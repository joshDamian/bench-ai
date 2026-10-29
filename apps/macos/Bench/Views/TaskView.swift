import SwiftUI

/// What a task tab shows besides its sessions.
enum TaskPane: Hashable { case session(String), changes, log, loop }

@MainActor @Observable
final class TaskModel {
    let taskId: String
    let store: BenchStore
    var detail: TaskDetail?
    var diff: [DiffFile] = []
    var log: [TaskLogEntry] = []
    var busy: String?
    var error: String?

    init(taskId: String, store: BenchStore) { self.taskId = taskId; self.store = store }

    var task: BenchTask? { store.task(taskId) ?? detail?.task }
    var git: TaskGit? { detail?.git }

    func load() async {
        do {
            detail = try await store.api.task(taskId)
            diff = DiffFile.parse(try await store.api.taskDiff(taskId))
            log = try await store.api.taskLog(taskId)
        } catch { self.error = error.localizedDescription }
    }

    /// Runs a server action, then reloads what it may have changed.
    func run(_ label: String, _ op: @escaping () async throws -> Void) {
        busy = label
        Task {
            do { try await op() } catch { self.error = error.localizedDescription }
            busy = nil
            await load()
        }
    }
}

struct TaskView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let taskId: String
    @Binding var selection: String?
    @State private var model: TaskModel
    @State private var pane: TaskPane
    @State private var shipping = false

    init(store: BenchStore, taskId: String, sessionId: String?, selection: Binding<String?>) {
        self.store = store
        self.taskId = taskId
        _selection = selection
        _model = State(initialValue: TaskModel(taskId: taskId, store: store))
        _pane = State(initialValue: sessionId.map(TaskPane.session) ?? .changes)
    }

    private var sessions: [Session] {
        store.sessions.filter { $0.task_id == taskId && $0.archived_at == nil }
            .sorted { ($0.created_at ?? "") < ($1.created_at ?? "") }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            tabs
            Divider().overlay(theme.colors.line)
            Group {
                switch pane {
                case .session(let id):
                    if store.session(id) != nil { SessionView(store: store, sessionId: id).id(id) }
                    else { ContentUnavailableView("Session not found", systemImage: "questionmark.circle") }
                case .changes: ChangesView(files: model.diff)
                case .log: LogView(entries: model.log)
                case .loop: LoopView(model: model)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(theme.colors.bg)
        .task(id: store.task(taskId)) { await model.load() }
        .onChange(of: selection) { _, s in if let s, store.session(s)?.task_id == taskId { pane = .session(s) } }
        .sheet(isPresented: $shipping) { ShipSheet(model: model).environment(\.theme, theme) }
        .alert("Something went wrong", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
    }

    private var header: some View {
        let t = model.task
        let g = model.git
        return HStack(alignment: .center, spacing: Space.md) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: Space.sm) {
                    Text(t?.title ?? "Task").font(.system(size: TypeScale.md, weight: .semibold)).lineLimit(1)
                    if let c = t?.check_status { CheckBadge(status: c) }
                    if let s = t?.state, s != "active" { Text(s == "merged" ? "Merged" : "Closed").font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted) }
                }
                HStack(spacing: 4) {
                    Text("\(t?.branch ?? "") → \(t?.base_branch ?? "main")")
                    if let g {
                        Text("+\(g.added)").foregroundStyle(theme.colors.good)
                        Text("−\(g.removed)").foregroundStyle(theme.colors.bad)
                        if g.ahead > 0 { Text("· \(g.ahead) ahead") }
                        if g.behind > 0 { Text("· \(g.behind) behind") }
                    }
                }
                .font(.system(size: TypeScale.xs, design: .monospaced))
                .foregroundStyle(theme.colors.muted)
            }
            Spacer()
            if let b = model.busy { ProgressView().controlSize(.small); Text(b).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted) }
            if t?.state == "active" {
                if (g?.behind ?? 0) > 0 {
                    Button("Update from \(t?.base_branch ?? "main")") { model.run("Updating…") { try await store.api.taskAction(taskId, "update") } }
                }
                Button("Push") { model.run("Pushing…") { try await store.api.taskAction(taskId, "push") } }
                Button("Merge into \(t?.base_branch ?? "main")…") { shipping = true }
                    .buttonStyle(.borderedProminent)
                    .disabled(g?.canMerge?.ok == false || model.busy != nil)
                    .help(g?.canMerge?.reason ?? "")
            }
        }
        .controlSize(.small)
        .padding(.horizontal, Space.xl)
        .padding(.top, Space.md)
        .padding(.bottom, Space.sm)
    }

    private var tabs: some View {
        HStack(spacing: 2) {
            ForEach(sessions) { s in
                tab(s.title, .session(s.id), dot: s.status)
            }
            Button { newSession() } label: { Image(systemName: "plus") }
                .buttonStyle(.borderless)
                .help("New Session in This Task")
                .padding(.horizontal, Space.xs)
            Divider().frame(height: 16).padding(.horizontal, Space.xs)
            tab(model.diff.isEmpty ? "Changes" : "Changes \(model.diff.count)", .changes)
            tab("Log", .log)
            tab("Loop", .loop)
            Spacer()
        }
        .padding(.horizontal, Space.lg)
    }

    private func tab(_ label: String, _ p: TaskPane, dot: String? = nil) -> some View {
        Button {
            pane = p
            if case .session(let id) = p { selection = id }
        } label: {
            HStack(spacing: 6) {
                if let dot { StatusDot(status: dot) }
                Text(label).lineLimit(1)
            }
            .font(.system(size: TypeScale.sm))
            .foregroundStyle(pane == p ? theme.colors.text : theme.colors.muted)
            .padding(.horizontal, Space.sm)
            .frame(height: 32)
            .overlay(alignment: .bottom) {
                if pane == p { Rectangle().fill(theme.colors.accent).frame(height: 2) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: 200)
    }

    private func newSession() {
        guard let t = model.task else { return }
        model.run("Starting…") {
            let s = try await store.api.createSession(project: t.project_id, task: t.id, mode: Mode(rawValue: store.defaults.mode ?? "") ?? .acceptEdits)
            store.apply(s)
            selection = s.id
        }
    }
}

struct CheckBadge: View {
    @Environment(\.theme) private var theme
    let status: String
    var body: some View {
        let (label, color): (String, Color) = switch status {
        case "passed": ("Check passed", theme.colors.good)
        case "failed": ("Check failed", theme.colors.bad)
        default: ("Checking…", theme.colors.muted)
        }
        Text(label)
            .font(.system(size: TypeScale.xs, weight: .medium))
            .foregroundStyle(color)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(color.opacity(0.12), in: Capsule())
    }
}

// MARK: Changes

struct DiffFile: Identifiable, Hashable {
    var id: String { path }
    let path: String
    let lines: [String]
    var added: Int { lines.filter { $0.hasPrefix("+") && !$0.hasPrefix("+++") }.count }
    var removed: Int { lines.filter { $0.hasPrefix("-") && !$0.hasPrefix("---") }.count }

    /// Splits a unified diff into one entry per file, keeping hunks and their lines.
    static func parse(_ diff: String) -> [DiffFile] {
        var out: [DiffFile] = []
        var path: String?
        var lines: [String] = []
        func flush() { if let path { out.append(DiffFile(path: path, lines: lines)) }; lines = [] }
        for line in diff.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            if line.hasPrefix("diff --git ") {
                flush()
                path = line.components(separatedBy: " b/").last ?? line
            } else if path != nil, !line.hasPrefix("index "), !line.hasPrefix("--- "), !line.hasPrefix("+++ "),
                      !line.hasPrefix("new file mode"), !line.hasPrefix("deleted file mode") {
                lines.append(line)
            }
        }
        flush()
        return out
    }
}

