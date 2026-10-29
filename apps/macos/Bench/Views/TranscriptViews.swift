import MarkdownUI
import SwiftUI

struct BenchMarkdown: View {
    @Environment(\.theme) private var theme
    let text: String

    var body: some View {
        Markdown(text)
            .markdownTextStyle { ForegroundColor(theme.colors.text); FontSize(TypeScale.md) }
            .markdownTextStyle(\.code) { FontFamilyVariant(.monospaced); FontSize(.em(0.92)); BackgroundColor(theme.colors.surface2) }
            .markdownBlockStyle(\.codeBlock) { configuration in
                ScrollView(.horizontal) {
                    configuration.label
                        .markdownTextStyle { FontFamilyVariant(.monospaced); FontSize(TypeScale.sm); ForegroundColor(theme.colors.wellText) }
                        .padding(Space.sm)
                }
                .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: theme.radiusSm))
                .markdownMargin(top: Space.xs, bottom: Space.sm)
            }
            .textSelection(.enabled)
    }
}

struct RowView: View {
    @Environment(\.theme) private var theme
    let row: Row
    let cwd: String
    let model: SessionModel

    var body: some View {
        switch row {
        case .user(_, let text, let attachments):
            HStack {
                Spacer(minLength: 80)
                VStack(alignment: .leading, spacing: Space.xs) {
                    if !attachments.isEmpty {
                        HStack { ForEach(attachments, id: \.self) { Pill(text: $0, color: theme.colors.info) } }
                    }
                    if !text.isEmpty { BenchMarkdown(text: text) }
                }
                .padding(.horizontal, Space.md)
                .padding(.vertical, Space.sm)
                .background(theme.colors.surface3, in: RoundedRectangle(cornerRadius: theme.radius))
            }
        case .text(_, let text, let sub):
            BenchMarkdown(text: text).padding(.leading, sub ? Space.lg : 0)
        case .thinking(let id, let eventId, let index, let length, let text):
            Disclosure(head: { Text("Thinking" + (length > 0 ? " · \(length) characters" : "")).foregroundStyle(theme.colors.muted) },
                       onOpen: { model.openThinking(id: id, eventId: eventId, index: index) }) {
                let body = text ?? model.thinking[id]
                if let body, !body.isEmpty { BenchMarkdown(text: body) } else { Text("Loading…").foregroundStyle(theme.colors.muted) }
            }
        case .tools(_, let calls, let sub):
            ToolRunView(calls: calls, cwd: cwd).padding(.leading, sub ? Space.lg : 0)
        case .turnEnd(_, let ok, let label, let reply):
            HStack {
                Text(label).font(.system(size: TypeScale.xs, weight: .medium)).foregroundStyle(ok ? theme.colors.muted : theme.colors.bad)
                Spacer()
                if !reply.isEmpty {
                    Button { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(reply, forType: .string) } label: {
                        Image(systemName: "doc.on.doc")
                    }
                    .buttonStyle(.borderless)
                    .foregroundStyle(theme.colors.muted)
                    .help("Copy reply")
                }
            }
        case .system(_, let text, let tone):
            Text(text)
                .font(.system(size: TypeScale.sm))
                .foregroundStyle(tone == .bad ? theme.colors.bad : tone == .info ? theme.colors.info : theme.colors.muted)
                .textSelection(.enabled)
        case .check(_, let ok, let head, let command, let output):
            Disclosure(head: {
                Pill(text: head, color: ok ? theme.colors.good : theme.colors.bad)
                Text(command).font(.system(size: TypeScale.sm, design: .monospaced)).lineLimit(1)
            }) { CodeBlock(text: output) }
        }
    }
}

/// A row that opens to show more.
struct Disclosure<Head: View, Content: View>: View {
    @Environment(\.theme) private var theme
    @ViewBuilder let head: () -> Head
    var flag: AnyView? = nil
    var onOpen: () -> Void = {}
    @ViewBuilder let content: () -> Content
    @State private var open = false

    init(@ViewBuilder head: @escaping () -> Head, flag: AnyView? = nil, onOpen: @escaping () -> Void = {}, @ViewBuilder content: @escaping () -> Content) {
        self.head = head
        self.flag = flag
        self.onOpen = onOpen
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.15)) { open.toggle() }
                if open { onOpen() }
            } label: {
                HStack(spacing: Space.sm) {
                    Image(systemName: "chevron.right")
                        .rotationEffect(.degrees(open ? 90 : 0))
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(theme.colors.muted)
                    HStack(spacing: Space.sm) { head() }
                    Spacer(minLength: 0)
                    flag
                }
                .contentShape(Rectangle())
                .padding(Space.sm)
            }
            .buttonStyle(.plain)
            if open {
                content().padding([.horizontal, .bottom], Space.sm)
            }
        }
        .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: theme.radiusSm))
    }
}

/// What Claude did between two pieces of prose, folded to one quiet line. While it runs,
/// the line is the step in progress; open, it lists every step.
struct ToolRunView: View {
    @Environment(\.theme) private var theme
    let calls: [ToolCall]
    let cwd: String
    @State private var open = false

