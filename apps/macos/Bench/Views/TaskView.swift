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
            // Archived sessions aren't in the live list; the conversation view reads from it.
            for s in detail?.sessions ?? [] where store.session(s.id) == nil { store.apply(s) }
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
        // Back to the pane and session it was left on; a task still open starts on its
        // conversation, where the work is, and a finished one on its overview.
        let kept = TaskView.kept[taskId]
        let open = (store.task(taskId)?.state ?? "active") == "active"
        _pane = State(initialValue: sessionId != nil ? .conversation : kept?.pane ?? (open ? .conversation : .overview))
        _sessionId = State(initialValue: sessionId ?? kept?.session)
    }

    /// Each task's pane and session as last left, for as long as the app runs.
    @MainActor static var kept: [String: (pane: Pane, session: String?)] = [:]

    private var sessions: [Session] {
        // A merged task's sessions are archived and may not be in the store's list; the
        // task's own detail carries them, so its history still shows.
        var seen = Set<String>()
        return (store.sessions.filter { $0.task_id == taskId } + (model.detail?.sessions ?? []))
            .filter { seen.insert($0.id).inserted }
            .sorted { ($0.created_at ?? "") < ($1.created_at ?? "") }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            if app.showTerminal {
                VSplitView {
                    content.frame(minHeight: 260)
                    RemoteTerminalView(store: store, query: "task=\(BenchAPI.enc(taskId))", place: model.task?.branch ?? "Worktree", onClose: { app.showTerminal = false })
                        .frame(minHeight: 140, idealHeight: 250)
                }
            } else {
                content.frame(maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(theme.colors.bg)
        .navigationTitle(model.task?.title ?? "Task")
        .task(id: store.task(taskId)) { await model.load() }
        .onChange(of: app.shipRequest) { _, _ in if model.task?.state == "active" { shipping = true } }
        .onChange(of: pane) { _, p in TaskView.kept[taskId] = (p, sessionId) }
        .onChange(of: sessionId) { _, s in TaskView.kept[taskId] = (pane, s) }
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
            case .overview:
                TaskOverview(model: model, sessions: sessions, open: { id in sessionId = id; pane = .conversation },
                             newSession: { newSession() }, showChanges: { pane = .changes }, ask: ask)
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
                    Text("\(t?.branch ?? "") → \(model.base)").font(BenchFont.mono(11.5)).truncationMode(.middle)
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

    /// Something for Claude to do in the task's latest session, which then shows.
    private func ask(_ text: String) {
        guard let s = sessions.last else { return newSession() }
        model.run("Asking Claude…") {
            try await store.api.send(s.id, text: text)
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

/// A task at a glance: where it is on the way to merging, what done means, what it has
/// changed, and its check - the thing that says whether it's done. Once merged, what it
/// shipped and where to go from there.
private struct TaskOverview: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    let model: TaskModel
    let sessions: [Session]
    let open: (String) -> Void
    let newSession: () -> Void
    let showChanges: () -> Void
    let ask: (String) -> Void
    @State private var done = ""
    @State private var check = ""
    @State private var attempts = 5
    @State private var minutes = 30
    @State private var editingDone = false
    @State private var editingCheck = false
    @State private var makeDefault = false
    @State private var suggestions: [(command: String, from: String)] = []
    @State private var writingOwn = false
    @State private var wide = true

    private var t: BenchTask? { model.task }
    private var merged: Bool { t?.state == "merged" || model.git?.merged == true }
    private var active: Bool { t?.state == "active" }

    var body: some View {
        ScrollView {
            // Two columns when there is room: the main one takes what is left after a side
            // column of fixed width. Narrower than that, the side cards go under the main ones.
            let layout = wide ? AnyLayout(HStackLayout(alignment: .top, spacing: 22)) : AnyLayout(VStackLayout(alignment: .leading, spacing: 22))
            layout {
                VStack(alignment: .leading, spacing: 22) {
                    if merged { shipped } else { progress }
                    if !merged || !(t?.done ?? "").isEmpty { doneMeans }
                    changes
                }
                .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 22) {
                    if !merged { checkCard }
                    sessionsCard
                    if merged { nextCard } else { loopCard }
                }
                .frame(width: wide ? 320 : nil)
                .frame(maxWidth: wide ? 320 : .infinity, alignment: .leading)
            }
            .onGeometryChange(for: Bool.self) { $0.size.width >= 820 } action: { wide = $0 }
            .frame(maxWidth: 1060)
            .padding(.horizontal, 32)
            .padding(.vertical, 26)
            .frame(maxWidth: .infinity)
        }
        .onAppear { reset() }
        .onChange(of: model.task?.updated_at) { _, _ in reset() }
        .task(id: model.taskId) {
            suggestions = (try? await model.store.api.checkSuggestions(model.taskId)) ?? []
        }
    }

    // MARK: cards

    private func card<C: View, X: View>(_ title: String, @ViewBuilder extra: () -> X = { EmptyView() }, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(theme.colors.muted)
                Spacer(minLength: 8)
                extra()
            }
            VStack(alignment: .leading, spacing: 0) { content() }
                .padding(.horizontal, 16).padding(.vertical, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(theme.colors.fill(0.035), in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(theme.colors.fill(0.07)))
        }
    }

    private enum Step { case done, now, todo, bad }

    /// Started → Working → Check → Merge, each with the detail that matters.
    private var progress: some View {
        let commits = model.git?.ahead ?? 0
        let status = t?.check_status
        let hasCheck = !(t?.check_command ?? "").isEmpty
        let checkStep: (Step, String) = !hasCheck ? (.todo, "none yet") : status == "passed" ? (.done, "passed" + ago(t?.check_at))
            : status == "failed" ? (.bad, "failed" + ago(t?.check_at)) : status == "running" ? (.now, "running…") : (.todo, "not run yet")
        let mergeOK = model.git?.canMerge?.ok ?? false
        let mergeStep: (Step, String) = (status == "failed" ? .todo : mergeOK ? .now : .todo, status == "failed" ? "waits for the check" : "into \(model.base)")
        let steps: [(String, Step, String)] = [
            ("Started", .done, "from \(model.base)" + ago(t?.created_at)),
            ("Working", commits > 0 ? .done : .now, "\(sessions.count) session\(sessions.count == 1 ? "" : "s") · \(commits) commit\(commits == 1 ? "" : "s")"),
            ("Check", checkStep.0, checkStep.1),
            ("Merge", mergeStep.0, mergeStep.1),
        ]
        return card("Where it is") {
            HStack(alignment: .top, spacing: 0) {
                ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 0) {
                            stepDot(s.1)
                            if i < steps.count - 1 {
                                Rectangle().fill(s.1 == .done ? theme.colors.good.opacity(0.6) : theme.colors.fill(0.1)).frame(height: 2).padding(.horizontal, 8)
                            }
                        }
                        Text(s.0).font(.system(size: 13, weight: .medium))
                        Text(s.2).font(.system(size: 11.5)).foregroundStyle(s.1 == .bad ? theme.colors.bad : theme.colors.muted)
                            .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    @ViewBuilder private func stepDot(_ s: Step) -> some View {
        let tint: Color = switch s { case .done: theme.colors.good; case .now: theme.colors.accent; case .bad: theme.colors.bad; case .todo: theme.colors.muted.opacity(0.5) }
        ZStack {
            Circle().strokeBorder(tint, lineWidth: 2).frame(width: 22, height: 22)
            switch s {
            case .done: Image(systemName: "checkmark").font(.system(size: 9, weight: .bold)).foregroundStyle(tint)
            case .bad: Text("!").font(.system(size: 11, weight: .bold)).foregroundStyle(tint)
            case .now: Circle().fill(tint).frame(width: 8, height: 8)
            case .todo: EmptyView()
            }
        }
    }

    private var shipped: some View {
        card("Shipped") {
            Text("Merged into \(model.base)" + (t?.merged_at.map { " " + when($0) } ?? "") + ".")
                .font(.system(size: 15))
            stats.padding(.top, 10)
        }
    }

    private var stats: some View {
        let g = model.git
        let files = g?.files ?? model.diff.count
        let added = g?.added ?? model.diff.reduce(0) { $0 + $1.added }
        let removed = g?.removed ?? model.diff.reduce(0) { $0 + $1.removed }
        let cost = sessions.compactMap(\.cost).reduce(0, +)
        return HStack(spacing: 16) {
            Text("\(files) file\(files == 1 ? "" : "s")")
            Text("+\(added)").foregroundStyle(theme.colors.good)
            Text("−\(removed)").foregroundStyle(theme.colors.bad)
            if let a = g?.ahead, a > 0 { Text("\(a) commit\(a == 1 ? "" : "s")") }
            if cost > 0 { Text("\(sessions.count) session\(sessions.count == 1 ? "" : "s") · \(Format.cost(cost))") }
        }
        .font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
    }

    private var doneMeans: some View {
        card("Done means", extra: {
            if !editingDone && !merged {
                Button("Edit") { editingDone = true }.buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            }
        }) {
            if editingDone {
                GrowingEditor(text: $done, placeholder: "What does done look like?", size: 14, minLines: 3, maxLines: 10)
                HStack {
                    Spacer()
                    Button("Cancel") { editingDone = false; done = t?.done ?? "" }.buttonStyle(TonalButton(height: 26))
                    Button("Save") { save(["done": done]); editingDone = false }.buttonStyle(PrimaryButton(height: 26))
                }
                .padding(.top, 10)
            } else {
                Text(done.isEmpty ? "Not set. Say what finished looks like and Claude checks its work against it." : done)
                    .font(.system(size: 14)).lineSpacing(4)
                    .foregroundStyle(done.isEmpty ? theme.colors.muted : theme.colors.text)
                    .textSelection(.enabled)
            }
        }
    }

    private var changes: some View {
        card("What changed", extra: {
            if !model.diff.isEmpty {
                Button("Review changes →", action: showChanges).buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(theme.colors.text)
            }
        }) {
            if model.diff.isEmpty {
                Text(merged ? "Nothing left to compare: it's in \(model.base)." : "Nothing changed yet.")
                    .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
            } else {
                if !merged { stats.padding(.bottom, 8) }
                ForEach(model.diff.prefix(10)) { f in
                    HStack(spacing: 10) {
                        Text(f.path).font(BenchFont.mono(12)).lineLimit(1).truncationMode(.head)
                        Spacer(minLength: 8)
                        Text("+\(f.added)").font(BenchFont.mono(12)).foregroundStyle(theme.colors.good)
                        Text("−\(f.removed)").font(BenchFont.mono(12)).foregroundStyle(theme.colors.bad).frame(minWidth: 34, alignment: .trailing)
                    }
                    .frame(height: 28)
                }
                if model.diff.count > 10 {
                    Text("and \(model.diff.count - 10) more").font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.top, 4)
                }
            }
        }
    }

    // MARK: the check

    @ViewBuilder private var checkCard: some View {
        let command = (t?.check_command ?? "").trimmingCharacters(in: .whitespaces)
        card("Check") {
            if editingCheck || (command.isEmpty && writingOwn) {
                checkEditor(was: command)
            } else if command.isEmpty {
                noCheck
            } else {
                checkResult(command)
            }
        }
    }

    private func checkResult(_ command: String) -> some View {
        let status = t?.check_status
        let tint: Color = status == "passed" ? theme.colors.good : status == "failed" ? theme.colors.bad : theme.colors.muted
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                if status == "running" { ProgressView().controlSize(.mini) } else { Circle().fill(tint).frame(width: 8, height: 8) }
                Text(lastRun(status)).font(.system(size: 13, weight: .semibold))
                if let s = failSummary, status == "failed" { Text(s).font(.system(size: 12.5)).foregroundStyle(theme.colors.muted).lineLimit(1) }
                else if let at = t?.check_at, status != nil, status != "running" { Text(when(at)).font(.system(size: 12.5)).foregroundStyle(theme.colors.muted) }
                Spacer(minLength: 8)
                Button(status == nil ? "Run" : "Run Again") { runCheck() }.buttonStyle(TonalButton(height: 26)).disabled(status == "running" || !active)
            }
            HStack(spacing: 8) {
                Text(command).font(BenchFont.mono(11.5)).foregroundStyle(theme.colors.muted).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                Spacer(minLength: 8)
                if active {
                    Button("Edit") { check = command; editingCheck = true }.buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(theme.colors.text)
                }
            }
            if let out = t?.check_output, !out.isEmpty, status == "failed" || status == "passed" {
                ScrollView {
                    Text(status == "passed" ? String(out.suffix(400)) : out)
                        .font(BenchFont.mono(11.5)).foregroundStyle(status == "failed" ? theme.colors.bad : theme.colors.good)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                }
                .defaultScrollAnchor(.bottom)
                .frame(maxHeight: status == "failed" ? 260 : 64)
                .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: 8))
            }
            if status == "failed" && active {
                HStack(spacing: 8) {
                    Button("Ask Claude to Fix It") {
                        ask("The check failed (`\(command)`). Here is the end of its output:\n\n```\n\(String((t?.check_output ?? "").suffix(3000)))\n```\n\nFix what's failing, then run the check again.")
                    }
                    .buttonStyle(PrimaryButton(height: 28))
                    Button("Copy Output") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(t?.check_output ?? "", forType: .string)
                    }
                    .buttonStyle(TonalButton(height: 28))
                }
                .padding(.top, 2)
            }
        }
    }

    /// "2 of 41 tests" and the like, when the runner printed a summary line.
    private var failSummary: String? {
        guard let out = t?.check_output else { return nil }
        let patterns = [#"Tests?:\s+(\d+) failed[^\n]*?(\d+) total"#, #"(\d+) failed,\s*(\d+) passed"#, #"(\d+) failing"#, #"Found (\d+) errors?"#, #"error TS\d+"#]
        if let m = out.range(of: patterns[0], options: .regularExpression) {
            let nums = out[m].matches(of: /\d+/).map { String($0.output) }
            if nums.count >= 2 { return "\(nums[0]) of \(nums[nums.count - 1]) tests" }
        }
        if let m = out.range(of: #"Found (\d+) errors?"#, options: .regularExpression) { return String(out[m]).replacingOccurrences(of: "Found ", with: "") }
        let tsErrors = out.matches(of: /error TS\d+/).count
        if tsErrors > 0 { return "\(tsErrors) type error\(tsErrors == 1 ? "" : "s")" }
        if let m = out.range(of: #"\d+ failing"#, options: .regularExpression) { return String(out[m]) }
        return nil
    }

    private var noCheck: some View {
        VStack(alignment: .leading, spacing: 10) {
            (Text("No check yet. ").fontWeight(.medium)
             + Text("Without one, Claude can't tell when the task is done, and “Keep going until done” can't run.").foregroundColor(theme.colors.muted))
                .font(.system(size: 13.5)).lineSpacing(3)
            if active {
                if !suggestions.isEmpty {
                    Text("From this project").font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.top, 2)
                    ForEach(suggestions, id: \.command) { s in
                        Button { saveCheck(s.command, run: true) } label: {
                            HStack(spacing: 10) {
                                Text(s.command).font(BenchFont.mono(12)).lineLimit(1)
                                Spacer(minLength: 8)
                                Text(s.from).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1)
                                Text("Use").font(.system(size: 12, weight: .medium)).foregroundStyle(theme.colors.accent)
                            }
                            .padding(.horizontal, 10).frame(height: 32)
                            .background(theme.colors.fill(0.06), in: RoundedRectangle(cornerRadius: 8))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                HStack {
                    Button("Write your own…") { check = ""; writingOwn = true }.buttonStyle(.plain).font(.system(size: 12.5))
                    Spacer()
                    Button("Ask Claude to suggest one") {
                        ask("This task has no check yet. Look at the project and suggest one shell command that tells whether this task is done (its tests, a type check, a build), run it once to show it works, and tell me the command. Don't set it yourself.")
                    }
                    .buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                }
                .padding(.top, 2)
            }
        }
    }

    private func checkEditor(was: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if !was.isEmpty, let s = t?.check_status {
                Text("\(lastRun(s)) with the old command").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            }
            TextField("npm test", text: $check)
                .textFieldStyle(.plain).font(BenchFont.mono(12.5))
                .padding(.horizontal, 10).frame(height: 34)
                .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.colors.accent.opacity(0.5)))
                .onSubmit { saveCheck(check, run: true) }
            HStack(spacing: 8) {
                Toggle("Make this the project's default", isOn: $makeDefault)
                    .toggleStyle(.checkbox).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                Spacer(minLength: 8)
                Button("Cancel") { editingCheck = false; writingOwn = false; check = was }.buttonStyle(TonalButton(height: 26))
                Button("Save and Run") { saveCheck(check, run: true) }
                    .buttonStyle(PrimaryButton(height: 26))
                    .disabled(check.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func saveCheck(_ command: String, run: Bool) {
        let c = command.trimmingCharacters(in: .whitespaces)
        guard !c.isEmpty else { return }
        editingCheck = false; writingOwn = false
        check = c
        let pid = t?.project_id
        let asDefault = makeDefault
        model.run(run ? "Checking…" : "Saving…") {
            try await model.store.api.editTask(model.taskId, ["check": c])
            if asDefault, let pid { try await model.store.api.setProjectCheck(pid, c) }
            if run { try await model.store.api.taskAction(model.taskId, "check") }
        }
    }

    private func runCheck() {
        model.run("Checking…") { try await model.store.api.taskAction(model.taskId, "check") }
    }

    // MARK: right column

    private var sessionsCard: some View {
        card("Sessions") {
            ForEach(Array(sessions.enumerated()), id: \.element.id) { i, s in
                if i > 0 { Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1) }
                Button { open(s.id) } label: {
                    HStack(spacing: 10) {
                        MarkView(mark: Mark.of(s, store: model.store)).frame(width: 10)
                        Text(s.title).font(.system(size: 13)).lineLimit(1)
                        Spacer(minLength: 8)
                        Text([model.store.modelLine(s), s.cost.map(Format.cost), Format.ago(s.updated_at)].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.system(size: 12)).foregroundStyle(theme.colors.muted).lineLimit(1)
                    }
                    .frame(height: 40).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if active {
                if !sessions.isEmpty { Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1) }
                HStack {
                    Spacer()
                    Button("＋ New session in this task", action: newSession).buttonStyle(.plain)
                        .font(.system(size: 12.5)).foregroundStyle(theme.colors.accent)
                }
                .padding(.top, 10)
            } else if sessions.isEmpty {
                Text("No sessions.").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
            }
        }
    }

    private var loopCard: some View {
        let looping = t?.loop?.state == "looping"
        let hasCheck = !(t?.check_command ?? "").isEmpty
        return card("Keep going until done") {
            HStack {
                Text(looping ? "On · Claude reruns until the check passes" : "Off · when on, Claude reruns until the check passes")
                    .font(.system(size: 13)).foregroundStyle(theme.colors.text.opacity(0.85))
                Spacer(minLength: 8)
                Toggle("", isOn: Binding(get: { looping }, set: { on in
                    if on { save(["done": done, "check": t?.check_command ?? check, "loop": ["max_attempts": attempts, "max_minutes": minutes]]) }
                    else { save(["loop": NSNull()]) }
                }))
                .labelsHidden().toggleStyle(.switch).controlSize(.small)
                .disabled(!hasCheck || !active)
                .help(hasCheck ? "" : "Needs a check first")
            }
            HStack(spacing: 14) {
                HStack(spacing: 6) { Text("Attempts").foregroundStyle(theme.colors.muted); PlusMinus(value: $attempts, range: 1...50, step: 1, unit: "") }
                HStack(spacing: 6) { Text("Time limit").foregroundStyle(theme.colors.muted); PlusMinus(value: $minutes, range: 5...600, step: 5, unit: "m") }
            }
            .font(.system(size: 12)).padding(.top, 10)
            if let l = t?.loop {
                Text("Attempt \(l.attempts ?? 0) of \(l.max_attempts ?? attempts) · \(LoopState.label(l.state))")
                    .font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.top, 6)
            }
        }
    }

    private var nextCard: some View {
        card("Next") {
            Button {
                guard let pid = t?.project_id else { return }
                app.taskDraft = "Follow-up to “\(t?.title ?? "the last task")”: "
                app.startTask(in: pid)
            } label: {
                HStack { Image(systemName: "plus").font(.system(size: 11)); Text("Follow-up task from here"); Spacer() }
                    .font(.system(size: 13)).padding(.horizontal, 12).frame(height: 34)
                    .background(theme.colors.fill(0.07), in: RoundedRectangle(cornerRadius: 8))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(t?.project_id == nil)
        }
    }

    // MARK: helpers

    private func ago(_ s: String?) -> String {
        guard let s, !s.isEmpty else { return "" }
        let a = when(s)
        return a.isEmpty ? "" : " · \(a)"
    }

    /// "4m ago", "just now".
    private func when(_ s: String?) -> String {
        let a = Format.ago(s)
        return a.isEmpty ? "" : a == "now" ? "just now" : "\(a) ago"
    }

    private func reset() {
        if !editingDone { done = t?.done ?? "" }
        if !editingCheck { check = t?.check_command ?? check }
        attempts = t?.loop?.max_attempts ?? attempts
        minutes = t?.loop?.max_minutes ?? minutes
    }

    private func save(_ body: [String: Any]) {
        model.run("Saving…") { try await model.store.api.editTask(model.taskId, body) }
    }

    private func lastRun(_ s: String?) -> String {
        switch s {
        case "passed": "Passed"
        case "failed": "Failed"
        case "running": "Running…"
        default: "Not run yet"
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
            Text(f.status).font(BenchFont.mono(11))
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
        .font(BenchFont.mono(12))
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
        GrowingEditor(text: text, placeholder: prompt, size: 13, minLines: lines, maxLines: max(lines, 6))
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