struct ChangesView: View {
    @Environment(\.theme) private var theme
    let files: [DiffFile]
    @State private var selected: String?

    var body: some View {
        if files.isEmpty {
            ContentUnavailableView("No changes yet", systemImage: "doc.text", description: Text("What Claude changes in this task shows up here."))
        } else {
            HSplitView {
                List(files, selection: $selected) { f in
                    HStack {
                        Text((f.path as NSString).lastPathComponent).lineLimit(1).help(f.path)
                        Spacer()
                        if f.added > 0 { Text("+\(f.added)").foregroundStyle(theme.colors.good) }
                        if f.removed > 0 { Text("−\(f.removed)").foregroundStyle(theme.colors.bad) }
                    }
                    .font(.system(size: TypeScale.sm, design: .monospaced))
                    .tag(f.path)
                }
                .frame(minWidth: 200, idealWidth: 240, maxWidth: 340)
                ScrollView([.vertical, .horizontal]) {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(shown) { f in
                            Text(f.path)
                                .font(.system(size: TypeScale.sm, weight: .semibold, design: .monospaced))
                                .padding(.vertical, Space.sm)
                                .padding(.horizontal, Space.md)
                            ForEach(Array(f.lines.enumerated()), id: \.offset) { _, l in DiffLine(text: l) }
                        }
                    }
                    .textSelection(.enabled)
                    .padding(.bottom, Space.lg)
                }
                .background(theme.colors.wellBg)
            }
        }
    }

    private var shown: [DiffFile] { selected.map { s in files.filter { $0.path == s } } ?? files }
}

