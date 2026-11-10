import SwiftUI

/// A project: its checkout's git state, a box to start a task, and the work in flight -
/// tasks and checkout sessions in one list, the ones that need you first.
struct ProjectView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    let store: BenchStore
    let projectId: String
    @State private var git: GitState?
    @State private var busy: String?
    @State private var error: String?
    @State private var committing = false
    @State private var branching = false
    @State private var showDone = false
    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmRemove = false

    private var project: Project? { store.project(projectId) }
    private func newest(_ a: BenchTask, _ b: BenchTask) -> Bool { (a.updated_at ?? "") > (b.updated_at ?? "") }

    var body: some View {
        VStack(spacing: 0) {
            header
            if app.showTerminal {
                VSplitView {
                    content.frame(minHeight: 260)
                    RemoteTerminalView(store: store, query: "project=\(BenchAPI.enc(projectId))", place: project?.path ?? "Checkout", onClose: { app.showTerminal = false })
                        .frame(minHeight: 140, idealHeight: 250)
                }
            } else {
                content
            }
        }
        .background(theme.colors.bg)
        .navigationTitle(project?.name ?? "Project")
        .task(id: projectId) { await load() }
        .sheet(isPresented: $committing) {
            CommitSheet(store: store, projectId: projectId, git: git) { await load() }.environment(\.theme, theme)
        }
        .alert("Rename project", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Rename") { act("Renaming…") { _ = try await store.api.call("/api/projects/\(BenchAPI.enc(projectId))", method: "PATCH", body: ["name": newName]); await store.refresh() } }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Remove \(project?.name ?? "this project") from Bench?", isPresented: $confirmRemove) {
            Button("Remove", role: .destructive) {
                act("Removing…") {
                    _ = try await store.api.call("/api/projects/\(BenchAPI.enc(projectId))", method: "DELETE")
                    await store.refresh()
                    app.selection = nil
                }
            }
        } message: { Text("The folder stays on the server. Only Bench forgets it.") }
        .alert("Something went wrong", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK") { error = nil }
        } message: { Text(error ?? "") }
    }

    private var content: some View {
        let all = store.tasks.filter { $0.project_id == projectId }
        let active = all.filter { $0.state == "active" }
        let done = all.filter { $0.state != "active" }.sorted(by: newest)
        let loose = store.sessions.filter { $0.project_id == projectId && $0.task_id == nil && $0.archived_at == nil }
        // Tasks and checkout sessions together, the work that needs you first.
        var flight: [Flight] = active.map { t in
            let m = Mark.of(task: t, store: store)
            return Flight(id: "task:\(t.id)", mark: m, title: t.title, tag: "from \(t.base_branch ?? "main")",
                          status: m == .idle ? nil : Describe.taskStatus(t, store: store), updated: t.updated_at ?? "")
        }
        flight += loose.map { s in
            let m = Mark.of(s, store: store)
            return Flight(id: s.id, mark: m, title: s.title, tag: "checkout",
                          status: m == .idle ? nil : Describe.session(s, store: store), updated: s.updated_at ?? "")
        }
        flight.sort { ($0.rank, $1.updated) < ($1.rank, $0.updated) }
        return ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                gitStrip
                StartTask(store: store, projectId: projectId, git: git)
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("In flight").font(.system(size: 15, weight: .semibold))
                        if !flight.isEmpty { Text("\(flight.count)").font(.system(size: 12)).foregroundStyle(theme.colors.muted) }
                    }
                    VStack(spacing: 0) {
                        if flight.isEmpty {
                            Text("Nothing in flight. Start a task above, or work in the checkout.")
                                .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                                .frame(maxWidth: .infinity).padding(.vertical, 26)
                        }
                        ForEach(Array(flight.enumerated()), id: \.element.id) { i, f in
                            if i > 0 { Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1) }
                            flightRow(f)
                        }
                    }
                    .background(theme.colors.fill(0.035), in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(theme.colors.fill(0.07)))
                }
                if !done.isEmpty {
                    Button { withAnimation(.easeOut(duration: 0.15)) { showDone.toggle() } } label: {
                        HStack(spacing: Space.sm) {
                            Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold)).rotationEffect(.degrees(showDone ? 90 : 0))
                            Text("Merged and closed · \(done.count)")
                        }
                        .font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
                    }
                    .buttonStyle(.plain)
                    if showDone {
                        VStack(spacing: 0) {
                            ForEach(done) { t in
                                flightRow(Flight(id: "task:\(t.id)", mark: .idle, title: t.title, tag: t.state == "merged" ? "merged" : "closed", status: nil, updated: t.updated_at ?? ""), quiet: true)
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: 780)
            .padding(.horizontal, 32).padding(.vertical, 26)
            .frame(maxWidth: .infinity)
        }
    }

    private struct Flight {
        let id: String
        let mark: Mark
        let title: String
        let tag: String
        let status: String?
        let updated: String
        var rank: Int { mark == .needs ? 0 : mark == .running ? 1 : 2 }
    }

    private func flightRow(_ f: Flight, quiet: Bool = false) -> some View {
        Button { app.selection = f.id } label: {
            HStack(spacing: 12) {
                MarkView(mark: f.mark).frame(width: 12)
                Text(f.title).font(.system(size: 14)).foregroundStyle(quiet ? theme.colors.muted : theme.colors.text).lineLimit(1)
                Spacer(minLength: 8)
                Text(f.tag).font(BenchFont.mono(11.5)).foregroundStyle(theme.colors.muted).lineLimit(1)
                    .padding(.horizontal, 8).frame(height: 24)
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(theme.colors.fill(0.1)))
                if let s = f.status {
                    let tint = f.mark.tint(theme.colors)
                    Text(s).font(.system(size: 12)).foregroundStyle(tint).lineLimit(1)
                        .padding(.horizontal, 8).frame(height: 24)
                        .background(tint.opacity(0.13), in: RoundedRectangle(cornerRadius: 6))
                }
            }
            .padding(.horizontal, 14).frame(height: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// The checkout's git state and what can be done about it, in one row.
    private var gitStrip: some View {
        HStack(spacing: 14) {
            Button { branching = true } label: {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.triangle.branch").font(.system(size: 10.5))
                    Text(git?.branch ?? "…").font(BenchFont.mono(12.5))
                    Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
                }
                .padding(.horizontal, 10).frame(height: 30)
                .background(theme.colors.fill(0.07), in: RoundedRectangle(cornerRadius: 7))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .popover(isPresented: $branching, arrowEdge: .bottom) {
                BranchPopover(store: store, projectId: projectId, git: git) { await load() }.environment(\.theme, theme)
            }
            if let git {
                let n = git.dirty.count
                if !git.conflicted.isEmpty {
                    Text("\(git.conflicted.count) conflicted").foregroundStyle(theme.colors.bad)
                } else if n > 0 {
                    Button { committing = true } label: {
                        HStack(spacing: 5) { Circle().fill(theme.colors.warn).frame(width: 7, height: 7); Text("\(n) changed") }
                    }
                    .buttonStyle(.plain).foregroundStyle(theme.colors.warn).help("Review and commit")
                }
                if git.ahead > 0 { Text("↑\(git.ahead) ahead").foregroundStyle(theme.colors.muted) }
                if git.behind > 0 { Text("↓\(git.behind) behind").foregroundStyle(theme.colors.muted) }
                if n == 0 && git.ahead == 0 && git.behind == 0 && git.conflicted.isEmpty {
                    Text(git.hasRemote == false ? "✓ clean" : "✓ clean · up to date").foregroundStyle(theme.colors.muted)
                }
            } else {
                Text("Reading the repository…").foregroundStyle(theme.colors.muted)
            }
            Spacer(minLength: 8)
            if let busy { ProgressView().controlSize(.small); Text(busy).foregroundStyle(theme.colors.muted) }
            HStack(spacing: 6) {
                if git?.hasRemote != false {
                    stripButton((git?.behind ?? 0) > 0 ? "Pull ↓\(git?.behind ?? 0)" : "Pull") { act("Pulling…") { try await store.api.projectAction(projectId, "pull") } }
                    if (git?.ahead ?? 0) > 0 {
                        stripButton("Push ↑\(git?.ahead ?? 0)") { act("Pushing…") { try await store.api.projectAction(projectId, "push") } }
                    }
                }
                if (git?.dirty.count ?? 0) > 0 { stripButton("Commit…") { committing = true } }
            }
            .disabled(busy != nil)
        }
        .font(.system(size: 12.5))
        .lineLimit(1)
        .padding(.horizontal, 10).frame(height: 52)
        .background(theme.colors.fill(0.035), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(theme.colors.fill(0.07)))
    }

    private func stripButton(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 12, weight: .medium)).fixedSize()
                .padding(.horizontal, 12).frame(height: 30)
                .background(theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 7))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var header: some View {
        PaneHeader {
            HeaderTitle(title: project?.name ?? "",
                        sub: (project?.path ?? "").replacingOccurrences(of: #"^/(home|Users)/[^/]+"#, with: "~", options: .regularExpression),
                        mono: true)
        } trailing: {
            Button("Files") { app.openTab("files:\(projectId)") }.buttonStyle(.plain).font(.system(size: 12.5))
                .foregroundStyle(theme.colors.muted).padding(.horizontal, 8).help("Browse the project's files (⌃⇧F)")
            HeaderToggle(icon: "terminal", help: "Terminal (⇧⌘T)", on: Binding(get: { app.showTerminal }, set: { app.showTerminal = $0 }))
            Menu {
                Button("Fetch") { act("Fetching…") { try await store.api.projectAction(projectId, "fetch") } }
                Button("Rename…") { newName = project?.name ?? ""; renaming = true }
                Divider()
                Button("Remove from Bench…") { confirmRemove = true }
            } label: { Image(systemName: "ellipsis").font(.system(size: 13)).foregroundStyle(theme.colors.muted).frame(width: 30, height: 30) }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            .help("More")
        }
    }

    private func groupButton(_ title: String, count: Int?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(title)
                if let count, count > 0 { Text("\(count)").foregroundStyle(theme.colors.muted) }
            }
            .padding(.horizontal, 10).frame(height: 26).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func load() async {
        do { git = try await store.api.projectGit(projectId) } catch { self.error = error.localizedDescription }
    }

    private func act(_ label: String, _ op: @escaping () async throws -> Void) {
        busy = label
        Task {
            do { try await op() } catch { self.error = error.localizedDescription }
            busy = nil
            await load()
        }
    }
}