    var body: some View {
        let current = calls.last(where: \.pending)
        VStack(alignment: .leading, spacing: Space.sm) {
            Button { withAnimation(.easeOut(duration: 0.15)) { open.toggle() } } label: {
                HStack(spacing: Space.sm) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 9, weight: .semibold))
                        .rotationEffect(.degrees(open ? 90 : 0))
                    if let current {
                        StatusDot(status: "running")
                        Text(Transcript.headline(current, cwd: cwd))
                            .font(.system(size: TypeScale.sm, design: current.name == "Bash" ? .monospaced : .default))
                            .lineLimit(1)
                        Text("· step \(calls.filter { $0.name != "Thinking" }.count)")
                    } else {
                        Text(Transcript.summary(calls))
                        if calls.contains(where: \.failed) { Pill(text: "error", color: theme.colors.bad) }
                    }
                }
                .font(.system(size: TypeScale.sm))
                .foregroundStyle(theme.colors.muted)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open {
                VStack(alignment: .leading, spacing: Space.xs) {
                    ForEach(calls) { ToolCallView(call: $0, cwd: cwd) }
                }
                .padding(.leading, Space.lg)
            }
        }
    }
}

struct RunFlag: View {
    @Environment(\.theme) private var theme
    let pending: Bool
    let failed: Bool
    var body: some View {
        if pending { StatusDot(status: "running") }
        else if failed { Pill(text: "error", color: theme.colors.bad) }
    }
}

struct ToolCallView: View {
    @Environment(\.theme) private var theme
    let call: ToolCall
    let cwd: String

    var body: some View {
        let i = call.input
        Disclosure(head: {
            Text(Transcript.headline(call, cwd: cwd))
                .font(.system(size: TypeScale.sm, design: call.name == "Bash" ? .monospaced : .default))
                .foregroundStyle(theme.colors.text)
                .lineLimit(2)
        }, flag: AnyView(RunFlag(pending: call.pending, failed: call.failed))) {
            VStack(alignment: .leading, spacing: Space.sm) {
                switch call.name {
                case "Edit", "MultiEdit":
                    let edits = call.name == "MultiEdit" ? i["edits"].array : [i]
                    MiniDiff(edits: edits.map { ($0["old_string"].string ?? "", $0["new_string"].string ?? "") })
                case "Write":
                    CodeBlock(text: i["content"].string ?? "")
                case "TodoWrite":
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(i["todos"].array.enumerated()), id: \.offset) { _, t in
                            let st = t["status"].string
                            Text((st == "completed" ? "✓ " : st == "in_progress" ? "▸ " : "○ ") + (t["content"].string ?? ""))
                                .font(.system(size: TypeScale.sm))
                                .foregroundStyle(st == "completed" ? theme.colors.muted : theme.colors.text)
                        }
                    }
                case "Bash", "Read", "Glob", "Grep", "WebFetch", "WebSearch":
                    EmptyView()
                default:
                    if !i.object.isEmpty { CodeBlock(text: i.pretty) }
                }
                if let r = call.result, !r.text.isEmpty {
                    let lines = r.text.split(separator: "\n", omittingEmptySubsequences: false)
                    let text = lines.count > 300 ? lines.prefix(300).joined(separator: "\n") + "\n… \(lines.count - 300) more lines" : r.text
                    CodeBlock(text: text, color: r.isError ? theme.colors.bad : theme.colors.wellText)
                }
            }
        }
    }
}

struct CodeBlock: View {
    @Environment(\.theme) private var theme
    let text: String
    var color: Color? = nil

    var body: some View {
        ScrollView(.horizontal) {
            Text(text)
                .font(.system(size: TypeScale.sm, design: .monospaced))
                .foregroundStyle(color ?? theme.colors.wellText)
                .textSelection(.enabled)
                .fixedSize(horizontal: true, vertical: false)
                .padding(Space.sm)
        }
        .frame(maxHeight: 420)
        .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: theme.radiusSm))
    }
}

struct MiniDiff: View {
    @Environment(\.theme) private var theme
    let edits: [(String, String)]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(edits.enumerated()), id: \.offset) { _, e in
                ForEach(Array(e.0.split(separator: "\n", omittingEmptySubsequences: false).enumerated()), id: \.offset) { _, l in
                    line("-" + l, theme.colors.bad)
                }
                ForEach(Array(e.1.split(separator: "\n", omittingEmptySubsequences: false).enumerated()), id: \.offset) { _, l in
                    line("+" + l, theme.colors.good)
                }
            }
        }
        .padding(Space.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: theme.radiusSm))
    }

    private func line(_ s: String, _ c: Color) -> some View {
        Text(s)
            .font(.system(size: TypeScale.sm, design: .monospaced))
            .foregroundStyle(c)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(c.opacity(0.10))
            .textSelection(.enabled)
    }
}

struct Pill: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text)
            .font(.system(size: TypeScale.xs, weight: .medium))
            .foregroundStyle(color)
            .padding(.horizontal, Space.sm)
            .padding(.vertical, 2)
            .background(color.opacity(0.10), in: Capsule())
            .overlay(Capsule().strokeBorder(color.opacity(0.45)))
    }
}
