import SwiftUI

/// Needs you, then sessions by project, newest first.
struct Sidebar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    @Binding var selection: String?
    @State private var showArchived = false

    var body: some View {
        List(selection: $selection) {
            if !store.approvals.isEmpty {
                Section("Needs you") {
                    ForEach(store.approvals) { a in
                        if let s = store.session(a.session_id) {
                            SessionRowView(session: s, project: store.project(s.project_id)?.name, detail: approvalLine(a))
                                .tag(s.id)
                        }
                    }
                }
            }
            // Sessions outside any project (the server's "ask" sessions) and any whose
            // project this list does not have.
            let known = Set(store.projects.map(\.id))
            let loose = store.sessions
                .filter { ($0.project_id == nil || !known.contains($0.project_id!)) && (showArchived || $0.archived_at == nil) }
                .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
            if !loose.isEmpty {
                Section("Ask") {
                    ForEach(loose) { s in
                        SessionRowView(session: s, project: nil, detail: nil)
                            .tag(s.id)
                            .contextMenu { SessionMenu(store: store, session: s) }
                    }
                }
            }
            ForEach(store.projects) { p in
                let sessions = store.sessions
                    .filter { $0.project_id == p.id && (showArchived || $0.archived_at == nil) }
                    .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
                if !sessions.isEmpty {
                    Section(p.name) {
                        ForEach(sessions) { s in
                            SessionRowView(session: s, project: nil, detail: store.task(s.task_id)?.title)
                                .tag(s.id)
                                .contextMenu { SessionMenu(store: store, session: s) }
                        }
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .background(theme.colors.surfaceRail)
        .toolbar {
            ToolbarItem {
                Button { model.showNewSession = true } label: { Label("New Session", systemImage: "square.and.pencil") }
                    .help("New session (⌘N)")
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
            HStack {
                Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 7, height: 7)
                Text(store.connected ? host : "Connecting…")
                    .font(.system(size: TypeScale.xs))
                    .foregroundStyle(theme.colors.muted)
                    .lineLimit(1)
                Spacer()
                Toggle("Archived", isOn: $showArchived)
                    .toggleStyle(.button)
                    .controlSize(.small)
            }
            }
            .padding(.horizontal, Space.md)
            .padding(.vertical, Space.sm)
        }
    }

    private var host: String { URLComponents(string: store.api.config.baseURL)?.host ?? store.api.config.baseURL }

    private func approvalLine(_ a: Approval) -> String {
        switch a.tool {
        case "AskUserQuestion": "Asking a question"
        case "ExitPlanMode": "Has a plan"
        default: a.title ?? "Wants to use \(a.tool)"
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
