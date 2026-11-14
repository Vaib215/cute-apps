import AppKit
import SwiftUI

// A tiny pet in the menu bar, parked under the light sensor so it's easy to find.
// Blinks now and then, perks up when a hand is near the sensor, hops when a gesture fires,
// sleeps in the dark. Mouse: click / double-click / hold are assignable gestures, drag sideways
// to line it up with the sensor (offset is remembered), right-click for a menu.

enum PetKind: String, CaseIterable, Identifiable {
    case cat, dog, bunny, panda, fox, bear
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
}

final class PetModel: ObservableObject {
    @Published var kind: PetKind = .cat
    @Published var alert = false
    @Published var asleep = false
    @Published var hop = false

    func bounce() {
        withAnimation(.spring(response: 0.18, dampingFraction: 0.5)) { hop = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
            withAnimation(.spring(response: 0.25, dampingFraction: 0.6)) { self.hop = false }
        }
    }
}

// MARK: - Drawing

private enum Pal {
    static let ink = Color(red: 0.24, green: 0.2, blue: 0.22)
    static let pink = Color(red: 1.0, green: 0.58, blue: 0.68)
    static let cream = Color(red: 1.0, green: 0.97, blue: 0.93)
    static let white = Color(red: 0.99, green: 0.99, blue: 0.99)
    static let black = Color(red: 0.13, green: 0.12, blue: 0.13)
    static let tan = Color(red: 0.94, green: 0.72, blue: 0.45)
    static let brown = Color(red: 0.62, green: 0.43, blue: 0.3)
    static let darkBrown = Color(red: 0.45, green: 0.3, blue: 0.2)
    static let lightTan = Color(red: 0.98, green: 0.88, blue: 0.74)
    static let orange = Color(red: 1.0, green: 0.6, blue: 0.27)
}

/// Per-pet layout in the shared 30×24 box.
private extension PetKind {
    var eyeXs: [CGFloat] { self == .bunny ? [11.4, 18.6] : [10.4, 19.6] }
    var eyeY: CGFloat { self == .bunny ? 15.4 : 13.2 }
    /// Vertical shift of nose/mouth/blush relative to the cat layout.
    var faceDY: CGFloat { self == .bunny ? 2.2 : 0 }
    var darkNose: Bool { [.dog, .panda, .fox, .bear].contains(self) }
    var blush: Bool { self != .panda }

