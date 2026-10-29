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
            if let id = model.selection, store.session(id) != nil {
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
            Text(store.projects.isEmpty ? "Add a project from Bench in the browser to get started." : "Choose a session, or start a new one.")
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
