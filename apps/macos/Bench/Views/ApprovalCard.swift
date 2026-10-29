import SwiftUI

/// A tool call waiting on you: a permission, a question or a plan. Mirrors the web's
/// ApprovalCard and sends the same answers (src/contract.d.mts ApprovalAnswer).
struct ApprovalCard: View {
    @Environment(\.theme) private var theme
    let approval: Approval
    let cwd: String
    var sessionTitle: String? = nil
    var compact = false
    let busy: Bool
    let onAnswer: ([String: Any]) -> Void
    @State private var note = ""
    @State private var answers: [String: String] = [:]

    var body: some View {
        let i = approval.input
        VStack(alignment: .leading, spacing: Space.sm) {
            if let sessionTitle { Text(sessionTitle).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted).lineLimit(1) }
            switch approval.tool {
            case "ExitPlanMode":
                Text("Review the plan").font(.system(size: TypeScale.md, weight: .semibold))
                ScrollView { BenchMarkdown(text: i["plan"].string ?? "").frame(maxWidth: .infinity, alignment: .leading) }
                    .frame(maxHeight: compact ? 160 : 420)
                HStack {
                    Button("Approve, Accept Edits") { onAnswer(["allow": true, "mode": "acceptEdits"]) }
                        .buttonStyle(CapsuleButton(kind: .primary))
                        .keyboardShortcut(compact ? nil : KeyboardShortcut(.return, modifiers: [.command, .shift]))
                    Button("Approve, Ask Before Edits") { onAnswer(["allow": true, "mode": "default"]) }
                        .buttonStyle(CapsuleButton(kind: .outline))
                }
                HStack {
                    TextField("What should change?", text: $note).textFieldStyle(.roundedBorder)
                    Button("Keep Planning") { onAnswer(["allow": false, "message": note.isEmpty ? "Keep planning." : note]) }
                }
            case "AskUserQuestion":
                Text("Answer a question").font(.system(size: TypeScale.md, weight: .semibold))
                let qs = i["questions"].array
                ForEach(Array(qs.enumerated()), id: \.offset) { _, q in
                    questionView(q)
                }
                let done = qs.allSatisfy { !(answers[$0["question"].string ?? ""] ?? "").isEmpty }
                HStack {
                    Button("Answer") { onAnswer(["allow": true, "answers": answers]) }
                        .buttonStyle(CapsuleButton(kind: .primary))
                        .disabled(!done)
                    Button("Skip") { onAnswer(["allow": false, "message": "The user declined to answer."]) }
                        .buttonStyle(CapsuleButton(kind: .outline))
                }
            default:
                HStack(spacing: 10) {
                    Circle().fill(theme.colors.warn).frame(width: 8, height: 8)
                    Text(Self.ask(approval)).font(.system(size: TypeScale.md, weight: .semibold))
                    Spacer()
                    Text(approval.tool).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted)
                }
                if let r = approval.reason { Text(r).foregroundStyle(theme.colors.muted).font(.system(size: TypeScale.sm)) }
                preview(i)
                if !compact {
                    TextField("", text: $note, prompt: Text("Optional: tell Claude why, or what to do instead"))
                        .textFieldStyle(.plain)
                        .font(.system(size: 13))
                        .padding(.horizontal, 10)
                        .frame(height: 32)
                        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.colors.line))
                }
                HStack(spacing: Space.sm) {
                    Button("Allow") { onAnswer(["allow": true]) }
                        .buttonStyle(CapsuleButton(kind: .primary))
                        .keyboardShortcut(compact ? nil : KeyboardShortcut(.return, modifiers: [.command, .shift]))
                    Button("Allow for This Session") { onAnswer(["allow": true, "always": true]) }
                        .buttonStyle(CapsuleButton(kind: .outline))
                    Button("Deny") { onAnswer(["allow": false, "message": note]) }
                        .buttonStyle(CapsuleButton(kind: .danger))
                        .keyboardShortcut(compact ? nil : KeyboardShortcut(.delete, modifiers: .command))
                    Spacer()
                    if !compact {
                        Text("⇧⌘↩ allow · ⌘⌫ deny").font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted)
                    }
                }
            }
        }
        .disabled(busy)
        .padding(Space.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colors.surface2, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(theme.colors.warn.opacity(0.35)))
        .background(RoundedRectangle(cornerRadius: 20).fill(theme.colors.warn.opacity(0.06)).padding(-4))
    }

    /// What Claude is asking to do, as a short verb phrase.
    static func ask(_ a: Approval) -> String {
        switch a.tool {
        case "Bash": "Run a command"
        case "Edit", "MultiEdit": "Edit a file"
        case "Write": "Create a file"
        case "WebFetch": "Open a web page"
        case "WebSearch": "Search the web"
        case "NotebookEdit": "Edit a notebook"
        default: a.title ?? "Use \(a.tool)"
        }
    }

    @ViewBuilder
    private func questionView(_ q: JSONValue) -> some View {
        let question = q["question"].string ?? ""
        let multi = q["multiSelect"].bool
        let options = q["options"].array
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(question)
            FlowChips(options: options.map { ($0["label"].string ?? "", $0["description"].string) }, selected: selected(question, multi)) { label in
                let cur = answers[question] ?? ""
                if !multi { answers[question] = label; return }
                var set = cur.components(separatedBy: ", ").filter { !$0.isEmpty }
                if let k = set.firstIndex(of: label) { set.remove(at: k) } else { set.append(label) }
                answers[question] = set.joined(separator: ", ")
            }
            TextField("Or type an answer", text: Binding(
                get: { let a = answers[question] ?? ""; return options.contains { $0["label"].string == a } ? "" : a },
                set: { answers[question] = $0 }
            ))
            .textFieldStyle(.roundedBorder)
        }
    }

    private func selected(_ q: String, _ multi: Bool) -> Set<String> {
        let a = answers[q] ?? ""
        return multi ? Set(a.components(separatedBy: ", ")) : [a]
    }

    @ViewBuilder
    private func preview(_ i: JSONValue?) -> some View {
        switch approval.tool {
        case "Bash":
            Text(i["command"].string ?? "")
                .font(.system(size: TypeScale.sm, design: .monospaced))
                .foregroundStyle(theme.colors.wellText)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Space.md).padding(.vertical, 10)
                .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: 8))
        case "Edit", "MultiEdit":
            Text(Transcript.rel(i["file_path"].string, cwd: cwd)).font(.system(size: TypeScale.sm)).foregroundStyle(theme.colors.muted)
            let edits = approval.tool == "MultiEdit" ? i["edits"].array : (i.map { [$0] } ?? [])
            MiniDiff(edits: edits.map { ($0["old_string"].string ?? "", $0["new_string"].string ?? "") })
        case "Write":
            Text(Transcript.rel(i["file_path"].string, cwd: cwd)).font(.system(size: TypeScale.sm)).foregroundStyle(theme.colors.muted)
            CodeBlock(text: (i["content"].string ?? "").split(separator: "\n", omittingEmptySubsequences: false).prefix(60).joined(separator: "\n"))
        default:
            if let i, !i.object.isEmpty { CodeBlock(text: i.pretty) }
        }
    }
}