/// Switch branch: search, choose, or type a new name to create it.
private struct BranchPopover: View {
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    let store: BenchStore
    let projectId: String
    let git: GitState?
    let reload: () async -> Void
    @State private var query = ""
    @State private var error: String?
    @State private var working = false

    var body: some View {
        let branches = (git?.branches ?? []).filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
        let local = branches.filter { !$0.name.hasPrefix("origin/") && !$0.name.hasPrefix("remotes/") }
        let remote = branches.filter { $0.name.hasPrefix("origin/") || $0.name.hasPrefix("remotes/") }
        let exact = git?.branches?.contains { $0.name == query } ?? false
        VStack(alignment: .leading, spacing: 2) {
            TextField("", text: $query, prompt: Text("Find or create a branch"))
                .textFieldStyle(.plain)
                .font(.system(size: 13))
                .padding(.horizontal, 10).frame(height: 30)
                .background(theme.colors.fill(0.07), in: RoundedRectangle(cornerRadius: 8))
                .padding(.bottom, 4)
                .onSubmit { if !query.isEmpty && !exact { checkout(query, create: true) } }
            ScrollView {
                VStack(alignment: .leading, spacing: 1) {
                    if !query.isEmpty && !exact {
                        item("Create \u{201C}\(query)\u{201D}", current: false, mono: false) { checkout(query, create: true) }
                    }
                    if !local.isEmpty { label("Local") }
                    ForEach(local, id: \.name) { b in item(b.name, current: b.current == true) { checkout(b.name, create: false) } }
                    if !remote.isEmpty { label("Remote") }
                    ForEach(remote, id: \.name) { b in item(b.name, current: false) { checkout(b.name, create: false) } }
                }
            }
            .frame(maxHeight: 300)
            if let n = git?.dirty.count, n > 0 {
                Divider().padding(.vertical, 4)
                Text("\(n) uncommitted change\(n == 1 ? "" : "s") will be stashed and brought back when you return.")
                    .font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 6)
            }
            if let error { Text(error).font(.system(size: 12)).foregroundStyle(theme.colors.bad).padding(.horizontal, 6) }
        }
        .padding(8)
        .frame(width: 320)
        .disabled(working)
    }

    private func label(_ s: String) -> some View {
        Text(s).font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.colors.muted).padding(.horizontal, 8).padding(.top, 6).padding(.bottom, 2)
    }

    private func item(_ name: String, current: Bool, mono: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark").font(.system(size: 10, weight: .semibold)).opacity(current ? 1 : 0).frame(width: 14)
                Text(name).font(.system(size: 12.5, design: mono ? .monospaced : .default)).lineLimit(1)
                Spacer()
            }
            .padding(.horizontal, 8).frame(height: 28).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func checkout(_ branch: String, create: Bool) {
        working = true
        Task {
            do {
                var body: [String: Any] = ["branch": branch]
                if create { body["create"] = true }
                if !(git?.dirty.isEmpty ?? true) { body["stash"] = true }
                _ = try await store.api.call("/api/projects/\(BenchAPI.enc(projectId))/checkout", method: "POST", body: body)
                await reload()
                dismiss()
            } catch { self.error = error.localizedDescription }
            working = false
        }
    }
}

