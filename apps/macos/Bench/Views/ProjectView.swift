import SwiftUI

/// A project, as the Project board draws it: the checkout's git controls, a new-task box,
/// open tasks, sessions in the checkout, and the merged and closed tasks folded away.
struct ProjectView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let projectId: String
    let open: (String) -> Void
    @State private var git: GitState?
    @State private var busy: String?
    @State private var error: String?
    @State private var prompt = ""
    @State private var starting = false
    @State private var committing = false
    @State private var commitMessage = ""
    @State private var showDone = false

    private var project: Project? { store.project(projectId) }
    private func newest(_ a: BenchTask, _ b: BenchTask) -> Bool { (a.updated_at ?? "") > (b.updated_at ?? "") }

    var body: some View {
        let all = store.tasks.filter { $0.project_id == projectId }
        let active = all.filter { $0.state == "active" }.sorted(by: newest)
        let done = all.filter { $0.state != "active" }.sorted(by: newest)
        let loose = store.sessions.filter { $0.project_id == projectId && $0.task_id == nil && $0.archived_at == nil }
            .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    newTask
                    if !active.isEmpty {
                        section("Tasks") { ForEach(active) { taskRow($0) } }
                    }
                    if !loose.isEmpty {
                        section("Sessions in the checkout") {
                            ForEach(loose) { s in
                                row(dot: s.status, title: s.title, trail: Text(Format.ago(s.updated_at)).foregroundStyle(theme.colors.muted)) { open(s.id) }
                            }
                        }
                    }
                    if !done.isEmpty {
                        Button { withAnimation(.easeOut(duration: 0.15)) { showDone.toggle() } } label: {
                            HStack(spacing: Space.sm) {
                                Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold)).rotationEffect(.degrees(showDone ? 90 : 0))
                                Text("Merged and closed · \(done.count)")
                            }
                            .font(.system(size: TypeScale.sm))
                            .foregroundStyle(theme.colors.muted)
                        }
                        .buttonStyle(.plain)
                        if showDone {
                            VStack(spacing: 6) { ForEach(done) { taskRow($0) } }
                        }
                    }
                }
                .frame(maxWidth: 760, alignment: .leading)
                .padding(.horizontal, 32)
                .padding(.top, Space.sm)
                .padding(.bottom, Space.xl)
                .frame(maxWidth: .infinity)
            }
        }
        .background(theme.colors.bg)
        .navigationTitle(project?.name ?? "Project")
        .hideToolbarTitle()
        .task(id: projectId) { await load() }
        .sheet(isPresented: $committing) { commitSheet }
        .alert("Something went wrong", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK") { error = nil }
        } message: { Text(error ?? "") }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: Space.md) {
            VStack(alignment: .leading, spacing: 3) {
                Text(project?.name ?? "").font(.system(size: 15, weight: .semibold))
                if let p = project?.path {
                    Text(p.replacingOccurrences(of: #"^/(home|Users)/[^/]+"#, with: "~", options: .regularExpression))
                        .font(.system(size: 11.5, design: .monospaced))
                        .foregroundStyle(theme.colors.muted)
                        .textSelection(.enabled)
                }
            }
            Spacer()
            if let busy { ProgressView().controlSize(.small); Text(busy).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted) }
            HStack(spacing: 2) {
                Menu {
                    ForEach(git?.branches ?? [], id: \.name) { b in
                        Button { act("Switching…") { _ = try await store.api.call("/api/projects/\(BenchAPI.enc(projectId))/checkout", method: "POST", body: ["branch": b.name]) } } label: {
                            if b.current == true { Label(b.name, systemImage: "checkmark") } else { Text(b.name) }
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Text(git?.branch ?? "…").font(.system(size: 12, design: .monospaced))
                        Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
                    }
                    .padding(.horizontal, 10).frame(height: 26)
                }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                Rectangle().fill(theme.colors.lineStrong).frame(width: 1, height: 16)
                pillButton("Pull", count: git?.behind, arrow: "↓") { act("Pulling…") { try await store.api.projectAction(projectId, "pull") } }
                pillButton("Push", count: nil) { act("Pushing…") { try await store.api.projectAction(projectId, "push") } }
                    .disabled((git?.ahead ?? 0) == 0)
                pillButton("Commit…", count: git?.dirty.count) { commitMessage = ""; committing = true }
                    .disabled((git?.dirty.count ?? 0) == 0)
                Menu {
                    Button("Fetch") { act("Fetching…") { try await store.api.projectAction(projectId, "fetch") } }
                } label: { Image(systemName: "ellipsis").frame(width: 30, height: 26) }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            }
            .font(.system(size: 12))
            .padding(.horizontal, 4)
            .pill(theme)
            .disabled(busy != nil)
        }
        .padding(.leading, Space.xl)
        .padding(.trailing, Space.lg)
        .padding(.top, 14)
        .padding(.bottom, Space.md)
    }

    private func pillButton(_ title: String, count: Int?, arrow: String = "", action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(title)
                if let count, count > 0 { Text("\(arrow)\(count)").foregroundStyle(theme.colors.muted) }
            }
            .padding(.horizontal, 10).frame(height: 26).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: New task

    private var newTask: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("New task").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            TextField("", text: $prompt, prompt: Text("What should Claude do?"), axis: .vertical)
                .textFieldStyle(.plain)
                .font(.system(size: TypeScale.md))
                .lineLimit(2...8)
            HStack(spacing: Space.sm) {
                Text("from ") + Text(git?.branch ?? "main").font(.system(size: 12, design: .monospaced)).foregroundColor(theme.colors.text.opacity(0.8))
                Text("·")
                Text(defaultMode.label)
                Spacer()
                Text("⌘↩")
                Button("Start Task", action: startTask)
                    .buttonStyle(CapsuleButton(kind: .primary))
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || starting)
            }
            .font(.system(size: 12))
            .foregroundStyle(theme.colors.muted)
        }
        .padding(.horizontal, Space.lg)
        .padding(.vertical, 14)
        .background(theme.colors.surface2, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(theme.colors.line))
    }

    private var defaultMode: Mode { Mode(rawValue: store.defaults.mode ?? "") ?? .acceptEdits }

    private func startTask() {
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let title = String(text.split(separator: "\n").first ?? "").prefix(80)
        starting = true
        Task {
            do {
                let s = try await store.api.createTask(project: projectId, title: String(title), prompt: text, mode: defaultMode)
                store.apply(s)
                prompt = ""
                open(s.id)
            } catch { self.error = error.localizedDescription }
            starting = false
        }
    }

    // MARK: Rows

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 12, weight: .semibold)).foregroundStyle(theme.colors.muted).padding(.bottom, 4)
            content()
        }
    }

    private func taskRow(_ t: BenchTask) -> some View {
        let s = store.sessions.filter { $0.task_id == t.id }.max { ($0.updated_at ?? "") < ($1.updated_at ?? "") }
        let (line, color) = taskLine(t, s)
        return row(dot: s?.status ?? "idle", title: t.title, trail: Text(line).foregroundStyle(color)) { open("task:\(t.id)") }
    }

    private func row(dot: String, title: String, trail: Text, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Space.md) {
                StatusDot(status: dot)
                Text(title).font(.system(size: 13.5)).lineLimit(1)
                Spacer()
                trail.font(.system(size: 11.5))
            }
            .padding(.horizontal, Space.md)
            .frame(height: 40)
            .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func taskLine(_ t: BenchTask, _ s: Session?) -> (String, Color) {
        if t.state == "merged" { return ("Merged", theme.colors.muted) }
        if t.state == "closed" { return ("Closed", theme.colors.muted) }
        if store.approvals.contains(where: { $0.session_id == s?.id }) { return ("Needs you", theme.colors.warn) }
        if s?.status == "running" { return ("Working", theme.colors.accent) }
        if t.loop?.state == "looping" { return ("Looping", theme.colors.accent) }
        switch t.check_status {
        case "passed": return ("Ready to merge", theme.colors.good)
        case "failed": return ("Check failed", theme.colors.bad)
        default: return (Format.ago(t.updated_at), theme.colors.muted)
        }
    }

    // MARK: Commit

    private var commitSheet: some View {
        VStack(alignment: .leading, spacing: Space.md) {
            Text("Commit \(git?.dirty.count ?? 0) changed file\(git?.dirty.count == 1 ? "" : "s") to \(git?.branch ?? "main")").font(.headline)
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(git?.dirty ?? [], id: \.self) { Text($0).font(.system(size: 12, design: .monospaced)).foregroundStyle(theme.colors.muted) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 160)
            TextField("Commit message", text: $commitMessage, axis: .vertical).lineLimit(2...5).textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("Cancel") { committing = false }.keyboardShortcut(.cancelAction)
                Button("Commit") {
                    committing = false
                    let m = commitMessage
                    act("Committing…") { _ = try await store.api.call("/api/projects/\(BenchAPI.enc(projectId))/commit", method: "POST", body: ["message": m]) }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(commitMessage.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(Space.lg)
        .frame(width: 480)
    }

    // MARK: Server

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
