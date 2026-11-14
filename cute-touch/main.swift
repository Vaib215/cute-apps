import AppKit
import Combine
import SwiftUI

// Cute Touch: tap the ambient light sensor next to the webcam to trigger gestures.
// - Runs headless (LSUIElement); LaunchAgent com.vaib215.cutetouch starts it with --background
// - A pet in the menu bar sits under the sensor; hover/click it (or a gesture) for the panel
// - The panel hangs below the pet: pinned + recent apps, window previews on hover, settings gear
// - Gestures run their action; feedback is the pet's hop + a click sound

final class AppModel: ObservableObject {
    @Published var expanded = false
    @Published var sensorState: SensorState = .paused
    @Published var shortcuts: [String] = []
    @Published var accessibilityTrusted = ActionRunner.accessibilityTrusted
    @Published var assignments: [Gesture: GestureAction] = AppModel.loadAssignments() {
        didSet { saveAssignments(); onAssignmentsChange?() }
    }
    @Published var paused = UserDefaults.standard.bool(forKey: "Paused") {
        didSet { UserDefaults.standard.set(paused, forKey: "Paused") }
    }
    @Published var feedbackSound = UserDefaults.standard.object(forKey: "FeedbackSound") as? Bool ?? true {
        didSet { UserDefaults.standard.set(feedbackSound, forKey: "FeedbackSound") }
    }
    @Published var glassUI = UserDefaults.standard.bool(forKey: "GlassUI") {
        didSet { UserDefaults.standard.set(glassUI, forKey: "GlassUI") }
    }
    // Notch panel launcher: apps the user pinned + apps recently brought to the front.
    @Published var pinnedApps = UserDefaults.standard.stringArray(forKey: "PinnedApps") ?? [] {
        didSet { UserDefaults.standard.set(pinnedApps, forKey: "PinnedApps") }
    }
    @Published var recentApps = UserDefaults.standard.stringArray(forKey: "RecentApps") ?? [] {
        didSet { UserDefaults.standard.set(recentApps, forKey: "RecentApps") }
    }
    @Published var showPinned = UserDefaults.standard.object(forKey: "ShowPinned") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showPinned, forKey: "ShowPinned") }
    }
    @Published var showRecent = UserDefaults.standard.object(forKey: "ShowRecent") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showRecent, forKey: "ShowRecent") }
    }
    static let recentLimit = 6

    /// Every user-tunable duration, in seconds (Settings → Timing).
    struct Timings: Codable, Equatable {
        var tapGap = 0.5          // max gap between taps of a double/triple tap
        var sensorHold = 1.0      // cover this long = Hold
        var darkAfter = 4.0       // a cover longer than this is the room going dark
        var petDoubleClick = 0.3
        var petHold = 0.5
        var peekDelay = 0.25      // hover the pet this long to peek the panel open
        var peekClose = 0.45      // close a peeked panel this long after the pointer leaves
        var previewDelay = 0.35   // hover an app icon this long for window previews
    }
    @Published var timings: Timings = {
        guard let d = UserDefaults.standard.data(forKey: "Timings"), let t = try? JSONDecoder().decode(Timings.self, from: d) else { return Timings() }
        return t
    }() {
        didSet { UserDefaults.standard.set(try? JSONEncoder().encode(timings), forKey: "Timings"); onTimingsChange?() }
    }
    var onTimingsChange: (() -> Void)?

    /// Back to factory settings. Keeps the user's pinned apps, recents and pet position.
    func resetToDefaults() {
        assignments = Self.defaults
        sensitivity = 1
        timings = Timings()
        feedbackSound = true
        glassUI = false
        showPinned = true
        showRecent = true
        pet = .cat
        paused = false
    }
    @Published var pet = PetKind(rawValue: UserDefaults.standard.string(forKey: "Pet") ?? "") ?? .cat {
        didSet { UserDefaults.standard.set(pet.rawValue, forKey: "Pet"); onPetChange?() }
    }
    var onPetChange: (() -> Void)?
    /// 0 = low, 1 = medium, 2 = high.
    @Published var sensitivity = UserDefaults.standard.object(forKey: "Sensitivity") as? Int ?? 1 {
        didSet { UserDefaults.standard.set(sensitivity, forKey: "Sensitivity"); onSensitivityChange?() }
    }
    var onSensitivityChange: (() -> Void)?
    var coverRatio: Double { [0.2, 0.35, 0.5][min(max(sensitivity, 0), 2)] }
    @Published var runningPaths = Set<String>()

    func refreshRunning() {
        runningPaths = Set(NSWorkspace.shared.runningApplications.compactMap { $0.bundleURL?.path })
    }

    var onOpenSettings: (() -> Void)?

    // Hover: the cat peeks the panel open; it closes once the pointer has left the cat, the
    // panel and the previews. A click/tap opens it "sticky" instead.
    @Published var previews: [WindowPreview] = []
    /// Visible, clickable regions of the panel window (window coords, top-left origin): the rest
    /// of the window must let clicks through.
    var launcherRect = CGRect.zero
    var previewRect = CGRect.zero
    @Published var previewApp: String?
    private var hoverOpened = false
    private var overCat = false, overPanel = false, overPreview = false
    private var hoverCloseWork: DispatchWorkItem?
    private var previewWork: DispatchWorkItem?

    func catHovered(_ on: Bool) {
        overCat = on
        if on {
            hoverCloseWork?.cancel()
            guard !expanded else { return }
            let work = DispatchWorkItem { [weak self] in
                guard let self, self.overCat, !self.expanded else { return }
                self.setExpanded(true)
                self.hoverOpened = true
            }
            hoverCloseWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + timings.peekDelay, execute: work)
        } else {
            scheduleHoverClose()
        }
    }

    func panelHovered(_ on: Bool) {
        overPanel = on
        if on { hoverCloseWork?.cancel() } else { scheduleHoverClose() }
    }

    func previewHovered(_ on: Bool) {
        overPreview = on
        if on { previewWork?.cancel(); hoverCloseWork?.cancel() } else { iconHovered(nil); scheduleHoverClose() }
    }

    private func scheduleHoverClose() {
        hoverCloseWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.overCat, !self.overPanel, !self.overPreview else { return }
            if !self.expanded {
                return
            }
            if self.hoverOpened { self.setExpanded(false) }
        }
        hoverCloseWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + timings.peekClose, execute: work)
    }

    /// Hovering a running app's icon shows its window thumbnails after a short dwell.
    func iconHovered(_ path: String?) {
        previewWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            guard let path else {
                guard !self.overPreview else { return }
                withAnimation(.easeOut(duration: 0.15)) { self.previewApp = nil; self.previews = [] }
                return
            }
            guard WindowPreviews.runningApp(at: path) != nil else {
                withAnimation(.easeOut(duration: 0.15)) { self.previewApp = nil; self.previews = [] }
                return
            }
            Task { @MainActor in
                let shots = await WindowPreviews.capture(appAt: path)
                guard self.expanded else { return }
                withAnimation(.spring(response: 0.25, dampingFraction: 0.85)) {
                    self.previewApp = path
                    self.previews = shots
                }
            }
        }
        previewWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + (path == nil ? 0.3 : (previewApp == nil ? timings.previewDelay : 0.1)), execute: work)
    }

    /// Recents not already pinned and still installed, newest first.
    var visibleRecents: [String] {
        Array(recentApps.filter {
            !pinnedApps.contains($0) && !$0.hasSuffix("/Finder.app") && FileManager.default.fileExists(atPath: $0)
        }.prefix(Self.recentLimit))
    }

    /// Every activated app, newest first (Finder included) — what "Switch to last app" walks.
    private var appHistory: [String] = []
    private static let finder = "com.apple.finder"

    func noteActivated(_ app: NSRunningApplication) {
        guard app.activationPolicy == .regular, let path = app.bundleURL?.path,
              app.bundleIdentifier != Bundle.main.bundleIdentifier else { return }
        appHistory = [path] + appHistory.filter { $0 != path }
        guard app.bundleIdentifier != Self.finder else { return }   // always running; not useful as a recent
        var list = recentApps.filter { $0 != path }
        list.insert(path, at: 0)
        recentApps = Array(list.prefix(20))
    }

    /// Cmd-Tab once: bring back the app that was frontmost before the current one.
    func switchToLastApp() {
        let front = NSWorkspace.shared.frontmostApplication?.bundleURL?.path
        guard let target = appHistory.first(where: {
            $0 != front && runningPaths.contains($0) && ($0.hasSuffix("/Finder.app") ? Self.finderHasWindows() : true)
        }) else { return }
        NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: target), configuration: .init())
    }

    /// Finder is always running (it draws the desktop); it only counts as "open" with a real window on screen.
    private static func finderHasWindows() -> Bool {
        guard let pid = NSRunningApplication.runningApplications(withBundleIdentifier: finder).first?.processIdentifier,
              let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]]
        else { return false }
        return list.contains { w in
            guard (w[kCGWindowOwnerPID as String] as? pid_t) == pid, (w[kCGWindowLayer as String] as? Int) == 0,
                  let b = w[kCGWindowBounds as String] as? [String: CGFloat] else { return false }
            return (b["Width"] ?? 0) >= 100 && (b["Height"] ?? 0) >= 100
        }
    }

    func openApp(_ path: String) {
        NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: path), configuration: .init())
        setExpanded(false)
    }

    func pickPinnedApps() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.application]
        panel.allowsMultipleSelection = true
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK else { return }
        for url in panel.urls where !pinnedApps.contains(url.path) { pinnedApps.append(url.path) }
    }

    var onExpandedChange: ((Bool) -> Void)?
    var onAssignmentsChange: (() -> Void)?
    private var instantToggleAt = -Double.infinity

    static let defaults: [Gesture: GestureAction] = [
        .tap: GestureAction(kind: .openPanel),
        .doubleTap: GestureAction(kind: .playPause),
        .tripleTap: GestureAction(kind: .screenshotFull),
        .petClick: GestureAction(kind: .openPanel),
        .petDoubleClick: GestureAction(kind: .none),
        .petHold: GestureAction(kind: .none),
        .hold: GestureAction(kind: .none),
    ]

    func action(for g: Gesture) -> GestureAction { assignments[g] ?? GestureAction(kind: .none) }

    /// A tap may fire before we know whether a second tap follows when that's harmless:
    /// opening the panel can be undone, and with no double-tap action there's nothing to wait for.
    var instantTap: Bool {
        action(for: .tap).kind == .openPanel
            || (action(for: .doubleTap).kind == .none && action(for: .tripleTap).kind == .none)
    }
    var instantDouble: Bool { action(for: .tripleTap).kind == .none }

    func setExpanded(_ on: Bool) {
        hoverOpened = false
        guard on != expanded else { return }
        if on {
            accessibilityTrusted = ActionRunner.accessibilityTrusted
        } else {
            previewWork?.cancel()
            previews = []
            previewApp = nil
            overPanel = false
            overPreview = false
        }
        withAnimation(on ? .spring(response: 0.26, dampingFraction: 0.82) : .easeOut(duration: 0.16)) { expanded = on }
        onExpandedChange?(on)
    }

    // AirPods head-gesture tick: soft and click-like.
    private static let clickSound = NSSound(
        contentsOfFile: "/System/Library/Components/CoreAudio.component/Contents/SharedSupport/SystemSounds/system/head_gestures_partial_nod.caf",
        byReference: true)

    func handle(_ g: Gesture) {
        let now = ProcessInfo.processInfo.systemUptime
        if feedbackSound, action(for: g).kind != .none {
            Self.clickSound?.stop()
            Self.clickSound?.play()
        }
        // The first tap of this double tap already toggled the panel instantly: undo that.
        if g == .doubleTap || g == .tripleTap, action(for: .tap).kind == .openPanel, now - instantToggleAt < 1.5 {
            instantToggleAt = -.infinity
            setExpanded(!expanded)
        }
        let action = action(for: g)
        if action.kind == .openPanel {
            if g == .tap { instantToggleAt = now }
            // Clicking the pet while the panel is only peeking (hover) pins it open.
            if Gesture.pet.contains(g), expanded, hoverOpened { hoverOpened = false; return }
            setExpanded(!expanded)
            return
        }
        guard action.kind != .none else { return }
        if action.kind == .switchApp { switchToLastApp() } else { ActionRunner.run(action) }
    }

    private static func loadAssignments() -> [Gesture: GestureAction] {
        guard let data = UserDefaults.standard.data(forKey: "Assignments"),
              let raw = try? JSONDecoder().decode([String: GestureAction].self, from: data) else { return defaults }
        var out = defaults
        for (k, v) in raw { if let g = Gesture(rawValue: k) { out[g] = v } }
        return out
    }

    private func saveAssignments() {
        let raw = Dictionary(uniqueKeysWithValues: assignments.map { ($0.key.rawValue, $0.value) })
        UserDefaults.standard.set(try? JSONEncoder().encode(raw), forKey: "Assignments")
    }
}

