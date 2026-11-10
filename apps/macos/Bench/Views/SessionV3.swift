import AppKit
import SwiftUI

// The session view's newer pieces (the canvas's "Session v3" boards): find highlighting,
// quiet turn actions, the live activity strip, time dividers and the outline.

// MARK: - Find in conversation: every match marked, the current block's stronger

private struct FindQueryKey: EnvironmentKey { static let defaultValue = "" }
private struct FindCurrentKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    /// What ⌘F is looking for; empty when the find bar is closed.
    var findQuery: String {
        get { self[FindQueryKey.self] }
        set { self[FindQueryKey.self] = newValue }
    }
    /// Whether this block holds the match the find bar is on.
    var findCurrent: Bool {
        get { self[FindCurrentKey.self] }
        set { self[FindCurrentKey.self] = newValue }
    }
}

enum FindMark {
    /// Wraps each match outside code in ~~ ~~, which BenchMarkdown draws as a highlight
    /// (not a strikethrough) while a search is on. Code spans and fences are left alone.
    static func mark(_ text: String, _ query: String) -> String {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, text.range(of: q, options: .caseInsensitive) != nil else { return text }
        var out = ""
        var inFence = false
        for (i, line) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            if i > 0 { out += "\n" }
            if line.trimmingCharacters(in: .whitespaces).hasPrefix("```") { inFence.toggle(); out += line; continue }
            if inFence { out += line; continue }
            // Split on backticks: even pieces are prose, odd are inline code.
            let parts = line.split(separator: "`", omittingEmptySubsequences: false)
            out += parts.enumerated().map { j, p -> String in
                j % 2 == 1 ? String(p) : wrap(String(p), q)
            }.joined(separator: "`")
        }
        return out
    }

    private static func wrap(_ s: String, _ q: String) -> String {
        var out = ""
        var rest = s[...]
        while let r = rest.range(of: q, options: .caseInsensitive) {
            out += rest[..<r.lowerBound] + "~~" + rest[r] + "~~"
            rest = rest[r.upperBound...]
        }
        return out + rest
    }
}

// MARK: - Time

enum TurnTime {
    static func label(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        let cal = Calendar.current
        if cal.isDateInToday(d) { return "Today, " + f.string(from: d) }
        if cal.isDateInYesterday(d) { return "Yesterday, " + f.string(from: d) }
        f.dateFormat = "EEE d MMM, HH:mm"
        return f.string(from: d)
    }

    static func clock(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f.string(from: d)
    }

    /// "2 hours later", or a day label when the gap crosses days; nil for short gaps.
    static func gap(from a: Date, to b: Date) -> String? {
        let s = b.timeIntervalSince(a)
        guard s >= 3600 else { return nil }
        if !Calendar.current.isDate(a, inSameDayAs: b) { return label(b) }
        let h = Int(s / 3600)
        return h == 1 ? "An hour later" : "\(h) hours later"
    }
}

/// A hairline with a time in the middle, between turns.
struct TimeDivider: View {
    @Environment(\.theme) private var theme
    let text: String
    var body: some View {
        HStack(spacing: 12) {
            Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1)
            Text(text).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).fixedSize()
            Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Hover actions

/// Small text buttons that show when the pointer is over a turn or a message.
struct HoverActions: View {
    @Environment(\.theme) private var theme
    let items: [(String, () -> Void)]
    var trailing: String?

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, it in
                Button(it.0, action: it.1)
                    .buttonStyle(.plain)
                    .font(.system(size: 12))
                    .foregroundStyle(theme.colors.muted)
                    .padding(.horizontal, 7).frame(height: 24)
                    .contentShape(Rectangle())
            }
            if let trailing {
                Text(trailing).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).padding(.leading, 6)
            }
        }
    }
}

// MARK: - Live activity

/// While Claude works: the step in progress with a spinner and a timer, and the last two
/// finished steps fading beneath it.
struct ActivityStrip: View {
    @Environment(\.theme) private var theme
    let steps: [Row]
    let cwd: String
    let since: Date?

