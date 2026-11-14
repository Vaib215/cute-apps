import AppKit
import SwiftUI

// Regular macOS settings window (the app is an accessory, so it activates itself to come forward).

final class SettingsWindowController {
    private var window: NSWindow?
    private let model: AppModel

    init(model: AppModel) { self.model = model }

    func show() {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 540, height: 780),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "Cute Touch Settings"
            w.isReleasedWhenClosed = false
            w.contentView = NSHostingView(rootView: SettingsView(model: model))
            w.center()
            window = w
        }
        model.accessibilityTrusted = ActionRunner.accessibilityTrusted
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

struct SettingsView: View {
    @ObservedObject var model: AppModel
    @State private var confirmReset = false

    var body: some View {
        Form {
            Section {
                HStack(spacing: 10) {
                    StatusDot(state: model.sensorState, paused: model.paused)
                    Text(statusText).foregroundStyle(.secondary)
                    Spacer()
                    Toggle("Pause detection", isOn: $model.paused).toggleStyle(.switch).labelsHidden()
                }
            } header: {
                Text("Sensor")
            } footer: {
                Text("Tap the light sensor just right of the camera. Your pet in the menu bar sits under it.")
            }

            Section {
                Picker("Sensitivity", selection: $model.sensitivity) {
                    Text("Low").tag(0)
                    Text("Medium").tag(1)
                    Text("High").tag(2)
                }
                .pickerStyle(.segmented)
            } footer: {
                Text("Lower sensitivity needs a firmer cover over the sensor, so a hand passing by won't trigger anything.")
            }

            Section("Gestures") {
                ForEach(Gesture.sensor, id: \.self) { g in
                    GestureRow(model: model, gesture: g)
                }
                if model.assignments.values.contains(where: { $0.kind.needsAccessibility }) && !model.accessibilityTrusted {
                    HStack {
                        Label("Media keys need Accessibility access", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                        Spacer()
                        Button("Grant…") { ActionRunner.requestAccessibility() }
                    }
                }
                Toggle("Click sound", isOn: $model.feedbackSound)
            }

            Section {
                PetPicker(selection: $model.pet)
                ForEach(Gesture.pet, id: \.self) { g in
                    GestureRow(model: model, gesture: g)
                }
            } header: {
                Text("Pet")
            } footer: {
                Text("Mouse gestures on the pet in the menu bar. Drag it sideways to line it up under the sensor; right-click for a menu.")
            }

            Section {
                TimingSlider("Double/triple tap window", value: $model.timings.tapGap, range: 0.2...1.2)
                TimingSlider("Hold (sensor), then lift", value: $model.timings.sensorHold, range: 0.4...2.5)
                TimingSlider("Long cover counts as dark after", value: $model.timings.darkAfter, range: 2...10)
                TimingSlider("Pet double-click window", value: $model.timings.petDoubleClick, range: 0.15...0.8)
                TimingSlider("Pet hold click", value: $model.timings.petHold, range: 0.25...1.5)
                TimingSlider("Peek panel on pet hover after", value: $model.timings.peekDelay, range: 0...1.5)
                TimingSlider("Close peeked panel after", value: $model.timings.peekClose, range: 0.1...2)
                TimingSlider("Window previews after", value: $model.timings.previewDelay, range: 0...1.5)
                HStack {
                    Spacer()
                    Button("Reset to defaults") { model.timings = AppModel.Timings() }
                        .disabled(model.timings == AppModel.Timings())
                }
            } header: {
                Text("Timing")
            } footer: {
                Text("Shorter tap and double-click windows make single taps/clicks fire sooner when a double action is set, but need quicker double taps.")
            }

            Section("Notch panel") {
                Picker("Style", selection: $model.glassUI) {
                    Text("Dark notch").tag(false)
                    Text("Liquid Glass").tag(true)
                }
                .pickerStyle(.segmented)
                Toggle("Pinned apps", isOn: $model.showPinned)
                Toggle("Recent apps", isOn: $model.showRecent)
            }

            Section {
                ForEach(model.pinnedApps, id: \.self) { path in
                    HStack(spacing: 10) {
                        Image(nsImage: AppIcons.icon(path)).resizable().frame(width: 20, height: 20)
                        Text(AppIcons.name(path))
                        Spacer()
                        Button {
                            model.pinnedApps.removeAll { $0 == path }
                        } label: {
                            Image(systemName: "minus.circle.fill").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .help("Unpin")
                    }
                }
                .onMove { model.pinnedApps.move(fromOffsets: $0, toOffset: $1) }
                Button("Add apps…") { model.pickPinnedApps() }
            } header: {
                Text("Pinned apps")
            }

            Section {
                HStack {
                    Button("Reset all settings…") { confirmReset = true }
                    Spacer()
                    Button("Quit Cute Touch") { NSApp.terminate(nil) }
                }
            } footer: {
                Text("Reset restores gestures, sensitivity, timing, sound, style and pet. Pinned apps and the pet's position are kept.")
            }
        }
        .formStyle(.grouped)
        .frame(minWidth: 500, minHeight: 560)
        .confirmationDialog("Reset all Cute Touch settings to defaults?", isPresented: $confirmReset) {
            Button("Reset", role: .destructive) { model.resetToDefaults() }
        } message: {
            Text("Gestures, sensitivity, timing, sound, style and pet go back to defaults. Pinned apps are kept.")
        }
    }

    private var statusText: String {
        if model.paused { return "Paused" }
        switch model.sensorState {
        case .watching: return "Watching for taps"
        case .tooDark: return "Too dark to sense — sleeping"
        case .unavailable: return "Light sensor not found"
        case .paused: return "Paused"
        }
    }
}

struct GestureRow: View {
    @ObservedObject var model: AppModel
    let gesture: Gesture

    var body: some View {
        let action = model.action(for: gesture)
        VStack(alignment: .leading, spacing: 6) {
            LabeledContent {
                Button {
                    ActionMenu(model: model, gesture: gesture).popUp()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: action.kind.symbol)
                        Text(action.label).lineLimit(1)
                        Image(systemName: "chevron.up.chevron.down").font(.system(size: 9, weight: .semibold))
                    }
                }
            } label: {
                HStack(spacing: 8) {
                    GestureGlyph(gesture: gesture).foregroundStyle(.tertiary).frame(width: 26, alignment: .leading)
                    Text(gesture.title)
                }
            }
            if action.kind == .shell {
                TextField("Shell command, e.g. open -a Notes", text: Binding(
                    get: { model.action(for: gesture).value },
                    set: { model.assignments[gesture] = GestureAction(kind: .shell, value: $0) }))
                    .font(.system(size: 11, design: .monospaced))
            }
        }
    }
}

