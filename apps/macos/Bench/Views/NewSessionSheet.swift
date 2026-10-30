import SwiftUI

/// ⌘N, as the New session board draws it: project, where (a new task on its own branch,
/// or the checkout), mode, and the first message.
struct NewSessionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.theme) private var theme
    let store: BenchStore
    let onCreated: (String) -> Void

    enum Where: String, CaseIterable, Identifiable { case newTask = "New Task", checkout = "Checkout"; var id: String { rawValue } }

    @State private var projectId: String?
    @State private var place: Where = .newTask
    @State private var prompt = ""
    @State private var mode: Mode = .acceptEdits
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("New Session").font(.system(size: 15, weight: .semibold))
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: Space.md) {
                GridRow {
                    label("Project")
                    Picker("", selection: $projectId) {
                        ForEach(store.projects) { Text($0.name).tag(Optional($0.id)) }
                        Divider()
                        Text("Ask Bench").tag(String?.none)
                    }
                    .labelsHidden()
                }
                if projectId != nil {
                    GridRow {
                        label("Where")
                        Picker("", selection: $place) {
                            ForEach(Where.allCases) { Text($0.rawValue).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                    }
                }
                GridRow {
                    label("Mode")
                    Picker("", selection: $mode) {
                        ForEach(Mode.allCases) { Text($0.label).tag($0) }
                    }
                    .labelsHidden()
                }
            }
            .font(.system(size: 13))
            TextField("", text: $prompt, prompt: Text("What should Claude do?"), axis: .vertical)
                .textFieldStyle(.plain)
                .font(.system(size: TypeScale.md))
                .lineLimit(5...12)
                .padding(.horizontal, Space.md)
                .padding(.vertical, 10)
                .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(theme.colors.lineStrong))
            if let error { Text(error).font(.system(size: 12)).foregroundStyle(theme.colors.bad) }
            HStack(spacing: Space.sm) {
                Text(hint).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                Spacer()
                Button("Cancel") { dismiss() }
                    .buttonStyle(CapsuleButton(kind: .outline))
                    .keyboardShortcut(.cancelAction)
                Button("Start") { start() }
                    .buttonStyle(CapsuleButton(kind: .primary))
                    .keyboardShortcut(.defaultAction)
                    .disabled(busy || (projectId != nil && place == .newTask && prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
            }
        }
        .padding(20)
        .frame(width: 560)
        .background(theme.colors.surface2)
        .onAppear {
            projectId = store.projects.first?.id
            mode = Mode(rawValue: store.defaults.mode ?? "") ?? .acceptEdits
        }
    }

    private func label(_ s: String) -> some View {
        Text(s).foregroundStyle(theme.colors.muted).frame(width: 96, alignment: .leading)
    }

    private var hint: String {
        guard projectId != nil else { return "A conversation about all of Bench" }
        let branch = store.tasks.first { $0.project_id == projectId }?.base_branch ?? "main"
        return place == .newTask ? "Works on a new branch from \(branch)" : "Works directly in the checkout"
    }

    private func start() {
        let pid = projectId
        busy = true
        error = nil
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            do {
                let s: Session
                if place == .newTask, let pid {
                    let title = String(text.split(separator: "\n").first ?? "").prefix(80)
                    s = try await store.api.createTask(project: pid, title: String(title), prompt: text, mode: mode)
                } else {
                    s = try await store.api.createSession(project: pid, task: nil, mode: mode)
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

/// ⌘K, as the Go to session board draws it: type, choose with the arrows, Return opens,
/// ⌘Return starts a new session with what you typed.
struct QuickOpen: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.theme) private var theme
    @Environment(AppModel.self) private var app
    let store: BenchStore
    let onPick: (String) -> Void
    @State private var query = ""
    @State private var index = 0
    @FocusState private var focused: Bool

    private var matches: [Session] {
        let live = store.sessions.filter { $0.archived_at == nil }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        guard !query.isEmpty else { return Array(live.prefix(8)) }
        let q = query.lowercased()
        return live.filter { s in
            s.title.lowercased().contains(q) || (store.project(s.project_id)?.name.lowercased().contains(q) ?? false)
        }.prefix(8).map { $0 }
    }

    var body: some View {
        let rows = matches
        VStack(spacing: 0) {
            HStack(spacing: Space.md) {
                Image(systemName: "magnifyingglass").foregroundStyle(theme.colors.muted)
                TextField("", text: $query, prompt: Text("Go to session"))
                    .textFieldStyle(.plain)
                    .font(.system(size: 17))
                    .focused($focused)
                    .onSubmit(pick)
                    .onChange(of: query) { _, _ in index = 0 }
                    .onKeyPress(.downArrow) { index = min(index + 1, rows.count - (query.isEmpty ? 1 : 0)); return .handled }
                    .onKeyPress(.upArrow) { index = max(index - 1, 0); return .handled }
            }
            .padding(.horizontal, 18)
            .frame(height: 52)
            Rectangle().fill(theme.colors.line).frame(height: 1)
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, s in
                        item(selected: i == index, trailing: "↩") {
                            StatusDot(status: s.status)
                        } title: { Text(s.title) } sub: {
                            Text([store.project(s.project_id)?.name ?? "Ask Bench", s.task_id != nil ? "task" : Format.ago(s.updated_at)].joined(separator: " · "))
                        }
                        .onTapGesture { index = i; pick() }
                    }
                    if !query.isEmpty {
                        item(selected: index == rows.count, trailing: nil) {
                            Image(systemName: "plus").font(.system(size: 12)).foregroundStyle(theme.colors.muted).frame(width: 13)
                        } title: { Text("New session: \u{201C}\(query)\u{201D}") } sub: { Text("⌘↩") }
                        .onTapGesture { startNew() }
                    }
                }
                .padding(Space.sm)
            }
            .frame(height: 272)
            Rectangle().fill(theme.colors.line).frame(height: 1)
            HStack(spacing: Space.lg) {
                Text("↑↓ choose"); Text("↩ open"); Text("esc close")
                Spacer()
            }
            .font(.system(size: 11.5))
            .foregroundStyle(theme.colors.muted)
            .padding(.horizontal, 18)
            .frame(height: 34)
        }
        .frame(width: 600)
        .background(theme.colors.surface2)
        .onAppear { focused = true }
        .onExitCommand { dismiss() }
        .background {
            Button("") { startNew() }.keyboardShortcut(.return, modifiers: .command).hidden()
        }
    }

    private func item<D: View, T: View, S: View>(selected: Bool, trailing: String?, @ViewBuilder dot: () -> D, @ViewBuilder title: () -> T, @ViewBuilder sub: () -> S) -> some View {
        HStack(spacing: Space.md) {
            dot()
            VStack(alignment: .leading, spacing: 0) {
                title().font(.system(size: 13.5)).foregroundStyle(selected ? theme.colors.text : theme.colors.text.opacity(0.8)).lineLimit(1)
                sub().font(.system(size: 11)).foregroundStyle(theme.colors.muted).lineLimit(1)
            }
            Spacer()
            if selected, let trailing { Text(trailing).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted) }
        }
        .padding(.horizontal, Space.md)
        .frame(height: 44)
        .background(selected ? theme.colors.accent.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
    }

    private func pick() {
        let rows = matches
        if index == rows.count && !query.isEmpty { startNew(); return }
        guard rows.indices.contains(index) else { return }
        onPick(rows[index].id)
        dismiss()
    }

    private func startNew() {
        dismiss()
        app.showNewSession = true
    }
}