    func draw(_ ctx: GraphicsContext) {
        let ink = Pal.ink
        func outlined(_ path: Path, _ fill: Color) {
            ctx.stroke(path, with: .color(ink), lineWidth: 2.2)
            ctx.fill(path, with: .color(fill))
        }
        func tri(_ pts: [(CGFloat, CGFloat)], tipLift: CGFloat = 2) -> Path {
            Path { p in
                p.move(to: CGPoint(x: pts[0].0, y: pts[0].1))
                p.addQuadCurve(to: CGPoint(x: pts[2].0, y: pts[2].1), control: CGPoint(x: pts[1].0, y: pts[1].1 - tipLift))
                p.closeSubpath()
            }
        }
        let boxHead = Path(roundedRect: CGRect(x: 3.5, y: 4.5, width: 23, height: 18.5), cornerRadius: 7, style: .continuous)

        switch self {
        case .cat:
            let ears = tri([(4, 10), (5.5, 0.8), (13, 5.5)]).union(tri([(26, 10), (24.5, 0.8), (17, 5.5)]))
            ctx.stroke(ears, with: .color(ink), lineWidth: 2.2); ctx.stroke(boxHead, with: .color(ink), lineWidth: 2.2)
            ctx.fill(ears, with: .color(Pal.cream)); ctx.fill(boxHead, with: .color(Pal.cream))
            ctx.fill(tri([(6, 8), (6.8, 3.3), (11, 6)], tipLift: 0).union(tri([(24, 8), (23.2, 3.3), (19, 6)], tipLift: 0)),
                     with: .color(Pal.pink.opacity(0.85)))
            whiskers(ctx)

        case .fox:
            let ears = tri([(3.5, 11), (4, -0.5), (13, 5)], tipLift: 1).union(tri([(26.5, 11), (26, -0.5), (17, 5)], tipLift: 1))
            ctx.stroke(ears, with: .color(ink), lineWidth: 2.2); ctx.stroke(boxHead, with: .color(ink), lineWidth: 2.2)
            ctx.fill(ears, with: .color(Pal.orange)); ctx.fill(boxHead, with: .color(Pal.orange))
            ctx.fill(tri([(5.6, 8.5), (5.6, 2.6), (10.8, 5.6)], tipLift: 0).union(tri([(24.4, 8.5), (24.4, 2.6), (19.2, 5.6)], tipLift: 0)),
                     with: .color(Pal.black.opacity(0.75)))
            // White cheeks / muzzle
            var cheeks = ctx; cheeks.clip(to: boxHead)
            cheeks.fill(Path(ellipseIn: CGRect(x: 3, y: 14.5, width: 12, height: 10)), with: .color(Pal.white))
            cheeks.fill(Path(ellipseIn: CGRect(x: 15, y: 14.5, width: 12, height: 10)), with: .color(Pal.white))

        case .dog:
            outlined(boxHead, Pal.tan)
            var muzzle = ctx; muzzle.clip(to: boxHead)
            muzzle.fill(Path(ellipseIn: CGRect(x: 8.5, y: 13.4, width: 13, height: 9)), with: .color(Pal.lightTan))
            // Floppy ears hanging over the sides
            for (x, angle) in [(CGFloat(4.0), -10.0), (CGFloat(26.0), 10.0)] {
                var e = ctx
                e.translateBy(x: x, y: 9.5)
                e.rotate(by: .degrees(angle))
                let ear = Path(roundedRect: CGRect(x: -3.4, y: -4.5, width: 6.8, height: 12), cornerRadius: 3.4)
                e.stroke(ear, with: .color(ink), lineWidth: 2.2)
                e.fill(ear, with: .color(Pal.darkBrown))
            }

        case .bunny:
            let head = Path(roundedRect: CGRect(x: 4.5, y: 8.5, width: 21, height: 15), cornerRadius: 7, style: .continuous)
            for (x, angle) in [(CGFloat(10.6), -8.0), (CGFloat(19.4), 8.0)] {
                var e = ctx
                e.translateBy(x: x, y: 10)
                e.rotate(by: .degrees(angle))
                let ear = Path(roundedRect: CGRect(x: -2.7, y: -10.5, width: 5.4, height: 12), cornerRadius: 2.7)
                e.stroke(ear, with: .color(ink), lineWidth: 2.2)
                e.fill(ear, with: .color(Pal.white))
                e.fill(Path(roundedRect: CGRect(x: -1.2, y: -8.6, width: 2.4, height: 8), cornerRadius: 1.2), with: .color(Pal.pink.opacity(0.85)))
            }
            outlined(head, Pal.white)

        case .panda:
            for x in [CGFloat(2.6), 19.4] {
                let ear = Path(ellipseIn: CGRect(x: x, y: 1.6, width: 8, height: 8))
                ctx.stroke(ear, with: .color(ink), lineWidth: 2.2)
                ctx.fill(ear, with: .color(Pal.black))
            }
            outlined(boxHead, Pal.white)
            for (x, angle) in [(CGFloat(10.4), 20.0), (CGFloat(19.6), -20.0)] {
                var p = ctx
                p.translateBy(x: x, y: 13.6)
                p.rotate(by: .degrees(angle))
                p.fill(Path(ellipseIn: CGRect(x: -3.7, y: -4.6, width: 7.4, height: 9)), with: .color(Pal.black))
            }

        case .bear:
            for x in [CGFloat(2.6), 19.4] {
                let ear = Path(ellipseIn: CGRect(x: x, y: 1.6, width: 8, height: 8))
                ctx.stroke(ear, with: .color(ink), lineWidth: 2.2)
                ctx.fill(ear, with: .color(Pal.brown))
                ctx.fill(Path(ellipseIn: CGRect(x: x + 2.2, y: 3.8, width: 3.6, height: 3.6)), with: .color(Pal.lightTan))
            }
            outlined(boxHead, Pal.brown)
            var muzzle = ctx; muzzle.clip(to: boxHead)
            muzzle.fill(Path(ellipseIn: CGRect(x: 9.5, y: 13.8, width: 11, height: 8.2)), with: .color(Pal.lightTan))
        }
    }