struct PetPicker: View {
    @Binding var selection: PetKind

    var body: some View {
        HStack(spacing: 6) {
            ForEach(PetKind.allCases) { kind in
                Button {
                    selection = kind
                } label: {
                    VStack(spacing: 4) {
                        PetFace(cat: Self.previewModel(kind))
                            .scaleEffect(1.5)
                            .frame(width: 46, height: 38)
                        Text(kind.title).font(.system(size: 10)).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(selection == kind ? Color.accentColor.opacity(0.18) : Color.clear))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(selection == kind ? Color.accentColor : Color.clear, lineWidth: 1.5))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private static var models: [PetKind: PetModel] = [:]
    private static func previewModel(_ kind: PetKind) -> PetModel {
        if let m = models[kind] { return m }
        let m = PetModel()
        m.kind = kind
        models[kind] = m
        return m
    }
}

struct TimingSlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>

    init(_ title: String, value: Binding<Double>, range: ClosedRange<Double>) {
        self.title = title
        _value = value
        self.range = range
    }

    var body: some View {
        LabeledContent(title) {
            HStack(spacing: 10) {
                Slider(value: $value, in: range, step: 0.05).frame(width: 150)
                Text(value < 1 ? "\(Int((value * 1000).rounded())) ms" : String(format: "%.2f s", value))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .frame(width: 58, alignment: .trailing)
            }
        }
    }
}
