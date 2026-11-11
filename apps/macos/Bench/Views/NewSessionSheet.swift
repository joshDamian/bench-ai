import SwiftUI

/// ⌘N, as the Mac v2 board draws it: what Claude should do first, then where and how.
struct NewSessionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.theme) private var theme
    let store: BenchStore
    let project: String?
    let onCreated: (String) -> Void

    @State private var projectId: String?
    @State private var newBranch = true
    @State private var prompt = ""
    @State private var mode: Mode = .acceptEdits
    @State private var providerId: String?
    @State private var modelId: String?
    @State private var effort: String?
    @State private var busy = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("New Session").font(.system(size: 15, weight: .semibold))
            GrowingEditor(text: $prompt, placeholder: "What should Claude do?", size: TypeScale.md, minLines: 5, maxLines: 12)
                .focused($focused)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(theme.colors.fill(0.06), in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(focused ? theme.colors.accent.opacity(0.6) : .clear))
            Group2 {
                GroupRow(title: "Project", first: true) {
                    Menu {
                        ForEach(projects) { p in Button(p.name) { projectId = p.id } }
                        Divider()
                        Button("Ask Bench, no project") { projectId = nil }
                    } label: { menuLabel(store.project(projectId)?.name ?? "Ask Bench") }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                }
                if projectId != nil {
                    GroupRow(title: "Work in") {
                        Picker("", selection: $newBranch) {
                            Text("New branch").tag(true)
                            Text("The checkout").tag(false)
                        }
                        .pickerStyle(.segmented).labelsHidden().fixedSize()
                    }
                }
                GroupRow(title: "Model") {
                    Menu {
                        Button { providerId = nil; modelId = nil } label: { check(modelId == nil, "Default") }
                        ForEach(store.providers.filter { $0.enabled && $0.ready != false }) { p in
                            Section(p.name) {
                                ForEach(p.models.filter(\.enabled)) { m in
                                    Button { providerId = p.id; modelId = m.id } label: { check(modelId == m.id, m.label) }
                                }
                            }
                        }
                        Divider()
                        Section("Effort") {
                            Button { effort = nil } label: { check(effort == nil, "Model default") }
                            ForEach(Effort.allCases) { e in Button { effort = e.rawValue } label: { check(effort == e.rawValue, e.label) } }
                        }
                    } label: { menuLabel(modelLabel) }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                }
                GroupRow(title: "Permissions") {
                    Menu {
                        ForEach(Mode.allCases) { m in Button { mode = m } label: { check(mode == m, m.label) } }
                    } label: { menuLabel(mode.label) }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                }
            }
            if let error { Text(error).font(.system(size: 12)).foregroundStyle(theme.colors.bad).textSelection(.enabled) }
            HStack(spacing: Space.sm) {
                Text(hint).font(.system(size: 12)).foregroundStyle(theme.colors.muted).lineLimit(2)
                Spacer()
                Button("Cancel") { dismiss() }.buttonStyle(TonalButton()).keyboardShortcut(.cancelAction)
                Button { start() } label: { HStack(spacing: 6) { Text("Start"); KeyHint("⌘↩") } }
                    .buttonStyle(PrimaryButton())
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(busy || (projectId != nil && newBranch && prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
            }
        }
        .padding(20)
        .frame(width: 620)
        .background(theme.colors.surface2)
        .onAppear {
            projectId = project ?? projects.first?.id
            mode = Mode(rawValue: store.defaults.mode ?? "") ?? .acceptEdits
            focused = true
        }
    }

    /// Most recently used first, as the Android picker sorts them.
    private var projects: [Project] {
        store.projects.sorted { ($0.last_used_at ?? "") > ($1.last_used_at ?? "") }
    }

    private var modelLabel: String {
        let m = store.providers.flatMap(\.models).first { $0.id == modelId }?.label ?? "Default"
        guard let e = effort.flatMap(Effort.init(rawValue:)) else { return m }
        return "\(m) · \(e.label)"
    }

    private func menuLabel(_ s: String) -> some View {
        HStack(spacing: 6) {
            Text(s).lineLimit(1)
            Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
        }
        .font(.system(size: 12.5))
        .padding(.horizontal, 9).frame(height: 26)
        .background(theme.colors.fill(0.1), in: RoundedRectangle(cornerRadius: 7))
    }

    @ViewBuilder private func check(_ on: Bool, _ title: String) -> some View {
        if on { Label(title, systemImage: "checkmark") } else { Text(title) }
    }

    private var hint: String {
        guard projectId != nil else { return "A conversation about all of Bench." }
        return newBranch ? "Runs on its own branch. You merge when it's done." : "Works directly in the checkout."
    }

    private func start() {
        let pid = projectId
        busy = true
        error = nil
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            do {
                let s: Session
                if newBranch, let pid {
                    let title = String(text.split(separator: "\n").first ?? "").prefix(80)
                    s = try await store.api.createTask(project: pid, title: String(title), prompt: text, mode: mode)
                } else {
                    s = try await store.api.createSession(project: pid, task: nil, mode: mode)
                }
                var patch: [String: Any] = [:]
                if let providerId, let modelId { patch["providerId"] = providerId; patch["modelId"] = modelId }
                if let effort { patch["effort"] = effort }
                if !patch.isEmpty { try? await store.api.patchSession(s.id, patch) }
                if !(newBranch && pid != nil) && !text.isEmpty { try await store.api.send(s.id, text: text) }
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

/// ⌘K: go anywhere or run a command. Actions for what's on screen come first, then
/// sessions, tasks and projects that match. ↑↓ choose, ↩ runs; a session, task or project
/// opens in a tab of its own, or the tab it already has.
struct CommandPalette: View {
    struct Item: Identifiable {
        let id: String
        let group: String
        let icon: String
        let title: String
        let sub: String
        let keys: String
        let run: () -> Void
    }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.theme) private var theme
    @Environment(\.openWindow) private var openWindow
    @Environment(AppModel.self) private var app
    let store: BenchStore
    @State private var query = ""
    @State private var index = 0
    @FocusState private var focused: Bool

    private var items: [Item] {
        let q = query.lowercased()
        func hit(_ s: String) -> Bool { q.isEmpty || s.lowercased().contains(q) }
        var out: [Item] = []
        // Actions: the task on screen first, then the app's.
        if let sel = app.selection, sel.hasPrefix("task:"), let t = store.task(String(sel.dropFirst(5))), t.state == "active" {
            let base = t.base_branch ?? "main"
            if hit("merge ship \(base)") {
                out.append(Item(id: "a.ship", group: "Actions", icon: "arrow.triangle.merge", title: "Merge into \(base)", sub: t.title, keys: "⇧⌘M") { app.shipRequest += 1 })
            }
            if hit("update pull \(base)") {
                out.append(Item(id: "a.update", group: "Actions", icon: "arrow.down.circle", title: "Update from \(base)", sub: t.title, keys: "") {
                    Task { try? await store.api.taskAction(t.id, "update") }
                })
            }
        }
        let general: [(String, String, String, String, () -> Void)] = [
            ("a.new", "plus", "New session", "⌘N", { app.newSession() }),
            ("a.project", "folder.badge.plus", "Add project", "⇧⌘N", { app.showAddProject = true }),
            ("a.terminal", "terminal", app.showTerminal ? "Hide terminal" : "Show terminal", "⇧⌘T", { app.showTerminal.toggle() }),
            ("a.inspector", "sidebar.right", app.showInspector ? "Hide inspector" : "Show inspector", "⌘J", { app.showInspector.toggle() }),
            ("a.dashboard", "chart.bar", "Dashboard", "⇧⌘D", { openWindow(id: "dashboard") }),
        ]
        for g in general where hit(g.2) && !q.isEmpty {
            out.append(Item(id: g.0, group: "Actions", icon: g.1, title: g.2, sub: "", keys: g.3, run: g.4))
        }
        // The session on screen: stop it, approve what it asks, change how it runs.
        if let sid = app.selection, let s = store.session(sid) {
            var mine: [(String, String, String, String, () -> Void)] = [
                ("s.find", "magnifyingglass", "Find in conversation", "⌘F", { app.findRequest += 1 }),
                ("s.window", "macwindow.badge.plus", "Open in new window", "⌥⌘O", { openWindow(id: "session", value: sid) }),
                ("s.quick", "text.bubble", "Quick prompt", QuickPrompt.label, { QuickPrompt.shared.show() }),
            ]
            if s.status == "running" { mine.insert(("s.stop", "stop.circle", "Stop Claude", "⌘.", { app.stopRequest += 1 }), at: 0) }
            if store.approvals.contains(where: { $0.session_id == sid }) { mine.insert(("s.approve", "checkmark.circle", "Approve request", "⇧⌘A", { app.approveRequest += 1 }), at: 0) }
            for m in Mode.allCases where m != s.mode {
                mine.append(("s.mode.\(m.rawValue)", "lock.shield", "Permissions: \(m.label)", "", { Task { try? await store.api.setMode(sid, m) } }))
            }
            for p in store.providers where p.enabled {
                for md in p.models where md.enabled && md.id != s.model_id {
                    mine.append(("s.model.\(md.id)", "cpu", "Model: \(md.label)", "", { Task { try? await store.api.patchSession(sid, ["providerId": p.id, "modelId": md.id]) } }))
                }
            }
            for g in mine where (q.isEmpty && ["s.stop", "s.approve"].contains(g.0)) || (!q.isEmpty && hit(g.2)) {
                out.append(Item(id: g.0, group: "This session", icon: g.1, title: g.2, sub: s.title, keys: g.3, run: g.4))
            }
        }
        let live = store.sessions.filter { $0.archived_at == nil && $0.task_id == nil }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        for s in live where hit(s.title) || hit(store.project(s.project_id)?.name ?? "") {
            out.append(Item(id: s.id, group: "Sessions", icon: "bubble.left", title: s.title, sub: store.project(s.project_id)?.name ?? "Ask Bench", keys: "") { app.openTab(s.id) })
            if out.filter({ $0.group == "Sessions" }).count >= 6 { break }
        }
        let tasks = store.tasks.filter { $0.state == "active" }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        for t in tasks where hit(t.title) || hit(t.branch ?? "") {
            out.append(Item(id: "task:\(t.id)", group: "Tasks", icon: "arrow.triangle.branch", title: t.title, sub: store.project(t.project_id)?.name ?? "", keys: "") { app.openTab("task:\(t.id)") })
            if out.filter({ $0.group == "Tasks" }).count >= 5 { break }
        }
        for p in store.projects where !q.isEmpty && hit(p.name) {
            out.append(Item(id: "project:\(p.id)", group: "Projects", icon: "folder", title: p.name, sub: "", keys: "") { app.openTab("project:\(p.id)") })
        }
        return out
    }

    var body: some View {
        let rows = items
        VStack(spacing: 0) {
            HStack(spacing: Space.md) {
                Image(systemName: "magnifyingglass").foregroundStyle(theme.colors.muted)
                TextField("", text: $query, prompt: Text("Search or run a command"))
                    .textFieldStyle(.plain)
                    .font(.system(size: 17))
                    .focused($focused)
                    .onSubmit { run(rows) }
                    .onChange(of: query) { _, _ in index = 0 }
                    .onKeyPress(.downArrow) { index = min(index + 1, max(rows.count - 1, 0)); return .handled }
                    .onKeyPress(.upArrow) { index = max(index - 1, 0); return .handled }
            }
            .padding(.horizontal, 18)
            .frame(height: 56)
            Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1)
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        if rows.isEmpty {
                            Text("Nothing matches.").font(.system(size: 13)).foregroundStyle(theme.colors.muted).padding(12)
                        }
                        ForEach(Array(rows.enumerated()), id: \.element.id) { i, r in
                            if i == 0 || rows[i - 1].group != r.group {
                                Text(r.group).font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.colors.muted)
                                    .padding(.horizontal, 10).padding(.top, i == 0 ? 4 : 10).padding(.bottom, 2)
                            }
                            row(r, selected: i == index)
                                .id(r.id)
                                .onTapGesture { index = i; run(rows) }
                        }
                    }
                    .padding(8)
                }
                .frame(height: 340)
                .onChange(of: index) { _, i in if rows.indices.contains(i) { proxy.scrollTo(rows[i].id) } }
            }
            Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1)
            HStack(spacing: Space.lg) {
                Text("↩ Run"); Text("↑↓ Choose")
                Spacer()
                Text("esc Close")
            }
            .font(.system(size: 11.5))
            .foregroundStyle(theme.colors.muted)
            .padding(.horizontal, 18)
            .frame(height: 36)
        }
        .frame(width: 640)
        .background(theme.colors.surface2)
        .onAppear { focused = true }
        .onExitCommand { dismiss() }
    }

    private func row(_ r: Item, selected: Bool) -> some View {
        HStack(spacing: 12) {
            Image(systemName: r.icon).font(.system(size: 11))
                .frame(width: 22, height: 22)
                .background(selected ? theme.colors.onAccent.opacity(0.15) : theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 6))
            HStack(spacing: 0) {
                Text(r.title)
                if !r.sub.isEmpty { Text(" · \(r.sub)").opacity(0.72) }
            }
            .font(.system(size: 13.5))
            .lineLimit(1)
            Spacer()
            if !r.keys.isEmpty { Text(r.keys).font(.system(size: 11)).opacity(0.72) }
        }
        .foregroundStyle(selected ? theme.colors.onAccent : theme.colors.text)
        .padding(.horizontal, 10)
        .frame(height: 38)
        .background(selected ? theme.colors.accent : .clear, in: RoundedRectangle(cornerRadius: 9))
        .contentShape(Rectangle())
    }

    private func run(_ rows: [Item]) {
        guard rows.indices.contains(index) else { return }
        let r = rows[index]
        dismiss()
        DispatchQueue.main.async { r.run() }
    }
}