    var body: some View {
        let calls = steps.flatMap { r -> [ToolCall] in if case .tools(_, let c, _) = r { c } else { [] } }.filter { $0.name != "Thinking" }
        let current = calls.last(where: \.pending)
        let done = calls.filter { !$0.pending }.suffix(2).reversed()
        let n = Transcript.stepCount(steps)
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 9) {
                MarkView(mark: .running).frame(width: 12)
                if let current {
                    Text(verb(current)).font(.system(size: 12.5, weight: .medium))
                    Text(Transcript.headline(current, cwd: cwd).replacingOccurrences(of: "^\\S+ ", with: "", options: .regularExpression))
                        .font(BenchFont.mono(12)).lineLimit(1).truncationMode(.middle)
                } else {
                    Text("Thinking").font(.system(size: 12.5, weight: .medium))
                }
                Spacer(minLength: 8)
                Group {
                    if let since { Text("step \(max(n, 1)) · ") + Text(since, style: .timer) } else { Text("step \(max(n, 1))") }
                }
                .font(.system(size: 12).monospacedDigit()).foregroundStyle(theme.colors.muted)
            }
            ForEach(Array(done.enumerated()), id: \.offset) { i, c in
                HStack(spacing: 9) {
                    Image(systemName: c.failed ? "xmark" : "checkmark").font(.system(size: 9, weight: .bold))
                        .foregroundStyle(c.failed ? theme.colors.bad : theme.colors.good).frame(width: 12)
                    Text(Transcript.headline(c, cwd: cwd)).font(.system(size: 12)).lineLimit(1).truncationMode(.middle)
                }
                .foregroundStyle(theme.colors.muted)
                .opacity(i == 0 ? 1 : 0.65)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(theme.colors.fill(0.07)))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Claude is working")
    }

    private func verb(_ c: ToolCall) -> String {
        switch c.name {
        case "Edit", "MultiEdit": "Editing"
        case "Write": "Writing"
        case "Read": "Reading"
        case "Bash": "Running"
        case "Glob", "Grep": "Searching"
        case "WebFetch", "WebSearch": "Looking up"
        case "Task", "Agent": "Delegating"
        default: "Working"
        }
    }
}

// MARK: - Outline

/// A tick per message of yours down the right edge: hover to read it, click to go there.
/// Ticks with a find match are amber; the one in view is the accent.
struct ConversationOutline: View {
    @Environment(\.theme) private var theme
    let items: [(id: String, text: String, time: Date?, hit: Bool)]
    let current: String?
    let go: (String) -> Void
    @State private var hover: String?

    var body: some View {
        VStack(alignment: .trailing, spacing: 7) {
            ForEach(items, id: \.id) { it in
                Capsule()
                    .fill(it.id == current ? theme.colors.accent : (it.hit ? theme.colors.warn : theme.colors.muted.opacity(hover == it.id ? 0.9 : 0.45)))
                    .frame(width: hover == it.id || it.id == current ? 18 : 10, height: 2)
                    .frame(width: 26, height: 7, alignment: .trailing)
                    .contentShape(Rectangle())
                    .onHover { hover = $0 ? it.id : (hover == it.id ? nil : hover) }
                    .onTapGesture { go(it.id) }
                    .overlay(alignment: .trailing) {
                        if hover == it.id {
                            VStack(alignment: .leading, spacing: 2) {
                                if let t = it.time { Text(TurnTime.label(t)).font(.system(size: 11)).foregroundStyle(theme.colors.muted) }
                                Text(it.text).font(.system(size: 12)).lineLimit(3)
                            }
                            .padding(.horizontal, 11).padding(.vertical, 8)
                            .frame(width: 240, alignment: .leading)
                            .background(theme.colors.surface3, in: RoundedRectangle(cornerRadius: 9))
                            .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(theme.colors.fill(0.08)))
                            .shadow(color: .black.opacity(0.25), radius: 10, y: 5)
                            .offset(x: -34)
                            .fixedSize()
                            .allowsHitTesting(false)
                        }
                    }
                    .accessibilityLabel("Go to: \(it.text)")
                    .accessibilityAddTraits(.isButton)
            }
        }
        .animation(.snappy(duration: 0.15), value: hover)
    }
}