// MARK: - Notch window

final class NotchPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

extension EnvironmentValues {
    /// Liquid Glass look (native glassEffect) instead of the black notch look.
    @Entry var glassUI = false
}

extension View {
    /// Native Liquid Glass in glass mode; a flat fill in the black notch mode.
    @ViewBuilder
    func surface(_ glass: Bool, in shape: some Shape, fill: Color, interactive: Bool = false) -> some View {
        if glass {
            glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            background(shape.fill(fill))
        }
    }
}

struct NotchRoot: View {
    @ObservedObject var model: AppModel
    @Environment(\.colorScheme) private var systemScheme
    static let windowSize = CGSize(width: 820, height: 250)

    var body: some View {
        VStack(spacing: 0) {
            if model.expanded {
                if model.glassUI {
                    LauncherContent(model: model)
                        .padding(.horizontal, 6)
                        .surface(true, in: Capsule(), fill: .clear)
                        .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { model.launcherRect = $0 }
                        .transition(.scale(scale: 0.85, anchor: .top).combined(with: .opacity))
                } else {
                    LauncherContent(model: model)
                        .padding(.horizontal, 6)
                        .background(Capsule().fill(Color.black))
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.08)))
                        .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { model.launcherRect = $0 }
                        .transition(.scale(scale: 0.7, anchor: .top).combined(with: .opacity))
                }
            }
            Spacer(minLength: 0)
        }
        .frame(width: Self.windowSize.width, height: Self.windowSize.height, alignment: .top)
        .overlayPreferenceValue(IconAnchorKey.self) { anchors in
            GeometryReader { proxy in
                if model.expanded, let app = model.previewApp, let anchor = anchors[app], !model.previews.isEmpty {
                    let icon = proxy[anchor]
                    let width = CGFloat(model.previews.count) * (WindowPreviews.maxWidth + 10) + 10
                    let x = min(max(icon.midX, width / 2), proxy.size.width - width / 2)
                    PreviewStrip(model: model)
                        .fixedSize()
                        .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { model.previewRect = $0 }
                        .onDisappear { model.previewRect = .zero }
                        .position(x: x, y: icon.maxY + 22 + (WindowPreviews.thumbHeight + 40) / 2)
                        .transition(.opacity.combined(with: .scale(scale: 0.92, anchor: .top)))
                }
            }
        }
        .environment(\.glassUI, model.glassUI)
        .environment(\.colorScheme, model.glassUI ? systemScheme : .dark)
    }
}

