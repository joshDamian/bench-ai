import SwiftUI

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
    var base: String { task?.base_branch ?? "main" }

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

/// A task, as the Mac v2 Task boards draw it. The toolbar switches between Overview,
/// Conversation, Changes and Log; the terminal opens underneath any of them.
struct TaskView: View {
    enum Pane: String, CaseIterable, Identifiable {
        case overview = "Overview", conversation = "Conversation", changes = "Changes", log = "Log"
        var id: String { rawValue }
    }

    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    let store: BenchStore
    let taskId: String
    @State private var model: TaskModel
    @State private var pane: Pane
    @State private var sessionId: String?
    @State private var shipping = false
    @State private var renaming = false
    @State private var newTitle = ""

    init(store: BenchStore, taskId: String, sessionId: String? = nil) {
        self.store = store
        self.taskId = taskId
        _model = State(initialValue: TaskModel(taskId: taskId, store: store))
        _pane = State(initialValue: sessionId == nil ? .overview : .conversation)
        _sessionId = State(initialValue: sessionId)
    }

    private var sessions: [Session] {
        // A merged task's sessions are archived with it; its history still shows.
        store.sessions.filter { $0.task_id == taskId }
            .sorted { ($0.created_at ?? "") < ($1.created_at ?? "") }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            if app.showTerminal {
                VSplitView {
                    content.frame(minHeight: 260)
                    RemoteTerminalView(store: store, query: "task=\(BenchAPI.enc(taskId))", place: model.task?.branch ?? "Worktree")
                        .frame(minHeight: 140, idealHeight: 250)
                }
            } else {
                content
            }
        }
        .background(theme.colors.bg)
        .navigationTitle(model.task?.title ?? "Task")
        .task(id: store.task(taskId)) { await model.load() }
        .onChange(of: app.shipRequest) { _, _ in if model.task?.state == "active" { shipping = true } }
        .sheet(isPresented: $shipping) { ShipSheet(model: model).environment(\.theme, theme) }
        .alert("Rename task", isPresented: $renaming) {
            TextField("Title", text: $newTitle)
            Button("Rename") { model.run("Renaming…") { try await store.api.editTask(taskId, ["title": newTitle]) } }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Something went wrong", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
    }

    @ViewBuilder private var content: some View {
        if let g = model.git, !g.conflicted.isEmpty {
            ConflictsView(model: model, files: g.conflicted, ask: askToResolve)
        } else {
            switch pane {
            case .overview: TaskOverview(model: model, sessions: sessions) { id in sessionId = id; pane = .conversation } newSession: { newSession() }
            case .conversation: conversation
            case .changes: ChangesView(files: model.diff)
            case .log: LogView(entries: model.log)
            }
        }
    }

