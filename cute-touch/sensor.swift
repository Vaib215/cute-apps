import Foundation

// Cover-gestures on the ambient light sensor next to the webcam (M1 Air has no notch,
// lid-angle sensor or IMU — the ALS is the only usable "touch" point).
// Read via the private IOHIDEventSystemClient API; no entitlement needed.

private typealias P = OpaquePointer
@_silgen_name("IOHIDEventSystemClientCreate") private func HIDClientCreate(_ a: CFAllocator?) -> P?
@_silgen_name("IOHIDEventSystemClientSetMatching") private func HIDClientSetMatching(_ c: P, _ m: CFDictionary)
@_silgen_name("IOHIDEventSystemClientCopyServices") private func HIDClientCopyServices(_ c: P) -> CFArray?
@_silgen_name("IOHIDEventSystemClientScheduleWithRunLoop") private func HIDClientSchedule(_ c: P, _ rl: CFRunLoop, _ mode: CFString)
@_silgen_name("IOHIDEventSystemClientUnscheduleWithRunLoop") private func HIDClientUnschedule(_ c: P, _ rl: CFRunLoop, _ mode: CFString)
@_silgen_name("IOHIDEventSystemClientRegisterEventCallback") private func HIDClientRegisterEventCallback(
    _ c: P, _ cb: @convention(c) (UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, P?, P?) -> Void,
    _ target: UnsafeMutableRawPointer?, _ refcon: UnsafeMutableRawPointer?)
@_silgen_name("IOHIDEventGetType") private func HIDEventGetType(_ e: P) -> Int32
@_silgen_name("IOHIDServiceClientSetProperty") private func HIDServiceSetProperty(_ s: P, _ k: CFString, _ v: CFTypeRef) -> Bool
@_silgen_name("IOHIDEventGetFloatValue") private func HIDEventGetFloat(_ e: P, _ f: Int32) -> Double

enum Gesture: String, CaseIterable {
    // Light-sensor gestures
    case tap, doubleTap, tripleTap, hold
    // Mouse gestures on the menu-bar pet
    case petClick, petDoubleClick, petHold

    static let sensor: [Gesture] = [.tap, .doubleTap, .tripleTap, .hold]
    static let pet: [Gesture] = [.petClick, .petDoubleClick, .petHold]

    var title: String {
        switch self {
        case .tap: "Tap"
        case .doubleTap: "Double tap"
        case .tripleTap: "Triple tap"
        case .hold: "Hold"
        case .petClick: "Click"
        case .petDoubleClick: "Double click"
        case .petHold: "Hold click"
        }
    }
}

enum SensorState: Equatable {
    case watching
    case tooDark
    case unavailable
    case paused
}

final class LightGestureSensor {
    var onGesture: ((Gesture) -> Void)?
    var onState: ((SensorState) -> Void)?
    /// The cover just passed the Hold time; lifting now (before it counts as darkness) fires `.hold`.
    var onHoldArmed: (() -> Void)?
    var onNear: ((Bool) -> Void)?   // hand over/near the sensor; fired on change only
    private var near = false
    /// Fire `.tap` on the first release instead of waiting out the double-tap window. Only safe
    /// when a tap that turns out to be the start of a double tap can be undone (or there is no double tap).
    var instantTap = false
    /// Fire `.doubleTap` on the second release when no triple tap is assigned (nothing to wait for).
    var instantDouble = true

    // Thresholds are ratios of the current reading to the uncovered baseline.
    // Tuned 2026-11-13: a finger slightly off-centre only dims to ~0.35–0.45.
    /// How dark (vs. the uncovered baseline) a reading must get to count as covered. Lower = less sensitive.
    var coverRatio = 0.35
    private let releaseRatio = 0.7, nearRatio = 0.8
    // Fast double taps never let the light recover past releaseRatio at ~10Hz: the lift between
    // taps shows up as a bump inside one cover (e.g. 0.02 → 0.55 → 0.25). A rise of `liftBump` above
    // the cover's minimum counts as a lift; a drop of `recoverBump` below the peak since, a new cover.
    private let liftBump = 0.25, recoverBump = 0.2
    var holdAfter = 1.0, multiGap = 0.5   // seconds; set from Settings → Timing
    private let minBaseline = 15.0   // clear-channel counts; below this there's no light to block

    // Battery: the driver pushes each reading to us (event callback on the main run loop), so
    // there's no polling — the process only wakes per sample. We ask for 20ms; the driver clamps
    // to ~97ms (~10Hz), which is what quick taps need: at its default 378ms (or 200ms) interval
    // the sensor averages a short cover away. It reverts to 378ms when this client goes away.
    private let reportInterval = 20_000
    // In the dark there's nothing to block, so gestures pause and the sensor drops to ~1Hz until
    // the light comes back (two bright samples in a row).
    private let darkInterval = 1_000_000
    private var dark = false, brightSamples = 0
    // A "cover" this long is the room going dark (lights off), not a finger: re-baseline.
    var staleCover = 4.0
    private var coverStart = 0.0
    private var service: AnyObject?

