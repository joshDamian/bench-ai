import SwiftUI

/// A project: its checkout's git state, its tasks, and the sessions working in the checkout.
struct ProjectView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    let store: BenchStore
    let projectId: String
    let open: (String) -> Void
    @State private var git: GitState?
    @State private var busy: String?
    @State private var error: String?

    private var project: Project? { store.project(projectId) }

    var body: some View {
        let tasks = store.tasks.filter { $0.project_id == projectId && $0.state == "active" }
            .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        let loose = store.sessions.filter { $0.project_id == projectId && $0.task_id == nil && $0.archived_at == nil }
            .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        ScrollView {
            VStack(alignment: .leading, spacing: Space.xl) {
                header
                Button { app.showNewSession = true } label: { Label("New Task", systemImage: "plus") }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut("n")
                section("Tasks") {
                    if tasks.isEmpty { empty("No open tasks. A task works on its own branch, so main stays clean.") }
                    ForEach(tasks) { t in
                        let s = store.sessions.filter { $0.task_id == t.id }.max { ($0.updated_at ?? "") < ($1.updated_at ?? "") }
                        row(title: t.title, line: taskLine(t, s), status: s?.status ?? "idle", when: t.updated_at) {
                            if let s { open(s.id) }
                        }
                    }
                }
                section("Sessions in the checkout") {
                    if loose.isEmpty { empty("None. These work directly on \(git?.branch ?? "the checked-out branch").") }
                    ForEach(loose) { s in
                        row(title: s.title, line: nil, status: s.status, when: s.updated_at) { open(s.id) }
                    }
                }
            }
            .frame(maxWidth: 860, alignment: .leading)
            .padding(Space.xl)
            .frame(maxWidth: .infinity)
        }
        .background(theme.colors.bg)
        .navigationTitle(project?.name ?? "Project")
        .task(id: projectId) { await load() }
        .alert("Something went wrong", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK") { error = nil }
        } message: { Text(error ?? "") }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            Text(project?.name ?? "").font(.system(size: TypeScale.xl, weight: .semibold))
            if let p = project?.path {
                Text(p).font(.system(size: TypeScale.xs, design: .monospaced)).foregroundStyle(theme.colors.muted).textSelection(.enabled)
            }
            HStack(spacing: Space.sm) {
                Label(git?.branch ?? "…", systemImage: "arrow.triangle.branch")
                    .font(.system(size: TypeScale.sm, design: .monospaced))
                if let g = git {
                    if !g.dirty.isEmpty { Text("\(g.dirty.count) changed").foregroundStyle(theme.colors.warn) }
                    if g.behind > 0 { Text("↓\(g.behind)").foregroundStyle(theme.colors.muted) }
                    if g.ahead > 0 { Text("↑\(g.ahead)").foregroundStyle(theme.colors.muted) }
                }
                Spacer()
                if let busy { ProgressView().controlSize(.small); Text(busy).foregroundStyle(theme.colors.muted) }
                if git?.hasRemote != false {
                    Button("Fetch") { act("Fetching…", "fetch") }
                    Button("Pull") { act("Pulling…", "pull") }.disabled((git?.behind ?? 0) == 0)
                    Button("Push") { act("Pushing…", "push") }.disabled((git?.ahead ?? 0) == 0)
                }
            }
            .font(.system(size: TypeScale.sm))
            .controlSize(.small)
            .disabled(busy != nil)
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(title).font(.system(size: TypeScale.xs, weight: .semibold)).foregroundStyle(theme.colors.muted)
            VStack(spacing: 0) { content() }
                .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: theme.radius))
        }
    }

    private func empty(_ text: String) -> some View {
        Text(text).font(.system(size: TypeScale.sm)).foregroundStyle(theme.colors.muted)
            .frame(maxWidth: .infinity, alignment: .leading).padding(Space.md)
    }

    private func row(title: String, line: String?, status: String, when: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Space.sm) {
                StatusDot(status: status)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).lineLimit(1)
                    if let line { Text(line).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted) }
                }
                Spacer()
                Text(Format.ago(when)).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted)
            }
            .padding(.horizontal, Space.md)
            .padding(.vertical, Space.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func taskLine(_ t: BenchTask, _ s: Session?) -> String {
        if store.approvals.contains(where: { a in s?.id == a.session_id }) { return "Needs you" }
        if s?.status == "running" { return "Working" }
        if t.loop?.state == "looping" { return "Looping" }
        switch t.check_status {
        case "passed": return "Ready to merge"
        case "failed": return "Check failed"
        default: return t.branch ?? ""
        }
    }

    private func load() async {
        do { git = try await store.api.projectGit(projectId) } catch { self.error = error.localizedDescription }
    }

    private func act(_ label: String, _ action: String) {
        busy = label
        Task {
            do { try await store.api.projectAction(projectId, action) } catch { self.error = error.localizedDescription }
            busy = nil
            await load()
        }
    }
}
