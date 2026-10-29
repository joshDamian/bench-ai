import SwiftUI

/// The menu bar window: what needs you, answerable in place, and what is working.
struct MenuBarView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: Space.md) {
            if let store = model.store {
                HStack {
                    Text("Bench").font(.headline)
                    Spacer()
                    Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 7, height: 7)
                }
                if store.approvals.isEmpty {
                    Text("Nothing needs you.").foregroundStyle(theme.colors.muted)
                } else {
                    ScrollView {
                        VStack(spacing: Space.sm) {
                            ForEach(store.approvals) { a in
                                let s = store.session(a.session_id)
                                ApprovalCard(
                                    approval: a, cwd: s?.cwd ?? "",
                                    sessionTitle: [store.project(s?.project_id)?.name, s?.title].compactMap { $0 }.joined(separator: " · "),
                                    compact: true, busy: false
                                ) { body in Task { try? await store.answer(a, body) } }
                            }
                        }
                    }
                    .frame(maxHeight: 420)
                }
                let working = store.sessions.filter { $0.status == "running" && $0.archived_at == nil }
                if !working.isEmpty {
                    Divider()
                    Text("Working").font(.system(size: TypeScale.xs, weight: .semibold)).foregroundStyle(theme.colors.muted)
                    ForEach(working) { s in
                        Button { open(s.id) } label: {
                            HStack { StatusDot(status: s.status); Text(s.title).lineLimit(1); Spacer() }
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                Text("Not connected").foregroundStyle(theme.colors.muted)
            }
            Divider()
            HStack {
                Button("Open Bench") { open(nil) }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(Space.md)
        .frame(width: 360)
        .background(theme.colors.bg)
    }

    private func open(_ sessionId: String?) {
        if let sessionId { model.selection = sessionId }
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
    }
}
