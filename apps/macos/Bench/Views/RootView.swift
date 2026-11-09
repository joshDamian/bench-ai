import CoreSpotlight
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
        // The board's window: an inset, rounded sidebar pane, then the place on the right.
        // No title bar; the traffic lights sit inside the sidebar pane.
        HStack(spacing: 0) {
            if model.showSidebar {
                Sidebar(store: store, selection: $model.selection)
                    .frame(width: 256)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                    .background(theme.colors.surface3.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(theme.colors.fill(0.07)))
                    .padding(8)
                    .transition(.move(edge: .leading).combined(with: .opacity))
            }
            VStack(spacing: 0) {
                if !model.showSidebar { Color.clear.frame(height: 0) }
                if model.tabs.count > 1 { TabStrip(store: store) }
                if let side = model.split, store.session(side) != nil, side != model.selection {
                    HSplitView {
                        detail.frame(minWidth: 380)
                        SplitPane(store: store, id: side).frame(minWidth: 380)
                    }
                } else {
                    detail
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.leading, model.showSidebar ? 0 : 112)
        }
        .overlay(alignment: .topLeading) {
            if !model.showSidebar {
                Button { withAnimation(.snappy) { model.showSidebar = true } } label: {
                    Image(systemName: "sidebar.left").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                        .frame(width: 28, height: 26).contentShape(Rectangle())
                }
                .buttonStyle(.plain).help("Show Sidebar (⌃⌘S)")
                .padding(.leading, 78).padding(.top, 14)
            }
        }
        .overlay(alignment: .topLeading) {
            if !model.showSidebar {
                TrafficLights().padding(.leading, 18).padding(.top, 21)
            }
        }
        .background(theme.colors.bg)
        .ignoresSafeArea()
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
            NewSessionSheet(store: store, project: model.newSessionProject) { id in model.selection = id }
                .environment(\.theme, theme)
        }
        .sheet(isPresented: $model.showAddProject) {
            AddProjectSheet(store: store) { id in model.selection = "project:\(id)" }
                .environment(\.theme, theme)
        }
        .sheet(isPresented: $model.showQuickOpen) {
            CommandPalette(store: store)
                .environment(\.theme, theme)
        }
        .onAppear {
            model.panel.hide()
            // Open where you left off.
            if model.selection == nil, let last = UserDefaults.standard.string(forKey: "lastSelection") { model.selection = last }
            if let sel = model.selection, !model.tabs.contains(sel) { model.tabs.append(sel) }
        }
        .onChange(of: model.selection) { old, v in
            if let v { UserDefaults.standard.set(v, forKey: "lastSelection") }
            model.follow(old, v)
        }
        // A session picked in Spotlight.
        .onContinueUserActivity(CSSearchableItemActionType) { a in
            if let id = a.userInfo?[CSSearchableItemActivityIdentifier] as? String { model.selection = id }
        }
    }

    @ViewBuilder private var detail: some View {
        if let id = model.selection, let place = Place(rawValue: id) {
            PlaceView(store: store, place: place)
        } else if let id = model.selection, id.hasPrefix("project:") {
            let pid = String(id.dropFirst("project:".count))
            ProjectView(store: store, projectId: pid).id(pid)
        } else if let id = model.selection, id.hasPrefix("task:") {
            let tid = String(id.dropFirst("task:".count))
            TaskView(store: store, taskId: tid).id(tid)
        } else if let id = model.selection, let s = store.session(id) {
            if let t = s.task_id, store.task(t) != nil {
                TaskView(store: store, taskId: t, sessionId: id).id(t)
            } else {
                SessionView(store: store, sessionId: id).id(id)
            }
        } else {
            EmptyDetail(store: store)
        }
    }
}

/// First run, or nothing chosen: one thing to do next.
private struct EmptyDetail: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore

    var body: some View {
        let empty = store.projects.isEmpty
        VStack(spacing: 18) {
            Text(empty ? "No sessions yet" : "Nothing open").font(.system(size: 20, weight: .semibold))
            Text(empty ? "Add a project from your server, then start a session in it. Or ask Bench something without a project."
                       : "Choose a session in the sidebar, or start a new one.")
                .font(.system(size: 13.5))
                .foregroundStyle(theme.colors.muted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 380)
            HStack(spacing: 10) {
                if empty {
                    Button("Ask Bench") { model.newSession() }.buttonStyle(TonalButton(height: 34))
                    Button("Add Project…") { model.showAddProject = true }.buttonStyle(PrimaryButton(height: 34))
                } else {
                    Button("Add Project…") { model.showAddProject = true }.buttonStyle(TonalButton(height: 34))
                    Button("New Session") { model.newSession() }.buttonStyle(PrimaryButton(height: 34))
                }
            }
            .padding(.top, 6)
            Text("⌘N new session · ⌘K go anywhere").font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.top, Space.sm)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bg)
    }
}

/// Needs you, Running and All sessions.
struct PlaceView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    let place: Place

    var body: some View {
        let live = store.sessions.filter { $0.archived_at == nil }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        VStack(spacing: 0) {
        PaneHeader { HeaderTitle(title: place.title, sub: "") } trailing: { EmptyView() }
        ScrollView {
            VStack(alignment: .leading, spacing: Space.lg) {
                switch place {
                case .needs:
                    if store.approvals.isEmpty {
                        Text("Nothing needs you. Questions, plans and permission requests show up here.")
                            .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                    }
                    ForEach(store.approvals) { a in
                        let s = store.session(a.session_id)
                        ApprovalCard(approval: a, cwd: s?.cwd ?? "",
                                     sessionTitle: [store.project(s?.project_id)?.name ?? "Ask Bench", s?.title, Format.ago(a.created_at)].compactMap { $0 }.joined(separator: " · "),
                                     inbox: true, openSession: { model.selection = a.session_id }, busy: false) { body in
                            Task { try? await store.answer(a, body) }
                        }
                    }
                case .running:
                    let running = live.filter { $0.status == "running" }
                    if running.isEmpty { Text("Nothing is running.").font(.system(size: 13)).foregroundStyle(theme.colors.muted) }
                    list(running)
                case .all:
                    list(live)
                }
            }
            .frame(maxWidth: 760, alignment: .leading)
            .padding(.horizontal, 32)
            .padding(.vertical, Space.lg)
            .frame(maxWidth: .infinity)
        }
        }
        .background(theme.colors.bg)
        .navigationTitle(place.title)
    }

    private func list(_ sessions: [Session]) -> some View {
        Group2 {
            ForEach(Array(sessions.enumerated()), id: \.element.id) { i, s in
                Button { model.selection = s.id } label: {
                    HStack(spacing: Space.sm) {
                        MarkView(mark: Mark.of(s, store: store))
                        Text(s.title).font(.system(size: 13)).lineLimit(1)
                        Text(store.project(s.project_id)?.name ?? "Ask Bench").font(.system(size: 12)).foregroundStyle(theme.colors.muted).lineLimit(1)
                        Spacer(minLength: Space.sm)
                        Text(Describe.session(s, store: store)).font(.system(size: 12)).foregroundStyle(theme.colors.muted).fixedSize()
                    }
                    .padding(.horizontal, 12)
                    .frame(height: 38)
                    .overlay(alignment: .top) { if i > 0 { Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1) } }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .contextMenu { SessionMenu(store: store, session: s) }
            }
        }
    }
}

extension View {
    /// The header draws its own title; the window keeps one for the Window menu.
    @ViewBuilder func hideToolbarTitle() -> some View {
        if #available(macOS 15.0, *) { toolbar(removing: .title) } else { self }
    }
}