private struct DiffLine: View {
    @Environment(\.theme) private var theme
    let text: String
    var body: some View {
        let (fg, bg): (Color, Color) =
            text.hasPrefix("@@") ? (theme.colors.info, .clear) :
            text.hasPrefix("+") ? (theme.colors.good, theme.colors.good.opacity(0.10)) :
            text.hasPrefix("-") ? (theme.colors.bad, theme.colors.bad.opacity(0.10)) :
            (theme.colors.wellText, .clear)
        Text(text.isEmpty ? " " : text)
            .font(.system(size: TypeScale.sm, design: .monospaced))
            .foregroundStyle(fg)
            .fixedSize()
            .padding(.horizontal, Space.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(bg)
    }
}

// MARK: Log

struct LogView: View {
    @Environment(\.theme) private var theme
    let entries: [TaskLogEntry]

    var body: some View {
        if entries.isEmpty {
            ContentUnavailableView("No decisions logged", systemImage: "list.bullet.rectangle", description: Text("Decisions, reviews and loop attempts show up here."))
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Space.md) {
                    ForEach(entries.reversed()) { e in
                        VStack(alignment: .leading, spacing: Space.xs) {
                            HStack {
                                Text(e.kind == "review" ? "Review" : e.kind == "loop" ? "Loop" : "Decision")
                                    .font(.system(size: TypeScale.xs, weight: .semibold))
                                    .foregroundStyle(theme.colors.accent)
                                Spacer()
                                Text(Format.ago(e.created_at)).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted)
                            }
                            Text(e.decision).font(.system(size: TypeScale.md, weight: .medium))
                            if !e.evidence.isEmpty { BenchMarkdown(text: e.evidence) }
                        }
                        .padding(Space.md)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: theme.radius))
                    }
                }
                .frame(maxWidth: 860)
                .padding(Space.xl)
                .frame(maxWidth: .infinity)
            }
        }
    }
}

// MARK: Loop

struct LoopView: View {
    @Environment(\.theme) private var theme
    let model: TaskModel
    @State private var done = ""
    @State private var check = ""
    @State private var attempts = 5
    @State private var minutes = 60