    func drawFace(_ ctx: GraphicsContext) {
        let dy = faceDY
        if blush {
            ctx.fill(Path(ellipseIn: CGRect(x: 5.4, y: 16.6 + dy, width: 4.2, height: 2.4)), with: .color(Pal.pink.opacity(0.55)))
            ctx.fill(Path(ellipseIn: CGRect(x: 20.4, y: 16.6 + dy, width: 4.2, height: 2.4)), with: .color(Pal.pink.opacity(0.55)))
        }
        let nose = darkNose ? CGRect(x: 13.4, y: 15.0 + dy, width: 3.2, height: 2.2) : CGRect(x: 14.0, y: 15.4 + dy, width: 2.0, height: 1.4)
        ctx.fill(Path(ellipseIn: nose), with: .color(darkNose ? Pal.black : Pal.pink))
        let mouth = Path { p in
            p.move(to: CGPoint(x: 12.9, y: 17.4 + dy))
            p.addQuadCurve(to: CGPoint(x: 15, y: 17.6 + dy), control: CGPoint(x: 13.9, y: 19.2 + dy))
            p.addQuadCurve(to: CGPoint(x: 17.1, y: 17.4 + dy), control: CGPoint(x: 16.1, y: 19.2 + dy))
        }
        ctx.stroke(mouth, with: .color(Pal.ink), style: StrokeStyle(lineWidth: 0.8, lineCap: .round))
    }

    private func whiskers(_ ctx: GraphicsContext) {
        let whiskers = Path { p in
            for (x0, x1, dy) in [(5.5, 0.5, -0.8), (5.5, 0.5, 0.9), (24.5, 29.5, -0.8), (24.5, 29.5, 0.9)] {
                p.move(to: CGPoint(x: x0, y: 15.2)); p.addLine(to: CGPoint(x: x1, y: 15.2 + dy * 1.6))
            }
        }
        ctx.stroke(whiskers, with: .color(Pal.ink.opacity(0.55)), style: StrokeStyle(lineWidth: 0.6, lineCap: .round))
    }
}

/// A chibi pet head in a 30×24 box: shared big shiny eyes, ω mouth, blush and soft outline (reads on
/// light and dark menu bars), per-pet ears/fur/markings.
struct PetFace: View {
    @ObservedObject var cat: PetModel
    @State private var blink = false
    @State private var zFloat = false

