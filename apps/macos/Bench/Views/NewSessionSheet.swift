import SwiftUI

/// Starts a session: in the project's checkout, in an existing task, or in a new task
/// (its own branch and worktree). The prompt is the session's first message.
struct NewSessionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.theme) private var theme
    let store: BenchStore
    let onCreated: (String) -> Void

    enum Where: String, CaseIterable, Identifiable { case checkout = "Checkout", newTask = "New task", task = "Existing task"; var id: String { rawValue } }

    @State private var projectId: String?
    @State private var place: Where = .checkout
    @State private var taskId: String?
    @State private var taskTitle = ""
    @State private var prompt = ""
    @State private var mode: Mode = .default
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        let tasks = store.tasks.filter { $0.project_id == projectId && $0.state == "active" }
        VStack(alignment: .leading, spacing: Space.md) {
            Text("New Session").font(.title3.weight(.semibold))
            Form {
                Picker("Project", selection: $projectId) {
                    ForEach(store.projects) { Text($0.name).tag(Optional($0.id)) }
                }
                Picker("Where", selection: $place) {
                    ForEach(Where.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                switch place {
                case .newTask: TextField("Task title", text: $taskTitle)
                case .task:
                    Picker("Task", selection: $taskId) {
                        Text("Choose…").tag(String?.none)
                        ForEach(tasks) { Text($0.title).tag(Optional($0.id)) }
                    }
                case .checkout:
                    Text("Works directly in the project's checked-out branch.").foregroundStyle(theme.colors.muted)
                }
                Picker("Mode", selection: $mode) {
                    ForEach(Mode.allCases) { Text($0.label).tag($0) }
                }
            }
            .formStyle(.grouped)
            Text("What should Claude do?").font(.system(size: TypeScale.sm)).foregroundStyle(theme.colors.muted)
            TextEditor(text: $prompt)
                .font(.system(size: TypeScale.md))
                .frame(minHeight: 110)
                .scrollContentBackground(.hidden)
                .padding(Space.xs)
                .background(theme.colors.surface2, in: RoundedRectangle(cornerRadius: theme.radiusSm))
            if let error { Text(error).foregroundStyle(theme.colors.bad) }
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Start") { start() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(busy || projectId == nil || (place == .newTask && taskTitle.isEmpty) || (place == .task && taskId == nil))
            }
        }
        .padding(Space.xl)
        .frame(width: 520)
        .onAppear {
            projectId = projectId ?? store.projects.first?.id
            mode = Mode(rawValue: store.defaults.mode ?? "default") ?? .default
        }
    }

    private func start() {
        guard let pid = projectId else { return }
        busy = true
        error = nil
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            do {
                let s: Session
                if place == .newTask {
                    s = try await store.api.createTask(project: pid, title: taskTitle, prompt: text, mode: mode)
                } else {
                    s = try await store.api.createSession(project: pid, task: place == .task ? taskId : nil, mode: mode)
                    if !text.isEmpty { try await store.api.send(s.id, text: text) }
                }
                store.apply(s)
                onCreated(s.id)
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }
}

/// ⌘K: jump to any session by typing part of its title or project.
struct QuickOpen: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.theme) private var theme
    let store: BenchStore
    let onPick: (String) -> Void
    @State private var query = ""
    @State private var index = 0

    private var matches: [Session] {
        let live = store.sessions.filter { $0.archived_at == nil }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        guard !query.isEmpty else { return Array(live.prefix(12)) }
        let q = query.lowercased()
        return live.filter { s in
            s.title.lowercased().contains(q) || (store.project(s.project_id)?.name.lowercased().contains(q) ?? false)
        }.prefix(12).map { $0 }
    }

    var body: some View {
        VStack(spacing: 0) {
            TextField("Go to session…", text: $query)
                .textFieldStyle(.plain)
                .font(.system(size: TypeScale.lg))
                .padding(Space.md)
                .onSubmit(pick)
                .onChange(of: query) { _, _ in index = 0 }
                .onKeyPress(.downArrow) { index = min(index + 1, max(matches.count - 1, 0)); return .handled }
                .onKeyPress(.upArrow) { index = max(index - 1, 0); return .handled }
            Divider()
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(Array(matches.enumerated()), id: \.element.id) { i, s in
                        SessionRowView(session: s, project: store.project(s.project_id)?.name, detail: nil)
                            .padding(.horizontal, Space.md)
                            .padding(.vertical, Space.sm)
                            .background(i == index ? theme.colors.accent.opacity(0.15) : .clear)
                            .contentShape(Rectangle())
                            .onTapGesture { index = i; pick() }
                    }
                }
            }
            .frame(height: 320)
        }
        .frame(width: 520)
        .onExitCommand { dismiss() }
    }

    private func pick() {
        guard matches.indices.contains(index) else { return }
        onPick(matches[index].id)
        dismiss()
    }
}
