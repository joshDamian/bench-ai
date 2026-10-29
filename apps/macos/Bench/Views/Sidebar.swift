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
                    Section(p.name) {
                        ForEach(sessions) { s in
                            SessionRowView(session: s, project: nil, detail: nil)
                                .tag(s.id)
                                .contextMenu { SessionMenu(store: store, session: s) }
                        }
                    }
                }
            }
            // Sessions outside any project, and any whose project this list does not have.
            let known = Set(store.projects.map(\.id))
            let loose = store.sessions
                .filter { ($0.project_id == nil || !known.contains($0.project_id!)) && visible($0) }
                .sorted(by: newest)
            if !loose.isEmpty {
                Section("Ask Bench") {
                    ForEach(loose) { s in
                        SessionRowView(session: s, project: nil, detail: nil)
                            .tag(s.id)
                            .contextMenu { SessionMenu(store: store, session: s) }
                    }
                }
            }
        }
        .listStyle(.sidebar)
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
                Button { model.showNewSession = true } label: { Label("New Session", systemImage: "square.and.pencil") }
                    .help("New Session (⌘N)")
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
        HStack(spacing: Space.sm) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(label).foregroundStyle(count > 0 ? color : theme.colors.text)
            Spacer()
            if count > 0 { Text("\(count)").foregroundStyle(theme.colors.muted).monospacedDigit() }
        }
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
                if !sub.isEmpty {
                    Text(sub).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Text(Format.ago(session.updated_at))
                .font(.system(size: TypeScale.xs))
                .foregroundStyle(theme.colors.muted)
        }
        .opacity(session.archived_at == nil ? 1 : 0.6)
    }
}

struct StatusDot: View {
    @Environment(\.theme) private var theme
    let status: String
    @State private var pulse = false

    var body: some View {
        Circle()
            .fill(theme.colors.status(status))
            .frame(width: 8, height: 8)
            .opacity(status == "running" && pulse ? 0.35 : 1)
            .animation(status == "running" ? .easeInOut(duration: 0.8).repeatForever() : .default, value: pulse)
            .onAppear { pulse = status == "running" }
            .onChange(of: status) { _, s in pulse = s == "running" }
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
