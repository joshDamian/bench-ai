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
        .sheet(isPresented: $model.showAddProject) {
            AddProjectSheet(store: store) { id in model.selection = "project:\(id)" }
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
            HStack {
                Button("Add Project…") { model.showAddProject = true }
                    .buttonStyle(CapsuleButton(kind: store.projects.isEmpty ? .primary : .outline))
                if !store.projects.isEmpty {
                    Button("New Session") { model.showNewSession = true }
                        .buttonStyle(CapsuleButton(kind: .primary))
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bg)
    }
}

/// Needs you and Working, as the Needs you board draws them: answerable cards, then
/// what finished recently.
struct PlaceView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let place: Place
    let open: (String) -> Void

    private func context(_ s: Session?, _ when: String?) -> String {
        [store.project(s?.project_id)?.name ?? "Ask Bench", s?.title, Format.ago(when)].compactMap { $0 }.joined(separator: " · ")
    }

    var body: some View {
        let working = store.sessions.filter { $0.status == "running" }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        let finished = store.sessions
            .filter { $0.status == "idle" && $0.archived_at == nil && Format.age($0.updated_at) < 3 * 86400 }
            .sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
            .prefix(6)
        let count = place == .needs ? store.approvals.count : working.count
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Text(place == .needs ? "Needs you" : "Working").font(.system(size: 15, weight: .semibold))
                if count > 0 {
                    let c = place == .needs ? theme.colors.warn : theme.colors.accent
                    Text("\(count)").font(.system(size: 11)).foregroundStyle(c)
                        .padding(.horizontal, 7).padding(.vertical, 1)
                        .overlay(Capsule().strokeBorder(c.opacity(0.4)))
                }
            }
            .padding(.horizontal, Space.xl)
            .padding(.top, 18)
            .padding(.bottom, Space.md)
            ScrollView {
                VStack(alignment: .leading, spacing: Space.lg) {
                    if place == .needs {
                        if store.approvals.isEmpty {
                            Text("Nothing needs you. Questions, plans and permission requests show up here.")
                                .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                        }
                        ForEach(store.approvals) { a in
                            let s = store.session(a.session_id)
                            ApprovalCard(approval: a, cwd: s?.cwd ?? "", sessionTitle: context(s, a.created_at ?? s?.updated_at),
                                         inbox: true, openSession: { open(a.session_id) }, busy: false) { body in
                                Task { try? await store.answer(a, body) }
                            }
                        }
                    } else {
                        if working.isEmpty {
                            Text("Nothing is running.").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                        }
                        VStack(spacing: 6) {
                            ForEach(working) { s in row(s, dot: s.status) }
                        }
                    }
                    if !finished.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Finished recently").font(.system(size: 12, weight: .semibold)).foregroundStyle(theme.colors.muted).padding(.bottom, 4)
                            ForEach(Array(finished)) { s in row(s, dot: "done") }
                        }
                        .padding(.top, Space.sm)
                    }
                }
                .frame(maxWidth: 760, alignment: .leading)
                .padding(.horizontal, 32)
                .padding(.top, Space.sm)
                .padding(.bottom, Space.xl)
                .frame(maxWidth: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(theme.colors.bg)
        .navigationTitle(place == .needs ? "Needs You" : "Working")
        .hideToolbarTitle()
    }

    private func row(_ s: Session, dot: String) -> some View {
        Button { open(s.id) } label: {
            HStack(spacing: Space.md) {
                if dot == "done" { Circle().fill(theme.colors.good).frame(width: 7, height: 7).frame(width: 13) }
                else { StatusDot(status: dot) }
                Text(s.title).font(.system(size: 13)).lineLimit(1)
                Spacer()
                Text(Format.ago(s.updated_at)).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
            }
            .padding(.horizontal, Space.md)
            .frame(height: 36)
            .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