/// Commit: what's changed in the checkout, a message, and commit (or commit and push).
private struct CommitSheet: View {
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    let store: BenchStore
    let projectId: String
    let git: GitState?
    let reload: () async -> Void
    @State private var message = ""
    @State private var working = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text("Commit").font(.system(size: 15, weight: .semibold))
                Spacer()
                Text("to \(git?.branch ?? "main")").font(BenchFont.mono(12)).foregroundStyle(theme.colors.muted)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array((git?.dirty ?? []).enumerated()), id: \.offset) { _, p in
                        Text(p).font(BenchFont.mono(12.5)).lineLimit(1).truncationMode(.head)
                            .padding(.horizontal, 12).frame(height: 28, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 4)
            }
            .frame(maxHeight: 180)
            .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: 12))
            Text("Message").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            GrowingEditor(text: $message, placeholder: "What changed", size: 13.5, minLines: 2, maxLines: 6)
                .padding(10)
                .background(theme.colors.fill(0.06), in: RoundedRectangle(cornerRadius: 10))
            if let error { Text(error).font(.system(size: 12)).foregroundStyle(theme.colors.bad).textSelection(.enabled) }
            HStack(spacing: Space.sm) {
                Spacer()
                Button("Cancel") { dismiss() }.buttonStyle(TonalButton()).keyboardShortcut(.cancelAction)
                Button("Commit and Push") { commit(push: true) }.buttonStyle(TonalButton()).disabled(!ready || git?.hasRemote == false)
                Button { commit(push: false) } label: { HStack(spacing: 6) { Text("Commit"); KeyHint("⌘↩") } }
                    .buttonStyle(PrimaryButton())
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(!ready)
            }
        }
        .padding(20)
        .frame(width: 560)
        .background(theme.colors.surface2)
    }

    private var ready: Bool { !working && !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private func commit(push: Bool) {
        working = true
        error = nil
        Task {
            do {
                _ = try await store.api.call("/api/projects/\(BenchAPI.enc(projectId))/commit", method: "POST", body: ["message": message])
                if push { try await store.api.projectAction(projectId, "push") }
                await reload()
                dismiss()
            } catch { self.error = error.localizedDescription }
            working = false
        }
    }
}

