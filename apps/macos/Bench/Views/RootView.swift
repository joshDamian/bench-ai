import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        Group {
            if let store = model.store {
                MainView(store: store)
                    .onChange(of: store.unauthorized) { _, gone in
                        // A token the server no longer knows (revoked elsewhere) goes back to pairing.
                        if gone && !store.api.config.isLoopback { model.unpair() }
                    }
            } else {
                PairView()
            }
        }
        .background(theme.colors.bg)
    }
}

struct MainView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore

    var body: some View {
        @Bindable var model = model
        NavigationSplitView {
            Sidebar(store: store, selection: $model.selection)
                .navigationSplitViewColumnWidth(min: 240, ideal: 290, max: 400)
        } detail: {
            if let id = model.selection, let place = Place(rawValue: id) {
                PlaceView(store: store, place: place) { model.selection = $0 }
            } else if let id = model.selection, id.hasPrefix("project:") {
                let pid = String(id.dropFirst("project:".count))
                ProjectView(store: store, projectId: pid) { model.selection = $0 }.id(pid)
            } else if let id = model.selection, id.hasPrefix("task:") {
                let tid = String(id.dropFirst("task:".count))
                TaskView(store: store, taskId: tid, sessionId: nil, selection: $model.selection).id(tid)
            } else if let id = model.selection, store.session(id) != nil {
                SessionView(store: store, sessionId: id)
                    .id(id)
            } else {
                EmptyDetail(store: store)
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if !store.connected && store.dropped {
                Text(store.error ?? "Reconnecting…")
                    .font(.system(size: TypeScale.sm, weight: .medium))
                    .foregroundStyle(theme.colors.warn)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Space.xs)
                    .background(theme.colors.warn.opacity(0.12))
            }
        }
        .sheet(isPresented: $model.showNewSession) {
            NewSessionSheet(store: store) { id in model.selection = id }
                .environment(\.theme, theme)
        }
        .sheet(isPresented: $model.showQuickOpen) {
            QuickOpen(store: store) { id in model.selection = id }
                .environment(\.theme, theme)
        }
    }
}

private struct EmptyDetail: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore

    var body: some View {
        VStack(spacing: Space.md) {
            Image(systemName: "rectangle.split.3x1")
                .font(.system(size: 36))
                .foregroundStyle(theme.colors.muted)
            Text(store.projects.isEmpty ? "Add a project to get started." : "Choose a session, or start a new one.")
                .foregroundStyle(theme.colors.muted)
            if !store.projects.isEmpty {
                Button("New Session") { model.showNewSession = true }
                    .keyboardShortcut("n")
                    .buttonStyle(.borderedProminent)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bg)
    }
}

/// Needs you and Working: the sessions in that state, newest first.
struct PlaceView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let place: Place
    let open: (String) -> Void

    private var rows: [(Session, String?)] {
        switch place {
        case .needs:
            store.approvals.compactMap { a in store.session(a.session_id).map { ($0, Self.waiting(a)) } }
        case .working:
            store.sessions.filter { $0.status == "running" }
                .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
                .map { ($0, nil) }
        }
    }

    static func waiting(_ a: Approval) -> String {
        switch a.tool {
        case "AskUserQuestion": "Asking a question"
        case "ExitPlanMode": "Has a plan for you to review"
        default: a.title ?? "Wants to use \(a.tool)"
        }
    }

    var body: some View {
        let rows = rows
        Group {
            if rows.isEmpty {
                ContentUnavailableView(place == .needs ? "Nothing needs you" : "Nothing is running",
                                       systemImage: place == .needs ? "checkmark.circle" : "moon.zzz",
                                       description: Text(place == .needs ? "Questions and approvals show up here." : "Sessions Claude is working on show up here."))
            } else if place == .needs {
                ScrollView {
                    LazyVStack(spacing: Space.md) {
                        ForEach(store.approvals) { a in
                            let s = store.session(a.session_id)
                            VStack(alignment: .leading, spacing: Space.sm) {
                                HStack {
                                    Text([store.project(s?.project_id)?.name ?? "Ask Bench", s?.title].compactMap { $0 }.joined(separator: " · "))
                                        .font(.system(size: TypeScale.sm, weight: .medium))
                                        .lineLimit(1)
                                    Spacer()
                                    Button("Open Session") { open(a.session_id) }.buttonStyle(.link)
                                }
                                ApprovalCard(approval: a, cwd: s?.cwd ?? "", busy: false) { body in
                                    Task { try? await store.answer(a, body) }
                                }
                            }
                            .padding(Space.md)
                            .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: theme.radius))
                        }
                    }
                    .frame(maxWidth: 860)
                    .padding(Space.xl)
                    .frame(maxWidth: .infinity)
                }
            } else {
                List(rows, id: \.0.id) { s, line in
                    Button { open(s.id) } label: {
                        SessionRowView(session: s, project: store.project(s.project_id)?.name ?? (s.project_id == nil ? "Ask Bench" : nil), detail: line)
                            .padding(.vertical, Space.xs)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .scrollContentBackground(.hidden)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bg)
        .navigationTitle(place == .needs ? "Needs You" : "Working")
    }
}