/// Dots for taps, a bar for hold.
struct GestureGlyph: View {
    let gesture: Gesture
    var body: some View {
        HStack(spacing: 3) {
            switch gesture {
            case .tap: Circle().frame(width: 6, height: 6)
            case .doubleTap: ForEach(0..<2, id: \.self) { _ in Circle().frame(width: 6, height: 6) }
            case .tripleTap: ForEach(0..<3, id: \.self) { _ in Circle().frame(width: 6, height: 6) }
            case .hold: Capsule().frame(width: 18, height: 6)
            case .petClick: Image(systemName: "cursorarrow.click").font(.system(size: 11))
            case .petDoubleClick: Image(systemName: "cursorarrow.click.2").font(.system(size: 11))
            case .petHold: Image(systemName: "hand.point.up.left.fill").font(.system(size: 11))
            }
        }
        .frame(height: 6)
    }
}

/// The notch panel: one compact row of clickable app icons (pinned, then recent) and a settings gear.
struct LauncherContent: View {
    @ObservedObject var model: AppModel

    var body: some View {
        let recents = model.showRecent ? model.visibleRecents : []
        HStack(spacing: 6) {
            if model.showPinned {
                ForEach(model.pinnedApps, id: \.self) { path in
                    AppIconButton(path: path, running: model.runningPaths.contains(path), open: { model.openApp(path) }, onHover: { model.iconHovered($0 ? path : nil) }) {
                        Button("Unpin") { model.pinnedApps.removeAll { $0 == path } }
                    }
                }
            }
            if model.showPinned && !model.pinnedApps.isEmpty && !recents.isEmpty {
                Divider().frame(height: 26).padding(.horizontal, 4)
            }
            ForEach(recents, id: \.self) { path in
                AppIconButton(path: path, running: model.runningPaths.contains(path), open: { model.openApp(path) }, onHover: { model.iconHovered($0 ? path : nil) }) {
                    Button("Pin") { model.pinnedApps.append(path) }
                    Button("Remove from recents") { model.recentApps.removeAll { $0 == path } }
                }
            }
            if (!model.showPinned || model.pinnedApps.isEmpty) && recents.isEmpty {
                Text("Nothing here yet").font(.system(size: 11)).foregroundStyle(.secondary).padding(.horizontal, 8)
            }
            IconButton(symbol: "gearshape.fill", help: "Cute Touch settings") {
                model.setExpanded(false)
                model.onOpenSettings?()
            }
            .padding(.leading, 2)
        }
        .padding(.top, 8)
        .padding(.bottom, 11)
        .padding(.horizontal, 6)
        .onHover { model.panelHovered($0) }
    }
}

