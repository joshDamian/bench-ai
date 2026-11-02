import SwiftUI

/// What a row's mark says at a glance. One source for the sidebar, the project table,
/// the menu bar and the palette, so they never disagree.
enum Mark: Equatable {
    case running, needs, ready, failed, idle

    @MainActor
    static func of(_ s: Session?, store: BenchStore) -> Mark {
        guard let s else { return .idle }
        if store.approvals.contains(where: { $0.session_id == s.id }) || s.status == "waiting" { return .needs }
        if s.status == "running" { return .running }
        if s.status == "error" { return .failed }
        return .idle
    }

    @MainActor
    static func of(task t: BenchTask, store: BenchStore) -> Mark {
        let sessions = store.sessions.filter { $0.task_id == t.id }
        if sessions.contains(where: { of($0, store: store) == .needs }) { return .needs }
        if sessions.contains(where: { $0.status == "running" }) || t.loop?.state == "looping" { return .running }
        if t.state != "active" { return .idle }
        if t.check_status == "passed" { return .ready }
        if t.check_status == "failed" { return .failed }
        return .idle
    }
}

/// The small mark before a row: a spinning ring while Claude works, a dot otherwise.
struct MarkView: View {
    @Environment(\.theme) private var theme
    let mark: Mark
    @State private var spin = false

    var body: some View {
        Group {
            switch mark {
            case .running:
                Circle()
                    .trim(from: 0, to: 0.75)
                    .stroke(theme.colors.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .frame(width: 9, height: 9)
                    .rotationEffect(.degrees(spin ? 360 : 0))
                    .animation(.linear(duration: 1).repeatForever(autoreverses: false), value: spin)
                    .onAppear { spin = true }
            case .needs: Circle().fill(theme.colors.warn).frame(width: 8, height: 8)
            case .ready: Circle().fill(theme.colors.good).frame(width: 8, height: 8)
            case .failed: Circle().fill(theme.colors.bad).frame(width: 8, height: 8)
            case .idle: Circle().fill(theme.colors.dotIdle).frame(width: 6, height: 6)
            }
        }
        .frame(width: 18)
    }
}

extension Mark {
    func tint(_ c: BenchColors) -> Color {
        switch self {
        case .running: c.accent
        case .needs: c.warn
        case .ready: c.good
        case .failed: c.bad
        case .idle: c.muted
        }
    }
}

@MainActor
enum Describe {
    /// The few words after a row's title: "Needs you", "Working", "Ready", or how long ago.
    static func session(_ s: Session, store: BenchStore) -> String {
        switch Mark.of(s, store: store) {
        case .needs: "Needs you"
        case .running: "Working"
        case .failed: "Error"
        default: Format.ago(s.updated_at)
        }
    }

    static func task(_ t: BenchTask, store: BenchStore) -> String {
        if t.state == "merged" { return "Merged" }
        if t.state == "closed" { return "Closed" }
        switch Mark.of(task: t, store: store) {
        case .needs: return "Needs you"
        case .running: return t.loop?.state == "looping" ? "Looping" : "Working"
        case .ready: return "Ready"
        case .failed: return "Check failed"
        case .idle: return Format.ago(t.updated_at)
        }
    }

    /// The task table's status column, which has room for more words.
    static func taskStatus(_ t: BenchTask, store: BenchStore) -> String {
        switch Mark.of(task: t, store: store) {
        case .ready: return "Check passed"
        case .failed: return "Check failed"
        case .idle: return t.state == "active" ? "Idle" : (t.state == "merged" ? "Merged" : "Closed")
        default: return task(t, store: store)
        }
    }

