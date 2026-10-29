import SwiftUI

/// Places a sidebar row can open besides a session.
enum Place: String { case needs = "place:needs", working = "place:working" }

/// Go to session, Needs you and Working, then sessions by project, Ask Bench last.
struct Sidebar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.openSettings) private var openSettings
    let store: BenchStore
    @Binding var selection: String?
    @State private var showArchived = false

    private func visible(_ s: Session) -> Bool { showArchived || s.archived_at == nil }
    private func newest(_ a: Session, _ b: Session) -> Bool { (a.updated_at ?? "") > (b.updated_at ?? "") }

    var body: some View {
        List(selection: $selection) {
            Section {
                PlaceRow(label: "Needs you", count: store.approvals.count, color: theme.colors.warn)
                    .tag(Place.needs.rawValue)
                PlaceRow(label: "Working", count: store.sessions.filter { $0.status == "running" }.count, color: theme.colors.accent)
                    .tag(Place.working.rawValue)
            }
            ForEach(store.projects) { p in
                let sessions = store.sessions.filter { $0.project_id == p.id && visible($0) }.sorted(by: newest)
                if !sessions.isEmpty {
                    Section {
                        ForEach(sessions) { s in
                            SessionRowView(session: s, project: nil, detail: nil)
                                .tag(s.id)
                                .contextMenu { SessionMenu(store: store, session: s) }
                        }
                    } header: {
                        Button { selection = "project:\(p.id)" } label: {
                            HStack(spacing: 4) {
                                Text(p.name).textCase(nil).font(.system(size: 11, weight: .semibold))
                                Image(systemName: "chevron.right").font(.system(size: 8, weight: .semibold)).opacity(0.6)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .help("Open \(p.name)")
                    }
                }
            }
            // Sessions outside any project, and any whose project this list does not have.
            let known = Set(store.projects.map(\.id))
            let loose = store.sessions
                .filter { ($0.project_id == nil || !known.contains($0.project_id!)) && visible($0) }
                .sorted(by: newest)
            if !loose.isEmpty {
                Section {
                    ForEach(loose) { s in
                        SessionRowView(session: s, project: nil, detail: nil)
                            .tag(s.id)
                            .contextMenu { SessionMenu(store: store, session: s) }
                    }
                } header: {
                    Text("Ask Bench").textCase(nil).font(.system(size: 11, weight: .semibold))
                }
            }
        }
        .listStyle(.sidebar)
        .tint(theme.colors.accent)
        .safeAreaInset(edge: .top, spacing: 0) {
            Button { model.showQuickOpen = true } label: {
                HStack(spacing: Space.sm) {
                    Image(systemName: "magnifyingglass")
                    Text("Go to session")
                    Spacer()
                    Text("⌘K")
                }
                .font(.system(size: TypeScale.sm))
                .foregroundStyle(theme.colors.muted)
                .padding(.horizontal, Space.sm)
                .frame(height: 28)
                .background(theme.colors.text.opacity(0.06), in: RoundedRectangle(cornerRadius: theme.radiusSm))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, Space.sm)
            .padding(.bottom, Space.xs)
        }
        .toolbar {
            ToolbarItem {
                Menu {
                    Button("New Session…") { model.showNewSession = true }
                    Button("Add Project…") { model.showAddProject = true }
                } label: { Label("Add", systemImage: "plus") } primaryAction: { model.showNewSession = true }
                .help("New Session (⌘N) · Add Project (⇧⌘N)")
            }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(alignment: .leading, spacing: Space.xs) {
                if let err = store.error {
                    Text(err)
                        .font(.system(size: TypeScale.xs))
                        .foregroundStyle(theme.colors.bad)
                        .lineLimit(3)
                        .textSelection(.enabled)
                }
                HStack(spacing: Space.sm) {
                    Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 6, height: 6)
                    Text(store.connected ? footer : "Connecting…")
                        .font(.system(size: TypeScale.xs))
                        .foregroundStyle(theme.colors.muted)
                        .lineLimit(1)
                    Spacer()
                    Toggle(isOn: $showArchived) { Image(systemName: "archivebox") }
                        .toggleStyle(.button).buttonStyle(.borderless)
                        .help(showArchived ? "Hide Archived" : "Show Archived")
                    Button { openSettings() } label: { Image(systemName: "gearshape") }
                        .buttonStyle(.borderless)
                        .help("Settings (⌘,)")
                }
                .foregroundStyle(theme.colors.muted)
            }
            .padding(.horizontal, Space.md)
            .padding(.vertical, Space.sm)
        }
    }

    private var footer: String {
        let host = URLComponents(string: store.api.config.baseURL)?.host?.split(separator: ".").first.map(String.init) ?? store.api.config.baseURL
        return [host, store.version.map { "Bench \($0)" }].compactMap { $0 }.joined(separator: " · ")
    }
}

private struct PlaceRow: View {
    @Environment(\.theme) private var theme
    let label: String
    let count: Int
    let color: Color

    var body: some View {
        let urgent = count > 0 && label == "Needs you"
        HStack(spacing: 10) {
            Circle().fill(color).frame(width: 7, height: 7).frame(width: 13)
            Text(label).fontWeight(urgent ? .semibold : .regular)
            Spacer()
            if count > 0 { Text("\(count)").fontWeight(urgent ? .semibold : .regular).foregroundStyle(urgent ? color : theme.colors.muted).monospacedDigit() }
        }
        .font(.system(size: 13))
        .foregroundStyle(urgent ? color : theme.colors.text.opacity(0.8))
        .listRowBackground(urgent ? RoundedRectangle(cornerRadius: 7).fill(color.opacity(0.10)).padding(.horizontal, 10) : nil)
    }
}

struct SessionRowView: View {
    @Environment(\.theme) private var theme
    let session: Session
    let project: String?
    let detail: String?

    var body: some View {
        let idle = session.status == "idle"
        HStack(spacing: 10) {
            StatusDot(status: session.status)
            VStack(alignment: .leading, spacing: 2) {
                Text(session.title).lineLimit(1)
                    .foregroundStyle(idle ? theme.colors.text.opacity(0.8) : theme.colors.text)
                let sub = [project, detail].compactMap { $0 }.joined(separator: " · ")
                if !sub.isEmpty {
                    Text(sub).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Text(Format.ago(session.updated_at))
                .font(.system(size: TypeScale.xs))
                .foregroundStyle(session.status == "running" ? theme.colors.accent : theme.colors.muted)
        }
        .font(.system(size: 13))
        .opacity(session.archived_at == nil ? 1 : 0.6)
    }
}

/// A session's state at a glance: dim when idle, a soft ring while Claude works.
struct StatusDot: View {
    @Environment(\.theme) private var theme
    let status: String

    var body: some View {
        let c = theme.colors.status(status)
        Circle()
            .fill(status == "idle" ? c.opacity(0.45) : c)
            .frame(width: 7, height: 7)
            .background {
                if status == "running" { Circle().fill(c.opacity(0.25)).frame(width: 13, height: 13) }
            }
            .frame(width: 13, height: 13)
    }
}

/// Rename, archive and open-in-terminal, for the sidebar's context menu and the toolbar.
struct SessionMenu: View {
    let store: BenchStore
    let session: Session
    @State private var renaming = false

    var body: some View {
        Button("Open in Terminal") { TerminalLauncher.open(session, store: store) }
            .disabled(session.claude_session_id == nil)
        Button("Copy Resume Command") { TerminalLauncher.copy(session) }
            .disabled(session.claude_session_id == nil)
        Divider()
        Menu("Permission Mode") {
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