enum AppIcons {
    private static var cache: [String: NSImage] = [:]
    static func icon(_ path: String) -> NSImage {
        if let i = cache[path] { return i }
        let i = NSWorkspace.shared.icon(forFile: path)
        cache[path] = i
        return i
    }
    static func name(_ path: String) -> String {
        FileManager.default.displayName(atPath: path).replacingOccurrences(of: ".app", with: "")
    }
}

struct AppIconButton<Menu: View>: View {
    let path: String
    var running = false
    let open: () -> Void
    var onHover: (Bool) -> Void = { _ in }
    @ViewBuilder let menu: () -> Menu
    @State private var hover = false

    var body: some View {
        Button(action: open) {
            Image(nsImage: AppIcons.icon(path))
                .resizable()
                .interpolation(.high)
                .frame(width: 38, height: 38)
                .scaleEffect(hover ? 1.14 : 1)
                .animation(.spring(response: 0.22, dampingFraction: 0.65), value: hover)
                // Dock-style "open" dot.
                .overlay(alignment: .bottom) {
                    Circle().fill(Color.primary.opacity(0.75)).frame(width: 4, height: 4)
                        .offset(y: 6)
                        .opacity(running ? 1 : 0)
                }
        }
        .buttonStyle(.plain)
        .onHover { hover = $0; onHover($0) }
        .anchorPreference(key: IconAnchorKey.self, value: .bounds) { [path: $0] }
        .help(AppIcons.name(path))
        .contextMenu { menu() }
    }
}