    @ViewBuilder private var conversation: some View {
        let id = sessionId ?? sessions.last?.id
        VStack(spacing: 0) {
            if sessions.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(sessions) { s in
                            Button { sessionId = s.id } label: {
                                HStack(spacing: 4) { MarkView(mark: Mark.of(s, store: store)); Text(s.title).lineLimit(1) }
                                    .font(.system(size: 12))
                                    .padding(.trailing, 10).frame(height: 26)
                                    .background(s.id == id ? theme.colors.fill(0.12) : .clear, in: Capsule())
                                    .contentShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                        Button { newSession() } label: { Image(systemName: "plus").frame(width: 26, height: 26) }
                            .buttonStyle(.plain).foregroundStyle(theme.colors.muted).help("New Session in This Task")
                    }
                    .padding(.horizontal, 20).padding(.vertical, 6)
                }
            }
            if let id, store.session(id) != nil {
                SessionView(store: store, sessionId: id, embedded: true).id(id)
            } else {
                ContentUnavailableView("No session yet", systemImage: "bubble.left", description: Text("Start one to give Claude work in this task."))
            }
        }
    }

    private var header: some View {
        let t = model.task
        let g = model.git
        return PaneHeader {
            VStack(alignment: .leading, spacing: 1) {
                Text(t?.title ?? "Task").font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                HStack(spacing: 4) {
                    Text("\(t?.branch ?? "") → \(model.base)").font(.system(size: 11.5, design: .monospaced)).truncationMode(.middle)
                    if let g, !g.conflicted.isEmpty {
                        Text("· \(g.conflicted.count) conflict\(g.conflicted.count == 1 ? "" : "s")").foregroundStyle(theme.colors.bad).fixedSize()
                    } else if let c = t?.check_status {
                        Text("· " + (c == "passed" ? "check passed" : c == "failed" ? "check failed" : "checking…"))
                            .foregroundStyle(c == "passed" ? theme.colors.good : c == "failed" ? theme.colors.bad : theme.colors.muted)
                            .fixedSize()
                    }
                }
                .font(.system(size: 11.5))
                .foregroundStyle(theme.colors.muted)
                .lineLimit(1)
            }
            .layoutPriority(-1)
        } trailing: {
            if let b = model.busy {
                HStack(spacing: 6) { ProgressView().controlSize(.small); Text(b).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted) }
            }
            Segments(options: Pane.allCases.map { ($0, $0.rawValue) }, selection: $pane).fixedSize()
            Spacer().frame(width: Space.xs)
            HeaderToggle(icon: "terminal", help: "Terminal (⇧⌘T)", on: Binding(get: { app.showTerminal }, set: { app.showTerminal = $0 }))
            Menu {
                Button("Rename…") { newTitle = t?.title ?? ""; renaming = true }
                Button("New Session in This Task") { newSession() }
                Divider()
                if (g?.behind ?? 0) > 0 {
                    Button("Update from \(model.base)") { model.run("Updating…") { try await store.api.taskAction(taskId, "update") } }
                }
                Button("Push Branch") { model.run("Pushing…") { try await store.api.taskAction(taskId, "push") } }
                Divider()
                if t?.state == "active" {
                    Button("Close Task") { model.run("Closing…") { try await store.api.taskAction(taskId, "close") } }
                } else {
                    Button("Reopen Task") { model.run("Reopening…") { try await store.api.taskAction(taskId, "reopen") } }
                }
            } label: { Image(systemName: "ellipsis").font(.system(size: 13)).foregroundStyle(theme.colors.muted).frame(width: 30, height: 30) }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            .help("More")
            if t?.state == "active", g?.conflicted.isEmpty ?? true {
                Button { shipping = true } label: { Text("Merge into \(model.base)").lineLimit(1) }
                    .buttonStyle(PrimaryButton(height: 28))
                    .fixedSize()
                    .disabled(model.busy != nil)
                    .help("Merge or ship (⇧⌘M)")
            }
        }
    }

    private func newSession() {
        guard let t = model.task else { return }
        model.run("Starting…") {
            let s = try await store.api.createSession(project: t.project_id, task: t.id, mode: Mode(rawValue: store.defaults.mode ?? "") ?? .acceptEdits)
            store.apply(s)
            sessionId = s.id
            pane = .conversation
        }
    }

    private func askToResolve(_ files: [String]) {
        guard let s = sessions.last else { return newSession() }
        model.run("Asking Claude…") {
            try await store.api.send(s.id, text: "Merging \(model.base) into this branch left conflicts in \(files.joined(separator: ", ")). Resolve them, keeping what both sides meant, then commit the merge.")
            sessionId = s.id
            pane = .conversation
        }
    }
}

// MARK: Overview

private struct TaskOverview: View {
    @Environment(\.theme) private var theme
    let model: TaskModel
    let sessions: [Session]
    let open: (String) -> Void
    let newSession: () -> Void
    @State private var done = ""
    @State private var check = ""
    @State private var attempts = 5
    @State private var minutes = 30
    @State private var editingDone = false

