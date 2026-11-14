import Foundation
import ServiceManagement

/// "Open at login" through a launchd agent embedded in the bundle
/// (Contents/Library/LaunchAgents), so it starts with --background and is restarted after a crash.
enum LoginItem {
    private static let service = SMAppService.agent(plistName: "com.vaib215.cutetouch.login.plist")

    static var isEnabled: Bool { service.status == .enabled }
    static var needsApproval: Bool { service.status == .requiresApproval }

    static func set(_ on: Bool) {
        do {
            if on { try service.register() } else { try service.unregister() }
        } catch {
            NSLog("Cute Touch: login item \(on ? "register" : "unregister") failed: \(error)")
        }
    }

    /// Turn it on the first time the app runs; after that it's the user's switch.
    static func enableOnFirstRun() {
        let key = "LoginItemInitialized"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        if !isEnabled { set(true) }
    }

    static func openSystemSettings() { SMAppService.openSystemSettingsLoginItems() }
}