struct StatusDot: View {
    let state: SensorState
    let paused: Bool

    var body: some View {
        let (color, help): (Color, String) = {
            if paused { return (.gray, "Paused") }
            switch state {
            case .watching: return (.green, "Watching")
            case .tooDark: return (.orange, "Too dark to sense")
            case .unavailable: return (.red, "Light sensor not found")
            case .paused: return (.gray, "Paused")
            }
        }()
        Circle().fill(color).frame(width: 6, height: 6).help(help)
    }
}

struct IconButton: View {
    let symbol: String
    let help: String
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 11, weight: .semibold))
                .frame(width: 26, height: 26)
                .foregroundStyle(hover ? .primary : .secondary)
                .background(Circle().fill(Color.primary.opacity(hover ? 0.12 : 0)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .help(help)
    }
}

/// Native NSMenu popped at the cursor: instant, keyboard-navigable, and renders SF Symbols.
final class ActionMenu: NSObject {
    private let model: AppModel
    private let gesture: Gesture
    private static var retained: ActionMenu?

    init(model: AppModel, gesture: Gesture) {
        self.model = model
        self.gesture = gesture
    }

    func popUp() {
        let current = model.action(for: gesture)
        let menu = NSMenu()
        for kind in ActionKind.simple {
            let item = add(menu, kind.title, kind.symbol) { [model, gesture] in model.assignments[gesture] = GestureAction(kind: kind) }
            item.state = current.kind == kind ? .on : .off
        }
        menu.addItem(.separator())
        let app = add(menu, current.kind == .openApp ? "Open \(current.label)…" : "Open app…", ActionKind.openApp.symbol) { [weak self] in self?.pickApp() }
        app.state = current.kind == .openApp ? .on : .off

        let sub = NSMenu()
        if model.shortcuts.isEmpty { sub.addItem(NSMenuItem(title: "No shortcuts found", action: nil, keyEquivalent: "")) }
        for name in model.shortcuts {
            let item = add(sub, name, nil) { [model, gesture] in model.assignments[gesture] = GestureAction(kind: .shortcut, value: name) }
            item.state = current.kind == .shortcut && current.value == name ? .on : .off
        }
        let shortcutItem = NSMenuItem(title: "Run shortcut", action: nil, keyEquivalent: "")
        shortcutItem.image = NSImage(systemSymbolName: ActionKind.shortcut.symbol, accessibilityDescription: nil)
        shortcutItem.submenu = sub
        shortcutItem.state = current.kind == .shortcut ? .on : .off
        menu.addItem(shortcutItem)

        let shell = add(menu, "Shell command", ActionKind.shell.symbol) { [model, gesture] in
            if current.kind != .shell { model.assignments[gesture] = GestureAction(kind: .shell) }
        }
        shell.state = current.kind == .shell ? .on : .off

        if current.kind != .none && current.kind != .openPanel {
            menu.addItem(.separator())
            add(menu, "Run now", "play.fill") { ActionRunner.run(current) }
        }

        ActionMenu.retained = self
        menu.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
    }