    private var client: P?
    private var running = false
    private var holdWork: DispatchWorkItem?
    private var tapWork: DispatchWorkItem?

    private var baseline = -1.0
    private var covered = false, holdFired = false, taps = 0
    private var minCovered = 0.0, peakReleased = 1.0
    private var state: SensorState = .paused {
        didSet { if state != oldValue { onState?(state) } }
    }

    func start() {
        guard !running else { return }
        guard let c = HIDClientCreate(kCFAllocatorDefault) else { state = .unavailable; return }
        HIDClientSetMatching(c, ["PrimaryUsagePage": 0xff00, "PrimaryUsage": 4] as CFDictionary)
        guard let services = HIDClientCopyServices(c) as? [AnyObject], let s = services.first else {
            state = .unavailable
            return
        }
        service = s
        setInterval(reportInterval)
        HIDClientRegisterEventCallback(c, { _, refcon, _, event in
            guard let refcon, let event, HIDEventGetType(event) == 12 else { return }
            let sensor = Unmanaged<LightGestureSensor>.fromOpaque(refcon).takeUnretainedValue()
            sensor.ingest(HIDEventGetFloat(event, (12 << 16) + 4))   // clear channel
        }, nil, Unmanaged.passUnretained(self).toOpaque())
        HIDClientSchedule(c, CFRunLoopGetMain(), CFRunLoopMode.commonModes.rawValue)
        client = c
        running = true
        baseline = -1; covered = false; holdFired = false; taps = 0
        dark = false; brightSamples = 0
    }

    private func setInterval(_ us: Int) {
        guard let service else { return }
        _ = HIDServiceSetProperty(P(Unmanaged.passUnretained(service).toOpaque()), "ReportInterval" as CFString, us as CFNumber)
    }

    private func enterDark() {
        dark = true
        brightSamples = 0
        covered = false; taps = 0
        holdWork?.cancel(); tapWork?.cancel()
        if near { near = false; onNear?(false) }
        setInterval(darkInterval)
        state = .tooDark
    }

    func stop() {
        if let client { HIDClientUnschedule(client, CFRunLoopGetMain(), CFRunLoopMode.commonModes.rawValue) }
        client = nil   // releasing the client lets the driver fall back to its own interval
        service = nil
        running = false
        holdWork?.cancel()
        tapWork?.cancel()
        state = .paused
    }

    private func after(_ delay: Double, _ body: @escaping () -> Void) -> DispatchWorkItem {
        let work = DispatchWorkItem(block: body)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
        return work
    }

    fileprivate func ingest(_ clear: Double) {
        if dark {
            brightSamples = clear >= minBaseline * 1.3 ? brightSamples + 1 : 0
            guard brightSamples >= 2 else { return }
            dark = false
            baseline = clear
            setInterval(reportInterval)
        }
        if baseline < 0 { baseline = clear }
        let r = clear / max(baseline, 1)
        if !covered && r > nearRatio { baseline += 0.05 * (clear - baseline) }
        if covered && ProcessInfo.processInfo.systemUptime - coverStart > staleCover {
            // Never released: the room went dark (lights off), not a finger. No Hold, no tap.
            baseline = clear
            covered = false; holdFired = false; taps = 0
            holdWork?.cancel(); tapWork?.cancel()
        }

        guard baseline >= minBaseline else { enterDark(); return }
        state = .watching
        let isNear = covered || r < nearRatio
        if isNear != near { near = isNear; onNear?(isNear) }

        if covered { minCovered = min(minCovered, r) } else { peakReleased = max(peakReleased, r) }

        if !covered && r < coverRatio && (peakReleased >= releaseRatio || peakReleased - r >= recoverBump) {
            covered = true
            coverStart = ProcessInfo.processInfo.systemUptime
            minCovered = r
            holdFired = false
            tapWork?.cancel()
            // Hold only arms here and fires on release: endless darkness never releases, so the
            // lights going off can't trigger it.
            holdWork = after(holdAfter) { [weak self] in
                guard let self, self.covered else { return }
                self.holdFired = true
                self.taps = 0
                self.onHoldArmed?()
            }
        } else if covered && (r > releaseRatio || r - minCovered >= liftBump) {
            covered = false
            peakReleased = r
            holdWork?.cancel()
            if holdFired {
                holdFired = false
                onGesture?(.hold)
                return
            }
            taps += 1
            switch taps {
            case 1:
                if instantTap { onGesture?(.tap) }
            case 2 where instantDouble:
                taps = 0
                onGesture?(.doubleTap)
                return
            case 2:
                break
            default:
                taps = 0
            onGesture?(.tripleTap)
                return
            }
            let count = taps
            tapWork = after(multiGap) { [weak self] in
                guard let self, self.taps == count, !self.covered else { return }
                self.taps = 0
                if count == 2 { self.onGesture?(.doubleTap) }
                else if !self.instantTap { self.onGesture?(.tap) }
            }
        }
    }
}