    var body: some View {
        let t = model.task
        let looping = t?.loop?.state == "looping"
        ScrollView {
            HStack(alignment: .top, spacing: 28) {
                VStack(alignment: .leading, spacing: 24) {
                    section("Done means") {
                        Group2 {
                            if editingDone {
                                TextField("", text: $done, prompt: Text("What does done look like?"), axis: .vertical)
                                    .textFieldStyle(.plain).font(.system(size: 13.5)).lineLimit(3...8)
                                    .padding(12)
                                GroupRow(title: "") {
                                    Button("Cancel") { editingDone = false; done = t?.done ?? "" }.buttonStyle(TonalButton(height: 24))
                                    Button("Save") { save(["done": done]); editingDone = false }.buttonStyle(TonalButton(height: 24))
                                }
                            } else {
                                Text(done.isEmpty ? "Not set. Say what finished looks like and Claude checks its work against it." : done)
                                    .font(.system(size: 13.5)).lineSpacing(3)
                                    .foregroundStyle(done.isEmpty ? theme.colors.muted : theme.colors.text)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(12)
                                GroupRow(title: "") { Button("Edit") { editingDone = true }.buttonStyle(TonalButton(height: 24)) }
                            }
                        }
                    }
                    section("Check") {
                        Group2 {
                            GroupRow(title: "Command", first: true, muted: true) {
                                TextField("", text: $check, prompt: Text("npm test"))
                                    .textFieldStyle(.plain).font(.system(size: 12.5, design: .monospaced))
                                    .multilineTextAlignment(.trailing).frame(maxWidth: 220)
                                    .onSubmit { save(["check": check]) }
                            }
                            GroupRow(title: "Last run", muted: true) {
                                HStack(spacing: 6) {
                                    MarkView(mark: t?.check_status == "passed" ? .ready : t?.check_status == "failed" ? .failed : t?.check_status == "running" ? .running : .idle)
                                    Text(lastRun(t)).foregroundStyle(theme.colors.muted)
                                }
                            }
                            GroupRow(title: "") {
                                Button("Run Check") {
                                    model.run("Checking…") {
                                        try await model.store.api.editTask(model.taskId, ["check": check])
                                        try await model.store.api.taskAction(model.taskId, "check")
                                    }
                                }
                                .buttonStyle(TonalButton(height: 24))
                                .disabled(check.trimmingCharacters(in: .whitespaces).isEmpty)
                            }
                        }
                        if let out = t?.check_output, !out.isEmpty, t?.check_status == "failed" {
                            Text(out.suffix(1200))
                                .font(.system(size: 11.5, design: .monospaced))
                                .foregroundStyle(theme.colors.wellText)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(10)
                                .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: 10))
                                .padding(.top, Space.sm)
                        }
                    }
                }
                .frame(maxWidth: .infinity)
                VStack(alignment: .leading, spacing: 24) {
                    section("Loop until done") {
                        Group2 {
                            GroupRow(title: "Keep going until the check passes", first: true) {
                                Toggle("", isOn: Binding(get: { looping }, set: { on in
                                    if on { save(["done": done, "check": check, "loop": ["max_attempts": attempts, "max_minutes": minutes]]) }
                                    else { save(["loop": NSNull()]) }
                                }))
                                .labelsHidden().toggleStyle(.switch).controlSize(.small)
                                .disabled(check.trimmingCharacters(in: .whitespaces).isEmpty || t?.state != "active")
                            }
                            GroupRow(title: "Attempts", muted: true) { PlusMinus(value: $attempts, range: 1...50, step: 1, unit: "") }
                            GroupRow(title: "Time limit", muted: true) { PlusMinus(value: $minutes, range: 5...600, step: 5, unit: "m") }
                            if let l = t?.loop {
                                GroupRow(title: "This run", muted: true) {
                                    Text("Attempt \(l.attempts ?? 0) of \(l.max_attempts ?? attempts) · \(LoopState.label(l.state))").foregroundStyle(theme.colors.muted)
                                }
                            }
                        }
                    }
                    section("Sessions") {
                        Group2 {
                            ForEach(Array(sessions.enumerated()), id: \.element.id) { i, s in
                                Button { open(s.id) } label: {
                                    GroupRow(title: s.title, first: i == 0) {
                                        Text([model.store.modelLine(s), s.cost.map(Format.cost)].compactMap { $0 }.joined(separator: " · "))
                                            .font(.system(size: 12)).foregroundStyle(theme.colors.muted).lineLimit(1)
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            }
                            GroupRow(title: "", first: sessions.isEmpty) {
                                Button("New Session in This Task", action: newSession).buttonStyle(.link)
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .frame(maxWidth: 1000)
            .padding(.horizontal, 32)
            .padding(.vertical, 20)
            .frame(maxWidth: .infinity)
        }
        .onAppear { reset() }
        .onChange(of: model.task?.updated_at) { _, _ in reset() }
    }

    private func reset() {
        let t = model.task
        if !editingDone { done = t?.done ?? "" }
        check = t?.check_command ?? check
        attempts = t?.loop?.max_attempts ?? attempts
        minutes = t?.loop?.max_minutes ?? minutes
    }

    private func save(_ body: [String: Any]) {
        model.run("Saving…") { try await model.store.api.editTask(model.taskId, body) }
    }

    private func lastRun(_ t: BenchTask?) -> String {
        switch t?.check_status {
        case "passed": "Passed"
        case "failed": "Failed"
        case "running": "Running…"
        default: "Not run yet"
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            GroupLabel(title)
            content()
        }
    }
}

enum LoopState {
    static func label(_ s: String?) -> String {
        switch s {
        case "looping": "looping"
        case "done": "passed"
        case "gave-up": "gave up"
        case "stopped": "stopped"
        case "error": "stopped by an error"
        default: "off"
        }
    }
}

// MARK: Conflicts

/// When updating from the base branch leaves conflicts, the task shows only that until
/// it's settled: which files, and the two ways out.
private struct ConflictsView: View {
    @Environment(\.theme) private var theme
    let model: TaskModel
    let files: [String]
    let ask: ([String]) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Merging \(model.base) left conflicts").font(.system(size: 17, weight: .semibold))
                    Text("Claude can resolve them in this task's branch, or you can abort the merge and leave the branch as it was.")
                        .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                }
                Group2 {
                    ForEach(Array(files.enumerated()), id: \.offset) { i, f in
                        GroupRow(title: (f as NSString).lastPathComponent, sub: (f as NSString).deletingLastPathComponent, first: i == 0) {
                            Circle().fill(theme.colors.bad).frame(width: 8, height: 8)
                        }
                    }
                }
                HStack(spacing: 10) {
                    Button("Abort Merge") { model.run("Aborting…") { try await model.store.api.taskAction(model.taskId, "abort-merge") } }
                        .buttonStyle(TonalButton())
                    Spacer()
                    Button("Ask Claude to Resolve") { ask(files) }.buttonStyle(PrimaryButton())
                }
                Text("Or open the terminal (⇧⌘T) and resolve them yourself.").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            }
            .frame(maxWidth: 640, alignment: .leading)
            .padding(32)
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: Changes

struct DiffFile: Identifiable, Hashable {
    var id: String { path }
    let path: String
    let lines: [String]
    /// M, A or D, as the file list shows it.
    var status = "M"
    var added: Int { lines.filter { $0.hasPrefix("+") && !$0.hasPrefix("+++") }.count }
    var removed: Int { lines.filter { $0.hasPrefix("-") && !$0.hasPrefix("---") }.count }

    /// Splits a unified diff into one entry per file, keeping hunks and their lines.
    static func parse(_ diff: String) -> [DiffFile] {
        var out: [DiffFile] = []
        var path: String?
        var lines: [String] = []
        var status = "M"
        func flush() { if let path { out.append(DiffFile(path: path, lines: lines, status: status)) }; lines = []; status = "M" }
        for line in diff.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            if line.hasPrefix("new file mode") { status = "A" } else if line.hasPrefix("deleted file mode") { status = "D" }
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

/// The changed files beside the selected file's diff, with line numbers.
struct ChangesView: View {
    @Environment(\.theme) private var theme
    let files: [DiffFile]
    @State private var selected: String?

    var body: some View {
        if files.isEmpty {
            ContentUnavailableView("No changes yet", systemImage: "doc.text", description: Text("What Claude changes in this task shows up here."))
        } else {
            HStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: Space.sm) {
                        Text("\(files.count) file\(files.count == 1 ? "" : "s")").foregroundStyle(theme.colors.muted)
                        Spacer()
                        Text("+\(files.reduce(0) { $0 + $1.added })").foregroundStyle(theme.colors.good)
                        Text("−\(files.reduce(0) { $0 + $1.removed })").foregroundStyle(theme.colors.bad)
                    }
                    .font(.system(size: 12))
                    .padding(.horizontal, 10).padding(.bottom, 8)
                    ScrollView {
                        VStack(spacing: 1) {
                            ForEach(files) { f in
                                Button { selected = f.path } label: { fileRow(f) }.buttonStyle(.plain)
                            }
                        }
                    }
                }
                .padding(.horizontal, 8).padding(.top, 12)
                .frame(width: 250)
                Rectangle().fill(theme.colors.fill(0.06)).frame(width: 1)
                ScrollView([.vertical, .horizontal]) {
                    if let f = files.first(where: { $0.path == current }) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(f.path).font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.horizontal, 16).padding(.vertical, 10)
                            ForEach(Array(numbered(f).enumerated()), id: \.offset) { _, l in DiffLine(text: l.text, number: l.number) }
                        }
                        .textSelection(.enabled)
                        .padding(.bottom, Space.lg)
                    }
                }
                .background(theme.colors.bg)
            }
        }
    }