    @discardableResult
    private func add(_ menu: NSMenu, _ title: String, _ symbol: String?, _ handler: @escaping () -> Void) -> NSMenuItem {
        let item = ClosureMenuItem(title: title, handler: handler)
        if let symbol { item.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil) }
        menu.addItem(item)
        return item
    }

    private func pickApp() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.application]
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK, let url = panel.url {
            model.assignments[gesture] = GestureAction(kind: .openApp, value: url.path)
        }
    }
}

// MARK: - App

final class CuteTouchApp: NSObject, NSApplicationDelegate {
    private let model = AppModel()
    private let sensor = LightGestureSensor()
    private var panel: NotchPanel!
    private var cat: PetWindow!
    private lazy var settings = SettingsWindowController(model: model)
    private var clickMonitor: Any?
    private var moveMonitors: [Any] = []
    private var keyMonitor: Any?
    private var pausedObserver: AnyCancellable?

    func applicationDidFinishLaunching(_ note: Notification) {
        // Single instance: launchd and Launchpad can both start us. A second copy just asks the
        // running one to show its panel and exits.
        let me = ProcessInfo.processInfo.processIdentifier
        let wantsSettings = CommandLine.arguments.contains("--settings")
        if NSRunningApplication.runningApplications(withBundleIdentifier: "com.vaib215.cutetouch").contains(where: { $0.processIdentifier != me }) {
            if CommandLine.arguments.contains("--background") { exit(0) }   // login agent started a duplicate
            DistributedNotificationCenter.default().postNotificationName(
                .init(wantsSettings ? "com.vaib215.cutetouch.settings" : "com.vaib215.cutetouch.show"), object: nil, deliverImmediately: true)
            exit(0)
        }
        DistributedNotificationCenter.default().addObserver(forName: .init("com.vaib215.cutetouch.show"), object: nil, queue: .main) { [weak self] _ in
            self?.model.setExpanded(true)
        }
        DistributedNotificationCenter.default().addObserver(forName: .init("com.vaib215.cutetouch.settings"), object: nil, queue: .main) { [weak self] _ in
            self?.settings.show()
        }

        panel = NotchPanel(contentRect: NSRect(origin: .zero, size: NotchRoot.windowSize),
                           styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.sharingType = .none   // keep it out of screenshots and screen shares
        panel.level = .statusBar
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        panel.ignoresMouseEvents = true
        panel.contentView = FirstMouseHostingView(rootView: NotchRoot(model: model))
        panel.acceptsMouseMovedEvents = true
        positionPanel()
        panel.orderFrontRegardless()

        model.onExpandedChange = { [weak self] on in self?.expandedChanged(on) }
        cat = PetWindow(
            onGesture: { [weak self] g in
                guard let self else { return }
                let gesture: Gesture = switch g {
                case .click: .petClick
                case .doubleClick: .petDoubleClick
                case .hold: .petHold
                }
                if self.model.action(for: gesture).kind != .none { self.cat.model.bounce() }
                self.model.handle(gesture)
            },
            wantsDoubleClick: { [weak self] in
                guard let self else { return false }
                return self.model.action(for: .petDoubleClick).kind != ActionKind.none
            },
            onHover: { [weak self] on in self?.model.catHovered(on) },
            onSettings: { [weak self] in self?.settings.show() },
            isPaused: { [weak self] in self?.model.paused ?? false },
            onTogglePause: { [weak self] in self?.model.paused.toggle() })
        model.onOpenSettings = { [weak self] in self?.settings.show() }
        cat.model.kind = model.pet
        model.onPetChange = { [weak self] in
            guard let self else { return }
            self.cat.model.kind = self.model.pet
        }
        cat.onMoved = { [weak self] in self?.positionPanel() }
        positionPanel()

        // Recent apps + running dots for the notch launcher.
        if let front = NSWorkspace.shared.frontmostApplication { model.noteActivated(front) }
        model.refreshRunning()
        for name in [NSWorkspace.didLaunchApplicationNotification, NSWorkspace.didTerminateApplicationNotification] {
            NSWorkspace.shared.notificationCenter.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.model.refreshRunning()
            }
        }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didActivateApplicationNotification,
                                                          object: nil, queue: .main) { [weak self] note in
            guard let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
            self?.model.noteActivated(app)
        }
        sensor.onGesture = { [weak self] g in
            self?.cat.model.bounce()
            self?.model.handle(g)
        }
        sensor.onNear = { [weak self] near in self?.cat.model.alert = near }
        sensor.onHoldArmed = { [weak self] in self?.cat.model.bounce() }
        sensor.onState = { [weak self] s in
            self?.model.sensorState = s
            self?.cat.model.asleep = s != .watching
        }
        sensor.instantTap = model.instantTap
        sensor.instantDouble = model.instantDouble
        sensor.coverRatio = model.coverRatio
        applyTimings()
        model.onTimingsChange = { [weak self] in self?.applyTimings() }
        model.onSensitivityChange = { [weak self] in
            guard let self else { return }
            self.sensor.coverRatio = self.model.coverRatio
        }
        model.onAssignmentsChange = { [weak self] in
            guard let self else { return }
            self.sensor.instantTap = self.model.instantTap
            self.sensor.instantDouble = self.model.instantDouble
        }
        pausedObserver = model.$paused.sink { [weak self] p in
            guard let self else { return }
            DispatchQueue.main.async { p ? self.sensor.stop() : self.sensor.start() }
        }
        ActionRunner.loadShortcuts { [weak self] names in self?.model.shortcuts = names }

        NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification,
                                               object: nil, queue: .main) { [weak self] _ in
            self?.positionPanel()
            self?.cat.position()
        }
        // Don't burn sensor polls while the machine sleeps.
        let ws = NSWorkspace.shared.notificationCenter
        ws.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            self?.sensor.stop()
        }
        ws.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, !self.model.paused else { return }
            self.sensor.start()
        }

        LoginItem.enableOnFirstRun()
        if wantsSettings { settings.show() } else if !CommandLine.arguments.contains("--background") { model.setExpanded(true) }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        model.setExpanded(true)
        return false
    }

    /// The panel hangs just below the cat (which sits under the sensor), centred on it.
    private func positionPanel() {
        guard let screen = NSScreen.screens.first else { return }
        let size = NotchRoot.windowSize
        let anchor = cat?.frame ?? NSRect(x: screen.frame.midX, y: screen.frame.maxY - 26, width: 0, height: 26)
        let x = min(max(anchor.midX - size.width / 2, screen.frame.minX), screen.frame.maxX - size.width)
        panel.setFrame(NSRect(x: x, y: anchor.minY - 4 - size.height, width: size.width, height: size.height), display: true)
    }

    private func applyTimings() {
        let t = model.timings
        sensor.multiGap = t.tapGap
        sensor.holdAfter = t.sensorHold
        sensor.staleCover = t.darkAfter
        cat.setTimings(doubleClick: t.petDoubleClick, hold: t.petHold)
    }

    /// The panel window is larger than what's drawn in it; only take the mouse over the drawn parts.
    private func updateMousePassThrough() {
        guard model.expanded else { panel.ignoresMouseEvents = true; return }
        let p = NSEvent.mouseLocation
        let local = CGPoint(x: p.x - panel.frame.minX, y: panel.frame.maxY - p.y)
        let hit = [model.launcherRect, model.previewRect].contains { !$0.isEmpty && $0.insetBy(dx: -4, dy: -4).contains(local) }
        if panel.ignoresMouseEvents == hit { panel.ignoresMouseEvents = !hit }
    }

    private func expandedChanged(_ on: Bool) {
        if on {
            panel.ignoresMouseEvents = false   // until the first move tells us where the pointer is
            moveMonitors = [
                NSEvent.addGlobalMonitorForEvents(matching: .mouseMoved) { [weak self] _ in self?.updateMousePassThrough() } as Any,
                NSEvent.addLocalMonitorForEvents(matching: .mouseMoved) { [weak self] e in self?.updateMousePassThrough(); return e } as Any,
            ]
            panel.makeKeyAndOrderFront(nil)
            clickMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
                self?.model.setExpanded(false)
            }
            keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
                if e.keyCode == 53 { self?.model.setExpanded(false); return nil }   // Escape
                return e
            }
        } else {
            panel.ignoresMouseEvents = true
            moveMonitors.forEach { NSEvent.removeMonitor($0) }
            moveMonitors = []
            if let m = clickMonitor { NSEvent.removeMonitor(m) }
            if let m = keyMonitor { NSEvent.removeMonitor(m) }
            clickMonitor = nil
            keyMonitor = nil
            panel.resignKey()
        }
    }
}

let app = NSApplication.shared
let delegate = CuteTouchApp()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
