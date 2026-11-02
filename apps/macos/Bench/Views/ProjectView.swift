import SwiftUI

/// A project, as the Mac v2 Project board draws it: its tasks as a table, what's in the
/// checkout, and the checkout's git controls grouped in the toolbar.
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
        Group {
            if app.showTerminal {
                VSplitView {
                    content.frame(minHeight: 260)
                    RemoteTerminalView(store: store, query: "project=\(BenchAPI.enc(projectId))", place: project?.path ?? "Checkout")
                        .frame(minHeight: 140, idealHeight: 250)
                }
            } else {
                content
            }
        }
        .background(theme.colors.bg)
        .navigationTitle(project?.name ?? "Project")
        .hideToolbarTitle()
        .toolbar { toolbar }
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
        let active = all.filter { $0.state == "active" }.sorted(by: newest)
        let done = all.filter { $0.state != "active" }.sorted(by: newest)
        let loose = store.sessions.filter { $0.project_id == projectId && $0.task_id == nil && $0.archived_at == nil }
            .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        return ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if active.isEmpty && loose.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("No tasks yet").font(.system(size: 15, weight: .semibold))
                        Text("A task runs on its own branch, so you can review and merge it when it's done.")
                            .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                    }
                    .padding(.top, Space.lg)
                } else {
                    table(active)
                }
                if !loose.isEmpty || !(git?.dirty.isEmpty ?? true) {
                    VStack(alignment: .leading, spacing: 4) {
                        GroupLabel("In the checkout")
                        ForEach(loose) { s in
                            row(mark: Mark.of(s, store: store), title: s.title, branch: git?.branch ?? "", status: Describe.session(s, store: store),
                                tint: Mark.of(s, store: store).tint(theme.colors), when: Format.ago(s.updated_at)) { app.selection = s.id }
                        }
                        if let n = git?.dirty.count, n > 0 {
                            HStack(spacing: 0) {
                                RoundedRectangle(cornerRadius: 2).fill(theme.colors.warn).frame(width: 8, height: 8).frame(width: 28)
                                Text("\(n) uncommitted change\(n == 1 ? "" : "s")").font(.system(size: 13))
                                Spacer()
                                Button("Review") { committing = true }.buttonStyle(.link).font(.system(size: 12))
                            }
                            .padding(.horizontal, 12)
                            .frame(height: 38)
                        }
                    }
                }
                if !done.isEmpty {
                    Button { withAnimation(.easeOut(duration: 0.15)) { showDone.toggle() } } label: {
                        HStack(spacing: Space.sm) {
                            Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold)).rotationEffect(.degrees(showDone ? 90 : 0))
                            Text("Merged and closed · \(done.count)")
                        }
                        .font(.system(size: 12.5))
                        .foregroundStyle(theme.colors.muted)
                        .padding(.leading, 12)
                    }
                    .buttonStyle(.plain)
                    if showDone { table(done, header: false) }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, Space.md)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func table(_ tasks: [BenchTask], header: Bool = true) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if header {
                HStack(spacing: 0) {
                    Spacer().frame(width: 28)
                    Text("Task").frame(maxWidth: .infinity, alignment: .leading)
                    Text("Branch").frame(width: 210, alignment: .leading)
                    Text("Status").frame(width: 150, alignment: .leading)
                    Text("Updated").frame(width: 70, alignment: .trailing)
                }
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(theme.colors.muted)
                .padding(.horizontal, 12)
                .frame(height: 28)
            }
            ForEach(tasks) { t in
                let m = Mark.of(task: t, store: store)
                row(mark: m, title: t.title, branch: t.branch ?? "", status: Describe.taskStatus(t, store: store),
                    tint: m == .idle ? theme.colors.muted : m.tint(theme.colors), when: Format.ago(t.updated_at)) { app.selection = "task:\(t.id)" }
            }
        }
    }

    private func row(mark: Mark, title: String, branch: String, status: String, tint: Color, when: String, open: @escaping () -> Void) -> some View {
        Button(action: open) {
            HStack(spacing: 0) {
                MarkView(mark: mark).frame(width: 28, alignment: .leading)
                Text(title).font(.system(size: 13)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading).padding(.trailing, 12)
                Text(branch).font(.system(size: 11.5, design: .monospaced)).foregroundStyle(theme.colors.muted).lineLimit(1).truncationMode(.middle)
                    .frame(width: 210, alignment: .leading)
                Text(status).font(.system(size: 12)).foregroundStyle(tint).lineLimit(1).frame(width: 150, alignment: .leading)
                Text(when).font(.system(size: 12)).foregroundStyle(theme.colors.muted).frame(width: 70, alignment: .trailing)
            }
            .padding(.horizontal, 12)
            .frame(height: 38)
            .background(mark == .running ? theme.colors.fill(0.04) : .clear, in: RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            VStack(alignment: .leading, spacing: 1) {
                Text(project?.name ?? "").font(.system(size: 13.5, weight: .semibold))
                if let p = project?.path {
                    Text(p.replacingOccurrences(of: #"^/(home|Users)/[^/]+"#, with: "~", options: .regularExpression))
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(theme.colors.muted)
                }
            }
            .padding(.leading, Space.xs)
        }
        ToolbarItemGroup(placement: .primaryAction) {
            if let busy { HStack(spacing: 6) { ProgressView().controlSize(.small); Text(busy).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted) } }
            HStack(spacing: 0) {
                Button { branching = true } label: {
                    HStack(spacing: 5) {
                        Text(git?.branch ?? "…").font(.system(size: 12, design: .monospaced))
                        Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
                    }
                    .padding(.horizontal, 10).frame(height: 26).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .popover(isPresented: $branching, arrowEdge: .bottom) {
                    BranchPopover(store: store, projectId: projectId, git: git) { await load() }.environment(\.theme, theme)
                }
                groupButton("Pull", count: git?.behind) { act("Pulling…") { try await store.api.projectAction(projectId, "pull") } }
                    .disabled(git?.hasRemote == false)
                groupButton("Push", count: git?.ahead) { act("Pushing…") { try await store.api.projectAction(projectId, "push") } }
                    .disabled((git?.ahead ?? 0) == 0)
                groupButton("Commit…", count: git?.dirty.count) { committing = true }
                    .disabled((git?.dirty.count ?? 0) == 0)
            }
            .font(.system(size: 12.5))
            .padding(.horizontal, 2)
            .background(theme.colors.fill(0.08), in: Capsule())
            .disabled(busy != nil)
            Toggle(isOn: Binding(get: { app.showTerminal }, set: { app.showTerminal = $0 })) { Label("Terminal", systemImage: "terminal") }
                .help("Terminal (⇧⌘T)")
            Menu {
                Button("Fetch") { act("Fetching…") { try await store.api.projectAction(projectId, "fetch") } }
                Button("Rename…") { newName = project?.name ?? ""; renaming = true }
                Divider()
                Button("Remove from Bench…") { confirmRemove = true }
            } label: { Label("More", systemImage: "ellipsis.circle") }
            Button("New Task") { app.newSession(in: projectId) }
                .buttonStyle(PrimaryButton(height: 28))
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
                Text("to \(git?.branch ?? "main")").font(.system(size: 12, design: .monospaced)).foregroundStyle(theme.colors.muted)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array((git?.dirty ?? []).enumerated()), id: \.offset) { _, p in
                        Text(p).font(.system(size: 12.5, design: .monospaced)).lineLimit(1).truncationMode(.head)
                            .padding(.horizontal, 12).frame(height: 28, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 4)
            }
            .frame(maxHeight: 180)
            .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: 12))
            Text("Message").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            TextField("", text: $message, axis: .vertical)
                .textFieldStyle(.plain).lineLimit(2...6).font(.system(size: 13.5))
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
