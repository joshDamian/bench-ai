import SwiftUI

/// The menu bar window, as the Menu bar board draws it: what needs you, answerable in
/// place, then what is working.
struct MenuBarView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let store = model.store {
                HStack(spacing: Space.sm) {
                    Text("Needs you").font(.system(size: 13, weight: .semibold))
                    Spacer()
                    Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 6, height: 6)
                    Text(host(store)).font(.system(size: 11)).foregroundStyle(theme.colors.muted).lineLimit(1)
                }
                .padding(.horizontal, Space.lg)
                .padding(.top, 14)
                .padding(.bottom, 10)

                if store.approvals.isEmpty {
                    Text("Nothing needs you")
                        .font(.system(size: 12.5))
                        .foregroundStyle(theme.colors.muted)
                        .padding(.horizontal, Space.lg)
                        .padding(.bottom, Space.sm)
                } else {
                    ScrollView {
                        VStack(spacing: Space.sm) {
                            ForEach(store.approvals) { a in card(store, a) }
                        }
                        .padding(.horizontal, 10)
                    }
                    .frame(maxHeight: 360)
                    .fixedSize(horizontal: false, vertical: true)
                }

                let working = store.sessions.filter { $0.status == "running" && $0.archived_at == nil }
                if !working.isEmpty {
                    Text("Working")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(theme.colors.muted)
                        .padding(.horizontal, Space.lg)
                        .padding(.top, Space.lg)
                        .padding(.bottom, 6)
                    VStack(spacing: 1) {
                        ForEach(working) { s in
                            Button { open(s.id) } label: {
                                HStack(spacing: 10) {
                                    StatusDot(status: s.status)
                                    Text(s.title).font(.system(size: 13)).lineLimit(1)
                                    Spacer()
                                    Text(Format.ago(s.updated_at)).font(.system(size: 11)).foregroundStyle(theme.colors.muted)
                                }
                                .padding(.horizontal, Space.sm)
                                .frame(height: 32)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.bottom, Space.sm)
                }
            } else {
                Text("Not connected to a server")
                    .font(.system(size: 12.5))
                    .foregroundStyle(theme.colors.muted)
                    .padding(Space.lg)
            }
            Rectangle().fill(theme.colors.text.opacity(0.06)).frame(height: 1).padding(.top, Space.sm)
            HStack(spacing: 4) {
                footerButton("New Session…") { model.showNewSession = true; open(nil) }
                Spacer()
                footerButton("Open Bench") { open(nil) }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, Space.sm)
        }
        .frame(width: 360)
        .background(theme.colors.surface2)
    }

    @ViewBuilder
    private func card(_ store: BenchStore, _ a: Approval) -> some View {
        let s = store.session(a.session_id)
        let permission = a.tool != "ExitPlanMode" && a.tool != "AskUserQuestion"
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text([store.project(s?.project_id)?.name ?? "Ask Bench", s?.title].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 11)).foregroundStyle(theme.colors.muted).lineLimit(1)
                Text(a.tool == "ExitPlanMode" ? "Claude has a plan" : a.tool == "AskUserQuestion" ? "Claude is asking" : ApprovalCard.ask(a))
                    .font(.system(size: 13, weight: .semibold))
            }
            if a.tool == "Bash", let c = a.input["command"].string {
                Text(c).font(.system(size: 12, design: .monospaced)).foregroundStyle(theme.colors.wellText).lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10).padding(.vertical, Space.sm)
                    .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: 6))
            } else if permission, let p = a.input["file_path"].string {
                Text(Transcript.rel(p, cwd: s?.cwd ?? "")).font(.system(size: 12, design: .monospaced)).foregroundStyle(theme.colors.muted).lineLimit(1)
            }
            HStack(spacing: 6) {
                if permission {
                    Button("Allow") { Task { try? await store.answer(a, ["allow": true]) } }
                        .buttonStyle(SmallCapsule(kind: .primary))
                    Button("Deny") { Task { try? await store.answer(a, ["allow": false, "message": ""]) } }
                        .buttonStyle(SmallCapsule(kind: .danger))
                }
                Spacer()
                Button("Open") { open(a.session_id) }
                    .buttonStyle(.plain)
                    .font(.system(size: 12))
                    .foregroundStyle(theme.colors.muted)
            }
        }
        .padding(Space.md)
        .background(theme.colors.text.opacity(0.04), in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(theme.colors.warn.opacity(0.35)))
    }

    private func footerButton(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 12.5)).padding(.horizontal, Space.sm).frame(height: 28).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func host(_ store: BenchStore) -> String {
        URLComponents(string: store.api.config.baseURL)?.host?.split(separator: ".").first.map(String.init) ?? ""
    }

    private func open(_ sessionId: String?) {
        if let sessionId { model.selection = sessionId }
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
    }
}

/// The menu bar's smaller pill buttons.
struct SmallCapsule: ButtonStyle {
    @Environment(\.theme) private var theme
    let kind: CapsuleButton.Kind

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: kind == .primary ? .semibold : .regular))
            .foregroundStyle(kind == .primary ? theme.colors.onAccent : kind == .danger ? theme.colors.bad : theme.colors.text)
            .padding(.horizontal, kind == .primary ? 14 : 12)
            .frame(height: 26)
            .background(kind == .primary ? theme.colors.accent : .clear, in: Capsule())
            .overlay { if kind != .primary { Capsule().strokeBorder(theme.colors.lineStrong) } }
            .opacity(configuration.isPressed ? 0.75 : 1)
            .contentShape(Capsule())
    }
}