    var body: some View {
        let kind = cat.kind
        let eyesClosed = blink || cat.asleep
        ZStack(alignment: .topLeading) {
            Canvas { ctx, _ in
                kind.draw(ctx)
                kind.drawFace(ctx)
            }
            .frame(width: 30, height: 24)

            // Eyes: big and shiny; ‿ when blinking/asleep, widen when a hand is near.
            ForEach(kind.eyeXs, id: \.self) { cx in
                ZStack {
                    ZStack(alignment: .topLeading) {
                        Ellipse().fill(Pal.ink).frame(width: 4.6, height: 5.4)
                        Circle().fill(.white).frame(width: 1.9, height: 1.9).offset(x: 0.8, y: 0.9)
                        Circle().fill(.white.opacity(0.8)).frame(width: 0.9, height: 0.9).offset(x: 2.9, y: 3.4)
                    }
                    .scaleEffect(x: cat.alert ? 1.15 : 1, y: eyesClosed ? 0.2 : (cat.alert ? 1.15 : 1))
                    .opacity(eyesClosed ? 0 : 1)
                    Path { p in
                        p.move(to: CGPoint(x: 0.3, y: 1.6))
                        p.addQuadCurve(to: CGPoint(x: 4.9, y: 1.6), control: CGPoint(x: 2.6, y: 4.2))
                    }
                    .stroke(kind == .panda ? Pal.white : Pal.ink, style: StrokeStyle(lineWidth: 1.1, lineCap: .round))
                    .frame(width: 5.2, height: 4)
                    .opacity(eyesClosed ? 1 : 0)
                }
                .frame(width: 5.4, height: 5.6)
                .position(x: cx, y: kind.eyeY)
            }

            if cat.asleep {
                Text("z")
                    .font(.system(size: 7, weight: .heavy, design: .rounded))
                    .foregroundStyle(Pal.cream)
                    .shadow(color: Pal.ink, radius: 0.6)
                    .position(x: 29, y: zFloat ? 1 : 4)
                    .opacity(zFloat ? 0.3 : 1)
                    .onAppear { withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) { zFloat = true } }
                    .onDisappear { zFloat = false }
            }
        }
        .frame(width: 30, height: 24)
        .shadow(color: .black.opacity(0.35), radius: 1, y: 0.5)
        .opacity(cat.asleep ? 0.85 : 1)
        .scaleEffect(x: cat.hop ? 0.92 : 1, y: cat.hop ? 1.12 : 1, anchor: .bottom)   // squash-stretch hop
        .animation(.easeInOut(duration: 0.12), value: blink)
        .animation(.spring(response: 0.22, dampingFraction: 0.6), value: cat.alert)
        .animation(.easeInOut(duration: 0.4), value: cat.asleep)
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Double.random(in: 2.5...6)))
                blink = true
                try? await Task.sleep(for: .milliseconds(130))
                blink = false
            }
        }
    }
}

// MARK: - Window + mouse handling

enum PetMouseGesture { case click, doubleClick, hold }

/// AppKit mouse handling on top of the SwiftUI pet: SwiftUI can't cleanly tell click /
/// double-click / long-press / drag apart on one view.
final class PetInteractionView: NSView {
    var onGesture: (PetMouseGesture) -> Void = { _ in }
    var wantsDoubleClick: () -> Bool = { false }
    var onHover: (Bool) -> Void = { _ in }
    var onDrag: (CGFloat) -> Void = { _ in }
    var onDragEnd: () -> Void = {}
    var menu_: () -> NSMenu = { NSMenu() }

    var holdAfter = 0.5
    // Our own double-click window: the system one (NSEvent.doubleClickInterval) can be 1s+,
    // which made every single click wait that long whenever a double-click action was assigned.
    var doubleClickWindow = 0.3
    private var lastClickUp = -Double.infinity
    private var downAt: NSPoint?
    private var dragging = false
    private var holdFired = false
    private var holdWork: DispatchWorkItem?
    private var clickWork: DispatchWorkItem?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? { frame.contains(point) ? self : nil }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
                                       owner: self, userInfo: nil))
    }
    override func mouseEntered(with event: NSEvent) { onHover(true) }
    override func mouseExited(with event: NSEvent) { onHover(false) }

    override func mouseDown(with event: NSEvent) {
        downAt = NSEvent.mouseLocation
        dragging = false
        holdFired = false
        holdWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.dragging, self.downAt != nil else { return }
            self.holdFired = true
            self.clickWork?.cancel()
            self.onGesture(.hold)
        }
        holdWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + holdAfter, execute: work)
    }

    override func mouseDragged(with event: NSEvent) {
        guard let start = downAt else { return }
        let p = NSEvent.mouseLocation
        if !dragging && hypot(p.x - start.x, p.y - start.y) > 3 {
            dragging = true
            holdWork?.cancel()
        }
        if dragging { onDrag(p.x) }
    }

    override func mouseUp(with event: NSEvent) {
        defer { downAt = nil }
        holdWork?.cancel()
        if dragging { dragging = false; onDragEnd(); return }
        guard !holdFired else { return }
        let now = ProcessInfo.processInfo.systemUptime
        guard wantsDoubleClick() else { onGesture(.click); return }
        if now - lastClickUp < doubleClickWindow {
            clickWork?.cancel()
            lastClickUp = -.infinity
            onGesture(.doubleClick)
        } else {
            // Wait out the (short) double-click window before treating it as a single click.
            lastClickUp = now
            let work = DispatchWorkItem { [weak self] in self?.onGesture(.click) }
            clickWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + doubleClickWindow, execute: work)
        }
    }

    override func rightMouseDown(with event: NSEvent) {
        NSMenu.popUpContextMenu(menu_(), with: event, for: self)
    }
}