    static func host(_ store: BenchStore) -> String {
        URLComponents(string: store.api.config.baseURL)?.host?.split(separator: ".").first.map(String.init) ?? store.api.config.baseURL
    }
}

/// A capsule toolbar control: model, mode, branch.
struct ToolbarCapsule: ViewModifier {
    @Environment(\.theme) private var theme
    func body(content: Content) -> some View {
        content
            .font(.system(size: TypeScale.sm))
            .padding(.horizontal, Space.md)
            .frame(height: 28)
            .background(theme.colors.fill(0.08), in: Capsule())
            .contentShape(Capsule())
    }
}

extension View {
    func toolbarCapsule() -> some View { modifier(ToolbarCapsule()) }
}

/// The one filled button a screen has.
struct PrimaryButton: ButtonStyle {
    @Environment(\.theme) private var theme
    @Environment(\.isEnabled) private var enabled
    var height: CGFloat = 30
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: TypeScale.sm, weight: .semibold))
            .foregroundStyle(theme.colors.onAccent)
            .padding(.horizontal, 14)
            .frame(height: height)
            .background(theme.colors.accent, in: Capsule())
            .opacity(configuration.isPressed ? 0.8 : enabled ? 1 : 0.45)
            .contentShape(Capsule())
    }
}

/// Every other button: a quiet tonal capsule.
struct TonalButton: ButtonStyle {
    @Environment(\.theme) private var theme
    @Environment(\.isEnabled) private var enabled
    var danger = false
    var height: CGFloat = 30
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: TypeScale.sm))
            .foregroundStyle(danger ? theme.colors.bad : theme.colors.text)
            .padding(.horizontal, 14)
            .frame(height: height)
            .background(theme.colors.fill(configuration.isPressed ? 0.14 : 0.08), in: Capsule())
            .opacity(enabled ? 1 : 0.45)
            .contentShape(Capsule())
    }
}

/// The hint after a button's label: "⌘↩".
struct KeyHint: View {
    let keys: String
    init(_ keys: String) { self.keys = keys }
    var body: some View { Text(keys).font(.system(size: TypeScale.xs)).opacity(0.7) }
}

/// A grouped list in the System Settings style: rounded, tonal, rows split by hairlines.
struct Group2<Content: View>: View {
    @Environment(\.theme) private var theme
    @ViewBuilder let content: Content
    var body: some View {
        VStack(spacing: 0) { content }
            .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: theme.radius))
    }
}

/// One row of a Group2.
struct GroupRow<Trailing: View>: View {
    @Environment(\.theme) private var theme
    let title: String
    var sub: String? = nil
    var first = false
    var muted = false
    @ViewBuilder let trailing: Trailing

    var body: some View {
        HStack(spacing: Space.md) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.system(size: 13)).foregroundStyle(muted ? theme.colors.muted : theme.colors.text)
                if let sub, !sub.isEmpty { Text(sub).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted) }
            }
            Spacer(minLength: Space.md)
            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, sub == nil ? 0 : 7)
        .frame(minHeight: 40)
        .overlay(alignment: .top) { if !first { Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1) } }
    }
}

/// A group's heading, above it.
struct GroupLabel: View {
    @Environment(\.theme) private var theme
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.system(size: 12, weight: .semibold)).foregroundStyle(theme.colors.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 4).padding(.bottom, 2)
    }
}

/// The boards' segmented control: a tonal track with a quiet grey selection, not the
/// system's accent-filled one.
struct Segments<T: Hashable>: View {
    @Environment(\.theme) private var theme
    let options: [(T, String)]
    @Binding var selection: T
    var fill = false

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Array(options.enumerated()), id: \.offset) { _, o in
                let on = o.0 == selection
                Button { selection = o.0 } label: {
                    Text(o.1)
                        .font(.system(size: 12, weight: on ? .medium : .regular))
                        .foregroundStyle(on ? theme.colors.text : theme.colors.muted)
                        .lineLimit(1)
                        .padding(.horizontal, 12)
                        .frame(maxWidth: fill ? .infinity : nil)
                        .frame(height: 24)
                        .background(on ? theme.colors.fill(0.14) : .clear, in: RoundedRectangle(cornerRadius: 7))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(2)
        .background(theme.colors.fill(0.06), in: RoundedRectangle(cornerRadius: 9))
    }
}

/// A pane's header, as the boards draw it: 54pt, title on the left, controls at the
/// pane's own right edge. The window has no title bar; headers drag it.
struct PaneHeader<Leading: View, Trailing: View>: View {
    @ViewBuilder let leading: Leading
    @ViewBuilder let trailing: Trailing

