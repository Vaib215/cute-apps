import AppKit
import ApplicationServices
import ScreenCaptureKit
import SwiftUI

// Windows-taskbar-style hover previews: live thumbnails of a running app's windows, via
// ScreenCaptureKit (needs Screen Recording). Clicking one raises that window (Accessibility,
// falls back to just activating the app).

struct WindowPreview: Identifiable {
    let id: CGWindowID
    let title: String
    let pid: pid_t
    let image: NSImage
}

enum WindowPreviews {
    static let thumbHeight: CGFloat = 96
    static let maxWidth: CGFloat = 170

    static func runningApp(at path: String) -> NSRunningApplication? {
        NSWorkspace.shared.runningApplications.first { $0.bundleURL?.path == path }
    }

    static func capture(appAt path: String) async -> [WindowPreview] {
        guard let app = runningApp(at: path),
              let content = try? await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        else { return [] }
        let all = content.windows.filter {
            $0.owningApplication?.processID == app.processIdentifier && $0.windowLayer == 0
                && $0.frame.width >= 120 && $0.frame.height >= 80
        }
        // Off-screen windows (other Spaces, minimized, hidden helpers) usually capture blank;
        // only fall back to them when the app has nothing on screen.
        let onScreen = all.filter(\.isOnScreen)
        let windows = (onScreen.isEmpty ? all.filter { !($0.title ?? "").isEmpty } : onScreen).prefix(4)
        var out: [WindowPreview] = []
        for w in windows {
            let cfg = SCStreamConfiguration()
            let scale = min(2 * thumbHeight / w.frame.height, 2 * maxWidth / w.frame.width)
            cfg.width = max(Int(w.frame.width * scale), 1)
            cfg.height = max(Int(w.frame.height * scale), 1)
            cfg.showsCursor = false
            guard let cg = try? await SCScreenshotManager.captureImage(
                contentFilter: SCContentFilter(desktopIndependentWindow: w), configuration: cfg),
                  !isBlank(cg) else { continue }
            let size = NSSize(width: CGFloat(cg.width) / 2, height: CGFloat(cg.height) / 2)
            out.append(WindowPreview(id: w.windowID, title: w.title ?? "", pid: app.processIdentifier,
                                     image: NSImage(cgImage: cg, size: size)))
        }
        return out
    }

    /// True when a capture came back empty: fully transparent or a single flat colour.
    private static func isBlank(_ image: CGImage) -> Bool {
        let n = 12
        var px = [UInt8](repeating: 0, count: n * n * 4)
        guard let ctx = CGContext(data: &px, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: n, height: n))
        let alphas = stride(from: 3, to: px.count, by: 4).map { Int(px[$0]) }
        if alphas.allSatisfy({ $0 < 8 }) { return true }
        let lum = stride(from: 0, to: px.count, by: 4).map { Int(px[$0]) + Int(px[$0 + 1]) + Int(px[$0 + 2]) }
        return (lum.max() ?? 0) - (lum.min() ?? 0) < 6
    }

    /// Bring one specific window forward: AX raise when trusted, otherwise just the app.
    static func focus(_ p: WindowPreview) {
        let app = NSRunningApplication(processIdentifier: p.pid)
        if AXIsProcessTrusted() {
            let ax = AXUIElementCreateApplication(p.pid)
            var value: CFTypeRef?
            if AXUIElementCopyAttributeValue(ax, kAXWindowsAttribute as CFString, &value) == .success,
               let windows = value as? [AXUIElement] {
                let match = windows.first { w in
                    var t: CFTypeRef?
                    AXUIElementCopyAttributeValue(w, kAXTitleAttribute as CFString, &t)
                    return (t as? String) == p.title
                }
                if let match {
                    AXUIElementSetAttributeValue(match, kAXMinimizedAttribute as CFString, kCFBooleanFalse)
                    AXUIElementPerformAction(match, kAXRaiseAction as CFString)
                    AXUIElementSetAttributeValue(match, kAXMainAttribute as CFString, kCFBooleanTrue)
                }
            }
        }
        app?.activate()
    }
}

/// Icon frames, so the preview strip can sit under the hovered icon.
struct IconAnchorKey: PreferenceKey {
    static let defaultValue: [String: Anchor<CGRect>] = [:]
    static func reduce(value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]) {
        value.merge(nextValue()) { $1 }
    }
}

struct PreviewStrip: View {
    @ObservedObject var model: AppModel
    @Environment(\.glassUI) private var glass

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            ForEach(model.previews) { p in
                PreviewThumb(preview: p) {
                    WindowPreviews.focus(p)
                    model.setExpanded(false)
                }
            }
        }
        .padding(10)
        .surface(glass, in: RoundedRectangle(cornerRadius: 18, style: .continuous), fill: .black)
        .onHover { model.previewHovered($0) }
    }
}

struct PreviewThumb: View {
    let preview: WindowPreview
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(nsImage: preview.image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: WindowPreviews.maxWidth, maxHeight: WindowPreviews.thumbHeight)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(Color.primary.opacity(hover ? 0.6 : 0.12), lineWidth: hover ? 2 : 1))
                Text(preview.title.isEmpty ? " " : preview.title)
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .frame(maxWidth: WindowPreviews.maxWidth)
            }
            .scaleEffect(hover ? 1.03 : 1)
            .animation(.easeOut(duration: 0.12), value: hover)
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .help(preview.title)
    }
}