/// Options as toggle buttons that wrap onto as many lines as they need.
struct FlowChips: View {
    @Environment(\.theme) private var theme
    let options: [(String, String?)]
    let selected: Set<String>
    let toggle: (String) -> Void

    var body: some View {
        FlowLayout(spacing: Space.sm) {
            ForEach(options, id: \.0) { option in
                let label = option.0
                let description = option.1
                let on = selected.contains(label)
                Button { toggle(label) } label: {
                    Text(label)
                        .padding(.horizontal, Space.sm)
                        .padding(.vertical, Space.xs)
                        .background(on ? theme.colors.accent.opacity(0.18) : theme.colors.surface1, in: Capsule())
                        .overlay(Capsule().strokeBorder(on ? theme.colors.accent : theme.colors.line))
                }
                .buttonStyle(.plain)
                .help(description ?? "")
            }
        }
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += line + spacing; line = 0 }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            line = max(line, size.height)
        }
        return CGSize(width: min(widest, width), height: y + line)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, line: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += line + spacing; line = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            line = max(line, size.height)
        }
    }
}

/// The design's pill buttons: filled accent, outlined, and outlined in the danger colour.
struct CapsuleButton: ButtonStyle {
    enum Kind { case primary, outline, danger }
    @Environment(\.theme) private var theme
    @Environment(\.isEnabled) private var enabled
    let kind: Kind

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: kind == .primary ? .semibold : .regular))
            .foregroundStyle(kind == .primary ? theme.colors.onAccent : kind == .danger ? theme.colors.bad : theme.colors.text)
            .padding(.horizontal, kind == .primary ? 16 : 14)
            .frame(height: 30)
            .background(kind == .primary ? theme.colors.accent : .clear, in: Capsule())
            .overlay { if kind != .primary { Capsule().strokeBorder(theme.colors.lineStrong) } }
            .opacity(configuration.isPressed ? 0.75 : enabled ? 1 : 0.45)
            .contentShape(Capsule())
    }
}
