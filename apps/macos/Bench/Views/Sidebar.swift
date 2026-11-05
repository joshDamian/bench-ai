import SwiftUI

/// Places a sidebar row can open besides a session, task or project.
enum Place: String, CaseIterable {
    case needs = "place:needs", running = "place:running", all = "place:all"
    var title: String {
        switch self {
        case .needs: "Needs you"
        case .running: "Running"
        case .all: "All sessions"
        }
    }
}

/// The Mac v2 sidebar: three places, then each project's open tasks and the sessions in
/// its checkout, Ask Bench last. Every row says its state in a word that never wraps.
struct Sidebar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    @Binding var selection: String?

    private func newest(_ a: Session, _ b: Session) -> Bool { (a.updated_at ?? "") > (b.updated_at ?? "") }

    /// Projects with the most recently active session or task first.
    private var byRecency: [Project] {
        var last: [String: String] = [:]
        for s in store.sessions { if let p = s.project_id, let u = s.updated_at, u > (last[p] ?? "") { last[p] = u } }
        for t in store.tasks { if let p = t.project_id, let u = t.updated_at, u > (last[p] ?? "") { last[p] = u } }
        return store.projects.sorted { (last[$0.id] ?? $0.last_used_at ?? "") > (last[$1.id] ?? $1.last_used_at ?? "") }
    }

    var body: some View {
        VStack(spacing: 0) {
        top
        List(selection: $selection) {
            Section {
                placeRow(.needs, glyph: "circle.fill", tint: theme.colors.warn, count: store.approvals.count, loud: true)
                placeRow(.running, glyph: "circle.dashed", tint: theme.colors.accent,
                         count: store.sessions.filter { $0.status == "running" && $0.archived_at == nil }.count, loud: false)
                placeRow(.all, glyph: "line.3.horizontal", tint: theme.colors.muted, count: 0, loud: false)
            }
            let quiet = store.projects.filter { p in
                !store.tasks.contains { $0.project_id == p.id && $0.state == "active" } &&
                !store.sessions.contains { $0.project_id == p.id && $0.task_id == nil && $0.archived_at == nil }
            }
            ForEach(byRecency.filter { p in !quiet.contains(p) }) { p in
                let tasks = store.tasks.filter { $0.project_id == p.id && $0.state == "active" }
                    .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
                let loose = store.sessions.filter { $0.project_id == p.id && $0.task_id == nil && $0.archived_at == nil }
                    .sorted(by: newest).prefix(5)
                Section {
                    ForEach(tasks) { t in
                        row(title: t.title, mark: Mark.of(task: t, store: store), meta: Describe.task(t, store: store))
                            .tag("task:\(t.id)")
                    }
                    ForEach(Array(loose)) { s in
                        row(title: s.title, mark: Mark.of(s, store: store), meta: Describe.session(s, store: store))
                            .tag(s.id)
                            .contextMenu { SessionMenu(store: store, session: s) }
                    }
                } header: {
                    Button { selection = "project:\(p.id)" } label: {
                        Text(p.name).textCase(nil).font(.system(size: 11, weight: .semibold))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .help("Open \(p.name)")
                }
                .collapsible(false)
            }
            // Projects with nothing open: one row each, so they stay one click away.
            if !quiet.isEmpty {
                Section {
                    ForEach(quiet) { p in
                        HStack(spacing: Space.sm) {
                            Image(systemName: "folder").font(.system(size: 11)).foregroundStyle(theme.colors.muted).frame(width: 18)
                            Text(p.name).lineLimit(1)
                        }
                        .font(.system(size: 13))
                        .tag("project:\(p.id)")
                    }
                } header: { Text("Projects").textCase(nil).font(.system(size: 11, weight: .semibold)) }
                .collapsible(false)
            }
            let known = Set(store.projects.map(\.id))
            let ask = store.sessions
                .filter { ($0.project_id == nil || !known.contains($0.project_id!)) && $0.archived_at == nil }
                .sorted(by: newest).prefix(8)
            if !ask.isEmpty {
                Section {
                    ForEach(Array(ask)) { s in
                        row(title: s.title, mark: Mark.of(s, store: store), meta: Describe.session(s, store: store))
                            .tag(s.id)
                            .contextMenu { SessionMenu(store: store, session: s) }
                    }
                } header: { Text("Ask Bench").textCase(nil).font(.system(size: 11, weight: .semibold)) }
                .collapsible(false)
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .environment(\.defaultMinListRowHeight, 28)
        footer
        }
    }

    private func placeRow(_ p: Place, glyph: String, tint: Color, count: Int, loud: Bool) -> some View {
        HStack(spacing: Space.sm) {
            Image(systemName: glyph).font(.system(size: p == .needs ? 8 : 11, weight: .semibold)).foregroundStyle(tint).frame(width: 18)
            Text(p.title).lineLimit(1)
            Spacer(minLength: Space.xs)
            if count > 0 {
                if loud {
                    Text("\(count)").font(.system(size: 11, weight: .semibold)).foregroundStyle(macDark.colors.onAccent)
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(theme.colors.warn, in: Capsule())
                } else {
                    Text("\(count)").font(.system(size: 11)).foregroundStyle(theme.colors.muted).monospacedDigit()
                }
            }
        }
        .font(.system(size: 13))
        .tag(p.rawValue)
    }

    private func row(title: String, mark: Mark, meta: String) -> some View {
        HStack(spacing: Space.sm) {
            MarkView(mark: mark)
            Text(title).lineLimit(1).truncationMode(.tail)
            Spacer(minLength: Space.xs)
            Text(meta)
                .font(.system(size: 11))
                .foregroundStyle(mark == .idle ? theme.colors.muted : mark.tint(theme.colors))
                .lineLimit(1)
                .fixedSize()
        }
        .font(.system(size: 13))
    }

    /// The pane's top: room for the traffic lights, then hide-sidebar and new-session.
    private var top: some View {
        HStack(spacing: 2) {
            TrafficLights().padding(.leading, 6)
            Spacer()
            Button { withAnimation(.snappy) { model.showSidebar = false } } label: {
                Image(systemName: "sidebar.left").font(.system(size: 13)).frame(width: 28, height: 26).contentShape(Rectangle())
            }
            .buttonStyle(.plain).help("Hide Sidebar (⌃⌘S)")
            Button { model.newSession() } label: {
                Image(systemName: "square.and.pencil").font(.system(size: 13)).frame(width: 28, height: 26).contentShape(Rectangle())
            }
            .buttonStyle(.plain).help("New Session (⌘N)")
        }
        .foregroundStyle(theme.colors.muted)
        .padding(.horizontal, 10)
        .frame(height: 46)
        .background(WindowDragArea())
    }

    private var footer: some View {
        HStack(spacing: Space.sm) {
            Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 7, height: 7)
            Text(store.connected ? Describe.host(store) : "Connecting…")
                .lineLimit(1)
            Spacer()
            if let v = store.version { Text("Bench \(v)").foregroundStyle(theme.colors.muted).fixedSize() }
        }
        .font(.system(size: 12))
        .foregroundStyle(theme.colors.muted)
        .padding(.horizontal, 14)
        .frame(height: 40)
    }
}

/// Rename, archive and open-in-terminal, for context menus.
struct SessionMenu: View {
    let store: BenchStore
    let session: Session

    var body: some View {
        Button("Open in Claude Code") { TerminalLauncher.open(session, store: store) }
            .disabled(session.claude_session_id == nil)
        Button("Copy Resume Command") { TerminalLauncher.copy(session) }
            .disabled(session.claude_session_id == nil)
        Divider()
        Menu("Permissions") {
            ForEach(Mode.allCases) { m in
                Button { Task { try? await store.api.setMode(session.id, m) } } label: {
                    if m == session.mode { Label(m.label, systemImage: "checkmark") } else { Text(m.label) }
                }
            }
        }
        Button(session.archived_at == nil ? "Archive" : "Unarchive") {
            Task { try? await store.api.archive(session.id, session.archived_at == nil) }
        }
    }
}

/// A session's state as a dot, for lists that still take a status string.
struct StatusDot: View {
    let status: String
    var body: some View {
        MarkView(mark: status == "running" ? .running : status == "waiting" ? .needs : status == "error" ? .failed : .idle)
    }
}

struct SessionRowView: View {
    @Environment(\.theme) private var theme
    let session: Session
    let project: String?
    let detail: String?

    var body: some View {
        HStack(spacing: Space.sm) {
            StatusDot(status: session.status)
            VStack(alignment: .leading, spacing: 2) {
                Text(session.title).lineLimit(1)
                let sub = [project, detail].compactMap { $0 }.joined(separator: " · ")
                if !sub.isEmpty { Text(sub).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted).lineLimit(1) }
            }
            Spacer(minLength: 0)
            Text(Format.ago(session.updated_at)).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted).fixedSize()
        }
        .font(.system(size: 13))
    }
}