/// The page's main action, on the page: describe a task and start it. Folded it's one line;
/// it opens to base branch, mode and what done means, with Start task (⌘↵).
private struct StartTask: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    let store: BenchStore
    let projectId: String
    let git: GitState?
    @State private var text = ""
    @State private var done = ""
    @State private var base: String?
    @State private var mode: Mode = .default
    @State private var open = false
    @State private var busy = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        content
            .onAppear { if app.focusTaskBox > 0 { focused = true } }
            .onChange(of: app.focusTaskBox) { _, _ in focused = true }
            .onDisappear { app.focusTaskBox = 0 }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: open ? .top : .center, spacing: 12) {
                if !open { Image(systemName: "plus").font(.system(size: 13, weight: .semibold)).foregroundStyle(theme.colors.accent) }
                TextField(open ? "What should it do?" : "Describe a task… it gets its own branch and worktree", text: $text, axis: .vertical)
                    .textFieldStyle(.plain).font(.system(size: 14.5)).lineLimit(1...8)
                    .focused($focused)
                    .onChange(of: focused) { _, f in if f { withAnimation(.snappy(duration: 0.18)) { open = true } } }
                    .onKeyPress(.escape) { fold(); return .handled }
                if !open {
                    Button { checkout() } label: {
                        HStack(spacing: 4) { Text("or").foregroundStyle(theme.colors.muted); Text("work in the checkout").underline(); Image(systemName: "arrow.right").font(.system(size: 10)) }
                    }
                    .buttonStyle(.plain).font(.system(size: 12.5)).fixedSize()
                    .help("A session in the project's own checkout: no branch, no merge step")
                }
            }
            if open {
                HStack(spacing: 6) {
                    Text("Done means").foregroundStyle(theme.colors.muted)
                    TextField("optional: how Claude knows it's finished", text: $done).textFieldStyle(.plain)
                }
                .font(.system(size: 12.5))
                Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1)
                HStack(spacing: 8) {
                    Menu {
                        ForEach((git?.branches ?? []).map(\.name), id: \.self) { b in Button(b) { base = b } }
                    } label: { chip("from \(base ?? git?.branch ?? "main")") }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                    Menu {
                        ForEach(Mode.allCases) { m in Button(m.label) { mode = m } }
                    } label: { chip(mode.label) }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                    if let error { Text(error).font(.system(size: 12)).foregroundStyle(theme.colors.bad).lineLimit(1) }
                    Spacer(minLength: 8)
                    Button("Cancel") { fold() }.buttonStyle(TonalButton(height: 30))
                    Button { start() } label: {
                        HStack(spacing: 8) { Text(busy ? "Starting…" : "Start task"); Text("⌘↵").font(BenchFont.mono(10.5)).opacity(0.75) }
                    }
                    .buttonStyle(PrimaryButton(height: 30))
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || busy)
                }
            }
        }
        .padding(.horizontal, 18).padding(.vertical, open ? 16 : 0)
        .frame(minHeight: 56)
        .background(theme.colors.fill(0.035), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(open ? theme.colors.accent.opacity(0.45) : theme.colors.fill(0.07)))
        .contentShape(Rectangle())
        .onTapGesture { focused = true }
    }

    private func chip(_ s: String) -> some View {
        HStack(spacing: 5) { Text(s).lineLimit(1); Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold)) }
            .font(.system(size: 12, weight: .medium)).padding(.horizontal, 12).frame(height: 30)
            .background(theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 7))
    }

    private func fold() {
        focused = false
        withAnimation(.snappy(duration: 0.18)) { open = false }
    }

    private func start() {
        let prompt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { return }
        busy = true; error = nil
        Task {
            do {
                let title = String(prompt.split(separator: "\n").first ?? "").prefix(80)
                let s = try await store.api.createTask(project: projectId, title: String(title), prompt: prompt, mode: mode, base: base, done: done)
                store.apply(s)
                await store.refresh()
                text = ""; done = ""; fold()
                app.selection = s.task_id.map { "task:\($0)" } ?? s.id
            } catch { self.error = error.localizedDescription }
            busy = false
        }
    }

    private func checkout() {
        Task {
            do {
                let s = try await store.api.createSession(project: projectId, task: nil, mode: mode)
                store.apply(s)
                app.selection = s.id
            } catch { self.error = error.localizedDescription }
        }
    }
}