    var body: some View {
        let t = model.task
        let looping = t?.loop?.state == "looping"
        Form {
            Section {
                TextField("What does done look like?", text: $done, axis: .vertical).lineLimit(2...5)
                TextField("Check command", text: $check, prompt: Text("npm test"))
                    .font(.system(size: TypeScale.sm, design: .monospaced))
            } header: { Text("Done") } footer: {
                Text("Bench runs the check in the task's worktree. A pass ends the loop; a failure sends Claude back with the output.")
            }
            Section("Limits") {
                Stepper("Up to \(attempts) attempts", value: $attempts, in: 1...50)
                Stepper("For up to \(minutes) minutes", value: $minutes, in: 5...600, step: 5)
            }
            if let l = t?.loop {
                Section("Status") {
                    LabeledContent("State", value: Self.label(l.state))
                    LabeledContent("Attempts", value: "\(l.attempts ?? 0) of \(l.max_attempts ?? attempts)")
                    if let r = l.reason { Text(r).foregroundStyle(theme.colors.muted) }
                }
            }
            if let out = t?.check_output, !out.isEmpty {
                Section("Last check") {
                    Text(out).font(.system(size: TypeScale.xs, design: .monospaced)).textSelection(.enabled)
                }
            }
            Section {
                HStack {
                    Button("Run Check") {
                        model.run("Checking…") {
                            try await model.store.api.editTask(model.taskId, ["done": done, "check": check])
                            try await model.store.api.taskAction(model.taskId, "check")
                        }
                    }
                    .disabled(check.trimmingCharacters(in: .whitespaces).isEmpty)
                    Spacer()
                    if looping {
                        Button("Stop Loop") { model.run("Stopping…") { try await model.store.api.editTask(model.taskId, ["loop": NSNull()]) } }
                    } else {
                        Button("Loop Until Done") {
                            model.run("Starting loop…") {
                                try await model.store.api.editTask(model.taskId, ["done": done, "check": check, "loop": ["max_attempts": attempts, "max_minutes": minutes]])
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(check.trimmingCharacters(in: .whitespaces).isEmpty || t?.state != "active")
                    }
                }
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .frame(maxWidth: 720)
        .frame(maxWidth: .infinity)
        .onAppear {
            done = t?.done ?? ""
            check = t?.check_command ?? ""
            attempts = t?.loop?.max_attempts ?? 5
            minutes = t?.loop?.max_minutes ?? 60
        }
    }

    static func label(_ s: String?) -> String {
        switch s {
        case "looping": "Looping"
        case "done": "Done, the check passed"
        case "gave-up": "Gave up"
        case "stopped": "Stopped"
        case "error": "Stopped by an error"
        default: "Off"
        }
    }
}

// MARK: Ship

struct ShipSheet: View {
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let model: TaskModel
    @State private var squash = true
    @State private var message = ""
    @State private var cleanup = true
    @State private var working = false
    @State private var error: String?

    var body: some View {
        let t = model.task
        let g = model.git
        let base = t?.base_branch ?? "main"
        VStack(alignment: .leading, spacing: Space.md) {
            Text("Merge into \(base)").font(.headline)
            HStack(spacing: 4) {
                Text("\(t?.branch ?? "") · \(g?.ahead ?? 0) commits · \(g?.files ?? 0) files ·")
                Text("+\(g?.added ?? 0)").foregroundStyle(theme.colors.good)
                Text("−\(g?.removed ?? 0)").foregroundStyle(theme.colors.bad)
            }
            .font(.system(size: TypeScale.xs, design: .monospaced))
            .foregroundStyle(theme.colors.muted)
            if let c = g?.canMerge, !c.ok {
                Text(c.reason ?? "This task can't be merged yet.").foregroundStyle(theme.colors.bad).font(.system(size: TypeScale.sm))
            } else {
                Text([t?.check_status == "passed" ? "Check passed" : nil, "No conflicts with \(base)"].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: TypeScale.sm))
                    .foregroundStyle(theme.colors.good)
            }
            Picker("", selection: $squash) {
                Text("Squash into one commit").tag(true)
                Text("Keep the \(g?.ahead ?? 0) commits").tag(false)
            }
            .pickerStyle(.radioGroup)
            .labelsHidden()
            if squash {
                TextField("Commit message", text: $message)
                    .textFieldStyle(.roundedBorder)
            }
            Toggle("Close the task and remove its worktree", isOn: $cleanup)
            if let error { Text(error).foregroundStyle(theme.colors.bad).font(.system(size: TypeScale.sm)).textSelection(.enabled) }
            HStack {
                Button("Open Pull Request Instead…") { pullRequest() }.disabled(working)
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Merge") { merge() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(working || g?.canMerge?.ok == false)
            }
        }
        .padding(Space.lg)
        .frame(width: 520)
        .onAppear { message = t?.title ?? "" }
    }

    private func merge() {
        working = true
        Task {
            do {
                var body: [String: Any] = ["strategy": squash ? "squash" : "merge", "cleanup": cleanup]
                if squash { body["message"] = message }
                try await model.store.api.taskAction(model.taskId, "merge", body: body)
                await model.load()
                dismiss()
            } catch { self.error = error.localizedDescription }
            working = false
        }
    }

    private func pullRequest() {
        working = true
        Task {
            do {
                let d = try await model.store.api.call("/api/tasks/\(BenchAPI.enc(model.taskId))/pr", method: "POST", body: ["title": model.task?.title ?? ""])
                if let u = (try? JSONDecoder().decode([String: String].self, from: d))?["url"], let url = URL(string: u) { openURL(url) }
                dismiss()
            } catch { self.error = error.localizedDescription }
            working = false
        }
    }
}