    private var current: String? { selected ?? files.first?.path }

    private func fileRow(_ f: DiffFile) -> some View {
        let name = (f.path as NSString).lastPathComponent
        let dir = (f.path as NSString).deletingLastPathComponent
        return HStack(spacing: Space.sm) {
            Text(f.status).font(.system(size: 11, design: .monospaced))
                .foregroundStyle(f.status == "A" ? theme.colors.good : f.status == "D" ? theme.colors.bad : theme.colors.warn)
                .frame(width: 12)
            VStack(alignment: .leading, spacing: 0) {
                Text(name).font(.system(size: 12.5)).lineLimit(1)
                if !dir.isEmpty { Text(dir).font(.system(size: 11)).foregroundStyle(theme.colors.muted).lineLimit(1).truncationMode(.head) }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10)
        .frame(height: 40)
        .background(current == f.path ? theme.colors.fill(0.1) : .clear, in: RoundedRectangle(cornerRadius: 7))
        .contentShape(Rectangle())
    }

    /// New-side line numbers, counted from each hunk header.
    private func numbered(_ f: DiffFile) -> [(text: String, number: Int?)] {
        var n = 0
        var out: [(text: String, number: Int?)] = []
        for l in f.lines {
            if l.hasPrefix("@@") {
                if let r = l.range(of: #"\+(\d+)"#, options: .regularExpression) { n = Int(l[r].dropFirst()) ?? 0 }
                out.append((text: l, number: nil))
            } else if l.hasPrefix("-") {
                out.append((text: l, number: nil))
            } else {
                out.append((text: l, number: n))
                n += 1
            }
        }
        return out
    }
}

private struct DiffLine: View {
    @Environment(\.theme) private var theme
    let text: String
    let number: Int?
    var body: some View {
        let (fg, bg): (Color, Color) =
            text.hasPrefix("@@") ? (theme.colors.muted, .clear) :
            text.hasPrefix("+") ? (theme.colors.good, theme.colors.good.opacity(0.1)) :
            text.hasPrefix("-") ? (theme.colors.bad, theme.colors.bad.opacity(0.1)) :
            (theme.colors.text.opacity(0.85), .clear)
        HStack(spacing: 0) {
            Text(number.map(String.init) ?? "")
                .foregroundStyle(theme.colors.dotIdle)
                .frame(width: 44, alignment: .trailing)
                .padding(.trailing, 12)
            Text(text.isEmpty ? " " : text).foregroundStyle(fg).fixedSize()
        }
        .font(.system(size: 12, design: .monospaced))
        .frame(minHeight: 21)
        .padding(.trailing, Space.md)
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
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: theme.radius))
                    }
                }
                .frame(maxWidth: 860)
                .padding(Space.xl)
                .frame(maxWidth: .infinity)
            }
        }
    }
}