final class PetWindow {
    static let size = CGSize(width: 34, height: 26)
    // Points right of the screen centre. Starting guess for "just right of the camera"; user drags to fine-tune.
    private static let offsetKey = "CatOffset"
    private var offset: CGFloat {
        get { UserDefaults.standard.object(forKey: Self.offsetKey) as? CGFloat ?? 40 }
        set { UserDefaults.standard.set(newValue, forKey: Self.offsetKey) }
    }

    let model = PetModel()
    var frame: NSRect { panel.frame }
    var onMoved: (() -> Void)?
    private let panel: NSPanel
    private var dragGrab: CGFloat?
    private var mouse: PetInteractionView?

    func setTimings(doubleClick: Double, hold: Double) {
        mouse?.doubleClickWindow = doubleClick
        mouse?.holdAfter = hold
    }

    init(onGesture: @escaping (PetMouseGesture) -> Void, wantsDoubleClick: @escaping () -> Bool,
         onHover: @escaping (Bool) -> Void, onSettings: @escaping () -> Void,
         isPaused: @escaping () -> Bool, onTogglePause: @escaping () -> Void) {
        panel = NSPanel(contentRect: NSRect(origin: .zero, size: Self.size),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.sharingType = .none   // keep it out of screenshots and screen shares
        panel.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)   // above the notch panel
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]

        let root = NSView(frame: NSRect(origin: .zero, size: Self.size))
        let face = NSHostingView(rootView: PetFace(cat: model).frame(width: Self.size.width, height: Self.size.height))
        face.frame = root.bounds
        root.addSubview(face)
        let mouse = PetInteractionView(frame: root.bounds)
        self.mouse = mouse
        mouse.toolTip = "Cute Touch: the light sensor is right above me. Drag to line me up."
        mouse.onGesture = onGesture
        mouse.wantsDoubleClick = wantsDoubleClick
        mouse.onHover = onHover
        mouse.menu_ = {
            let menu = NSMenu()
            menu.addItem(ClosureMenuItem(title: "Settings…", handler: onSettings))
            menu.addItem(ClosureMenuItem(title: isPaused() ? "Resume detection" : "Pause detection", handler: onTogglePause))
            menu.addItem(.separator())
            menu.addItem(ClosureMenuItem(title: "Quit Cute Touch") { NSApp.terminate(nil) })
            return menu
        }
        root.addSubview(mouse)
        panel.contentView = root

        mouse.onDrag = { [weak self] x in self?.drag(to: x) }
        mouse.onDragEnd = { [weak self] in self?.dragGrab = nil }
        position()
        panel.orderFrontRegardless()
    }

    func position() {
        guard let screen = NSScreen.screens.first else { return }
        let menuBar = screen.frame.maxY - screen.visibleFrame.maxY
        let h = max(menuBar, Self.size.height)
        panel.setFrame(NSRect(x: screen.frame.midX + offset - Self.size.width / 2,
                              y: screen.frame.maxY - h + (h - Self.size.height) / 2,
                              width: Self.size.width, height: Self.size.height), display: true)
        onMoved?()
    }

    private func drag(to mouseX: CGFloat) {
        guard let screen = NSScreen.screens.first else { return }
        let grab = dragGrab ?? (mouseX - panel.frame.midX)
        dragGrab = grab
        offset = (mouseX - grab - screen.frame.midX).rounded()
        position()
    }
}

final class ClosureMenuItem: NSMenuItem {
    private let handler: () -> Void
    init(title: String, handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
    }
    required init(coder: NSCoder) { fatalError() }
    @objc private func fire() { handler() }
}

/// Lets the first click on an inactive (non-activating) panel reach the SwiftUI controls.
final class FirstMouseHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}
