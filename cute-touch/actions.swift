import AppKit
import ApplicationServices

enum ActionKind: String, Codable, CaseIterable {
    case none, openPanel, playPause, nextTrack, previousTrack, muteToggle, lockScreen, screenshot, screenshotFull
    case switchApp, openApp, shortcut, shell

    /// Kinds that need no extra value and can be picked straight from the menu.
    static let simple: [ActionKind] = [.openPanel, .switchApp, .playPause, .nextTrack, .previousTrack, .muteToggle, .lockScreen, .screenshotFull, .screenshot, .none]

    var title: String {
        switch self {
        case .none: "Nothing"
        case .openPanel: "Open panel"
        case .switchApp: "Switch to last app"
        case .playPause: "Play / Pause"
        case .nextTrack: "Next track"
        case .previousTrack: "Previous track"
        case .muteToggle: "Mute / unmute sound"
        case .lockScreen: "Lock screen"
        case .screenshot: "Screenshot area"
        case .screenshotFull: "Screenshot"
        case .openApp: "Open app"
        case .shortcut: "Run shortcut"
        case .shell: "Shell command"
        }
    }

    var symbol: String {
        switch self {
        case .none: "circle.slash"
        case .openPanel: "rectangle.topthird.inset.filled"
        case .switchApp: "arrow.left.arrow.right.square"
        case .playPause: "playpause.fill"
        case .nextTrack: "forward.fill"
        case .previousTrack: "backward.fill"
        case .muteToggle: "speaker.slash.fill"
        case .lockScreen: "lock.fill"
        case .screenshot: "camera.viewfinder"
        case .screenshotFull: "macwindow.on.rectangle"
        case .openApp: "app.fill"
        case .shortcut: "square.stack.3d.up.fill"
        case .shell: "terminal.fill"
        }
    }

    /// Media keys are posted as synthetic HID events, which macOS gates behind Accessibility.
    var needsAccessibility: Bool { [.playPause, .nextTrack, .previousTrack].contains(self) }
}

struct GestureAction: Codable, Equatable {
    var kind: ActionKind
    var value: String = ""

    var label: String {
        switch kind {
        case .openApp: value.isEmpty ? kind.title : FileManager.default.displayName(atPath: value).replacingOccurrences(of: ".app", with: "")
        case .shortcut: value.isEmpty ? kind.title : value
        case .shell: value.isEmpty ? kind.title : value
        default: kind.title
        }
    }
}

enum ActionRunner {
    static func run(_ action: GestureAction) {
        switch action.kind {
        case .none, .openPanel, .switchApp: break   // handled by the app model
        case .playPause: postMediaKey(16)        // NX_KEYTYPE_PLAY
        case .nextTrack: postMediaKey(17)        // NX_KEYTYPE_NEXT
        case .previousTrack: postMediaKey(18)    // NX_KEYTYPE_PREVIOUS
        case .muteToggle:
            NSAppleScript(source: "set volume output muted not (output muted of (get volume settings))")?.executeAndReturnError(nil)
        case .lockScreen: lockScreen()
        case .screenshot: launch("/usr/sbin/screencapture", ["-ic"])
        case .screenshotFull: launch("/usr/sbin/screencapture", ["-c"])
        case .openApp:
            guard !action.value.isEmpty else { return }
            NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: action.value), configuration: .init())
        case .shortcut:
            guard !action.value.isEmpty else { return }
            launch("/usr/bin/shortcuts", ["run", action.value])
        case .shell:
            guard !action.value.isEmpty else { return }
            launch("/bin/zsh", ["-lc", action.value])
        }
    }

    static var accessibilityTrusted: Bool { AXIsProcessTrusted() }

    static func requestAccessibility() {
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
    }

    private static func postMediaKey(_ key: Int) {
        for down in [true, false] {
            let flag = down ? 0xa : 0xb
            let event = NSEvent.otherEvent(
                with: .systemDefined, location: .zero,
                modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(flag << 8)),
                timestamp: 0, windowNumber: 0, context: nil,
                subtype: 8, data1: (key << 16) | (flag << 8), data2: -1)
            event?.cgEvent?.post(tap: .cghidEventTap)
        }
    }

    private static func lockScreen() {
        guard let handle = dlopen("/System/Library/PrivateFrameworks/login.framework/Versions/Current/login", RTLD_LAZY),
              let sym = dlsym(handle, "SACLockScreenImmediate") else { return }
        typealias LockFn = @convention(c) () -> Int32
        _ = unsafeBitCast(sym, to: LockFn.self)()
    }

    private static func launch(_ path: String, _ args: [String]) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        try? p.run()
    }

    /// Names from `shortcuts list`, loaded off the main thread.
    static func loadShortcuts(_ done: @escaping ([String]) -> Void) {
        DispatchQueue.global(qos: .utility).async {
            let p = Process(), pipe = Pipe()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/shortcuts")
            p.arguments = ["list"]
            p.standardOutput = pipe
            try? p.run()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            p.waitUntilExit()
            let names = String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init).sorted()
            DispatchQueue.main.async { done(names) }
        }
    }
}