// MARK: Ship

/// Ship: merge on the server, open a pull request, or only push the branch.
struct ShipSheet: View {
    enum How: String, CaseIterable, Identifiable { case merge, pr, push; var id: String { rawValue } }

    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let model: TaskModel
    @State private var how: How = .merge
    @State private var squash = true
    @State private var message = ""
    @State private var cleanup = true
    @State private var prTitle = ""
    @State private var prBody = ""
    @State private var working = false
    @State private var error: String?

    var body: some View {
        let t = model.task
        let g = model.git
        VStack(alignment: .leading, spacing: 0) {
            Text("Ship").font(.system(size: 15, weight: .semibold))
            Text("\(t?.branch ?? "") · \(g?.ahead ?? 0) commit\(g?.ahead == 1 ? "" : "s") · \(t?.check_status == "passed" ? "check passed" : t?.check_status == "failed" ? "check failed" : "not checked")")
                .font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                .padding(.top, 2).padding(.bottom, 12)
            VStack(spacing: 2) {
                option(.merge, "Merge into \(model.base)", "Merges on the server and pushes \(model.base).") {
                    Picker("", selection: $squash) {
                        Text("Squash into one commit").tag(true)
                        Text("Keep the \(g?.ahead ?? 0) commits").tag(false)
                    }
                    .pickerStyle(.radioGroup).labelsHidden()
                    if squash { field($message, "Commit message", lines: 2) }
                    Toggle("Close the task and remove its worktree", isOn: $cleanup).font(.system(size: 12.5))
                    if let c = g?.canMerge, !c.ok {
                        Text(c.reason ?? "This task can't be merged yet.").font(.system(size: 12)).foregroundStyle(theme.colors.bad)
                    }
                }
                option(.pr, "Open a pull request", "Pushes the branch and opens a PR on GitHub.") {
                    field($prTitle, "Title", lines: 1)
                    field($prBody, "Description", lines: 4)
                }
                option(.push, "Push the branch", "Nothing else.") { EmptyView() }
            }
            if let error { Text(error).font(.system(size: 12)).foregroundStyle(theme.colors.bad).textSelection(.enabled).padding(.top, 10) }
            HStack(spacing: Space.sm) {
                Spacer()
                Button("Cancel") { dismiss() }.buttonStyle(TonalButton()).keyboardShortcut(.cancelAction)
                Button(how == .merge ? "Merge" : how == .pr ? "Open Pull Request" : "Push") { go() }
                    .buttonStyle(PrimaryButton())
                    .keyboardShortcut(.defaultAction)
                    .disabled(working || (how == .merge && g?.canMerge?.ok == false))
            }
            .padding(.top, 16)
        }
        .padding(20)
        .frame(width: 580)
        .background(theme.colors.surface2)
        .onAppear {
            message = t?.title ?? ""
            prTitle = t?.title ?? ""
            prBody = t?.done ?? ""
        }
    }