    var body: some View {
        HStack(spacing: Space.sm) {
            leading
            Spacer(minLength: Space.md)
            trailing
        }
        .padding(.leading, 20)
        .padding(.trailing, 12)
        .frame(height: 54)
        .background(WindowDragArea())
    }
}

/// The title block a header leads with: name, and where it is.
struct HeaderTitle: View {
    @Environment(\.theme) private var theme
    let title: String
    let sub: String
    var mono = false

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
            if !sub.isEmpty {
                Text(sub).font(.system(size: 11.5, design: mono ? .monospaced : .default)).foregroundStyle(theme.colors.muted).lineLimit(1).truncationMode(.middle)
            }
        }
    }
}

/// A round icon toggle for headers: terminal, inspector.
struct HeaderToggle: View {
    @Environment(\.theme) private var theme
    let icon: String
    let help: String
    @Binding var on: Bool

    var body: some View {
        Button { on.toggle() } label: {
            Image(systemName: icon).font(.system(size: 13))
                .foregroundStyle(on ? theme.colors.text : theme.colors.muted)
                .frame(width: 30, height: 30)
                .background(on ? theme.colors.fill(0.1) : .clear, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

/// Empty space that moves the window, for a window with no title bar.
struct WindowDragArea: NSViewRepresentable {
    final class DragView: NSView { override var mouseDownCanMoveWindow: Bool { true } }
    func makeNSView(context: Context) -> NSView { DragView() }
    func updateNSView(_ v: NSView, context: Context) {}
}

/// The window's close, minimise and zoom buttons, drawn inside the sidebar pane as the
/// board does. The system's own are hidden, since macOS keeps moving them back.
struct TrafficLights: View {
    @State private var hover = false
    @State private var key = true
    /// In full screen macOS shows its own title bar on hover, with its own buttons.
    @State private var full = false

    var body: some View {
        HStack(spacing: 8) {
            light(Color(red: 1, green: 0.373, blue: 0.341), "xmark") { NSApp.keyWindow?.performClose(nil) }
            light(Color(red: 0.996, green: 0.737, blue: 0.180), "minus") { NSApp.keyWindow?.miniaturize(nil) }
            light(Color(red: 0.157, green: 0.784, blue: 0.251), "plus") { NSApp.keyWindow?.toggleFullScreen(nil) }
        }
        .opacity(full ? 0 : 1)
        .allowsHitTesting(!full)
        .onHover { hover = $0 }
        .background(SystemLightsHider())
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.willEnterFullScreenNotification)) { _ in full = true }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didExitFullScreenNotification)) { _ in full = false }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didBecomeKeyNotification)) { _ in key = true }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didResignKeyNotification)) { _ in key = NSApp.keyWindow != nil }
    }

    private func light(_ c: Color, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Circle().fill(key || hover ? c : Color.gray.opacity(0.35))
                .frame(width: 12, height: 12)
                .overlay { if hover { Image(systemName: icon).font(.system(size: 7, weight: .heavy)).foregroundStyle(.black.opacity(0.55)) } }
        }
        .buttonStyle(.plain)
    }
}

/// Hides the system's traffic lights in the window this view is in.
private struct SystemLightsHider: NSViewRepresentable {
    final class V: NSView {
        private var observers: [Any] = []

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            observers.forEach { NotificationCenter.default.removeObserver($0) }
            observers = []
            guard let w = window else { return }
            set(hidden: !w.styleMask.contains(.fullScreen))
            // Full screen's hover title bar needs the real buttons and the window's name.
            observers.append(NotificationCenter.default.addObserver(forName: NSWindow.willEnterFullScreenNotification, object: w, queue: .main) { [weak self] _ in self?.set(hidden: false) })
            observers.append(NotificationCenter.default.addObserver(forName: NSWindow.didExitFullScreenNotification, object: w, queue: .main) { [weak self] _ in self?.set(hidden: true) })
        }

        private func set(hidden: Bool) {
            guard let w = window else { return }
            for k in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] { w.standardWindowButton(k)?.isHidden = hidden }
            w.titleVisibility = hidden ? .hidden : .visible
        }
    }
    func makeNSView(context: Context) -> V { V() }
    func updateNSView(_ v: V, context: Context) {}
}