    private func option<C: View>(_ h: How, _ title: String, _ sub: String, @ViewBuilder _ more: () -> C) -> some View {
        let on = how == h
        return VStack(alignment: .leading, spacing: 10) {
            Button { how = h } label: {
                HStack(alignment: .top, spacing: 12) {
                    Circle().strokeBorder(on ? theme.colors.accent : theme.colors.fill(0.3), lineWidth: on ? 5 : 1.5).frame(width: 16, height: 16).padding(.top, 1)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.system(size: 13))
                        Text(sub).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if on { VStack(alignment: .leading, spacing: 8) { more() }.padding(.leading, 28) }
        }
        .padding(12)
        .background(on ? theme.colors.fill(0.05) : .clear, in: RoundedRectangle(cornerRadius: 12))
    }

    private func field(_ text: Binding<String>, _ prompt: String, lines: Int) -> some View {
        TextField("", text: text, prompt: Text(prompt), axis: .vertical)
            .textFieldStyle(.plain)
            .lineLimit(lines...max(lines, 6))
            .font(.system(size: 13))
            .padding(.horizontal, 10).padding(.vertical, 8)
            .background(theme.colors.fill(0.06), in: RoundedRectangle(cornerRadius: 9))
    }

    private func go() {
        working = true
        error = nil
        Task {
            do {
                switch how {
                case .merge:
                    var body: [String: Any] = ["strategy": squash ? "squash" : "merge", "cleanup": cleanup]
                    if squash { body["message"] = message }
                    try await model.store.api.taskAction(model.taskId, "merge", body: body)
                case .pr:
                    let d = try await model.store.api.call("/api/tasks/\(BenchAPI.enc(model.taskId))/pr", method: "POST", body: ["title": prTitle, "body": prBody])
                    if let u = (try? JSONDecoder().decode(JSONValue.self, from: d))?["url"].string, let url = URL(string: u) { openURL(url) }
                case .push:
                    try await model.store.api.taskAction(model.taskId, "push")
                }
                await model.load()
                dismiss()
            } catch { self.error = error.localizedDescription }
            working = false
        }
    }
}

/// The board's − value + control.
struct PlusMinus: View {
    @Environment(\.theme) private var theme
    @Binding var value: Int
    let range: ClosedRange<Int>
    let step: Int
    let unit: String

    var body: some View {
        HStack(spacing: 10) {
            button("minus") { value = max(range.lowerBound, value - step) }
            Text("\(value)\(unit)").font(.system(size: 13)).monospacedDigit().frame(minWidth: 36)
            button("plus") { value = min(range.upperBound, value + step) }
        }
    }

    private func button(_ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 10, weight: .semibold))
                .frame(width: 24, height: 24)
                .background(theme.colors.fill(0.1), in: RoundedRectangle(cornerRadius: 6))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
