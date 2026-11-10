import SwiftUI

/// The menu bar window, as the Mac v2 board draws it: requests first, answerable in place,
/// then what's running, then tasks ready to merge.
struct MenuBarView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let store = model.store {
                if store.approvals.isEmpty {
                    HStack(spacing: Space.sm) {
                        Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 7, height: 7)
                        Text(store.connected ? "Nothing needs you" : "Connecting to \(Describe.host(store))…").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 10)
                } else {
                    ScrollView {
                        VStack(spacing: 6) { ForEach(store.approvals) { a in card(store, a) } }
                    }
                    .frame(maxHeight: 380)
                    .fixedSize(horizontal: false, vertical: true)
                }

                let running = store.sessions.filter { $0.status == "running" && $0.archived_at == nil }
                if !running.isEmpty {
                    label("Running")
                    ForEach(running) { s in
                        rowButton(s.id) {
                            MarkView(mark: .running)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(s.title).font(.system(size: 13)).lineLimit(1)
                                Text(store.project(s.project_id)?.name ?? "Ask Bench").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1)
                            }
                            Spacer()
                            if let since = store.runningSince[s.id] {
                                Text(since, style: .timer).font(.system(size: 11.5).monospacedDigit()).foregroundStyle(theme.colors.muted)
                            }
                            Button { Task { try? await store.api.interrupt(s.id) } } label: {
                                Image(systemName: "stop.fill").font(.system(size: 9)).frame(width: 22, height: 22)
                                    .background(theme.colors.fill(0.08), in: Circle())
                            }
                            .buttonStyle(.plain)
                            .help("Stop")
                            .accessibilityLabel("Stop \(s.title)")
                        }
                    }
                }

                let ready = store.tasks.filter { $0.state == "active" && Mark.of(task: $0, store: store) == .ready }
                if !ready.isEmpty {
                    label("Ready to merge")
                    ForEach(ready) { t in
                        rowButton("task:\(t.id)") {
                            MarkView(mark: .ready)
                            Text(t.title).font(.system(size: 13)).lineLimit(1)
                            Spacer()
                            Text(store.project(t.project_id)?.name ?? "").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1)
                        }
                    }
                }
            } else {
                Text("Not connected to a server").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted).padding(10)
            }
            Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1).padding(.vertical, 6).padding(.horizontal, 4)
            HStack(spacing: 4) {
                footerButton("New Session…") { model.newSession(); open(nil) }
                Spacer()
                footerButton("Open Bench") { open(nil) }
            }
        }
        .padding(6)
        .frame(width: 360)
    }

    @ViewBuilder
    private func card(_ store: BenchStore, _ a: Approval) -> some View {
        let s = store.session(a.session_id)
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(PanelText.title(a)).font(.system(size: 13, weight: .semibold))
                Text([store.project(s?.project_id)?.name ?? "Ask Bench", s?.title, Format.ago(a.created_at)].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1)
            }
            if let d = PanelText.detail(a, cwd: s?.cwd ?? "") {
                Text(d).font(BenchFont.mono(12)).foregroundStyle(theme.colors.wellText).lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10).padding(.vertical, 7)
                    .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: 7))
            }
            HStack(spacing: 6) {
                if PanelText.answerable(a) {
                    Button("Deny") { Task { try? await store.answer(a, ["allow": false, "message": ""]) } }
                        .buttonStyle(TonalButton(danger: true, height: 28))
                    Spacer()
                    Button("Open") { open(a.session_id) }.buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                    Button("Allow") { Task { try? await store.answer(a, ["allow": true]) } }
                        .buttonStyle(PrimaryButton(height: 28))
                } else if a.tool == "ExitPlanMode" {
                    Spacer()
                    Button("Open") { open(a.session_id) }.buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                    Button("Approve") { Task { try? await store.answer(a, ["allow": true, "mode": "acceptEdits"]) } }
                        .buttonStyle(PrimaryButton(height: 28))
                } else if a.tool == "AskUserQuestion" {
                    QuickAnswer(approval: a, store: store)
                } else {
                    Spacer()
                    Button("Answer in Bench") { open(a.session_id) }.buttonStyle(PrimaryButton(height: 28))
                }
            }
        }
        .padding(12)
        .background(theme.colors.warn.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(theme.colors.warn.opacity(0.3)))
    }

    private func label(_ s: String) -> some View {
        Text(s).font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.colors.muted)
            .padding(.horizontal, 10).padding(.top, 10).padding(.bottom, 2)
    }

    private func rowButton<C: View>(_ id: String, @ViewBuilder _ content: () -> C) -> some View {
        Button { open(id) } label: {
            HStack(spacing: 10) { content() }
                .padding(.horizontal, 8).padding(.vertical, 6)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func footerButton(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 13)).padding(.horizontal, 10).frame(height: 28).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func open(_ id: String?) {
        if let id { model.selection = id }
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
    }
}


/// A question answered in a line: its options as buttons when it has them, or a reply field.
struct QuickAnswer: View {
    @Environment(\.theme) private var theme
    let approval: Approval
    let store: BenchStore
    @State private var reply = ""

    private var question: JSONValue? { approval.input["questions"].array.first }

    var body: some View {
        let q = question?["question"].string ?? ""
        let options = question?["options"].array.compactMap { $0["label"].string } ?? []
        VStack(alignment: .leading, spacing: 6) {
            if !options.isEmpty {
                FlowButtons(options: options) { send(q, $0) }
            }
            HStack(spacing: 6) {
                TextField("", text: $reply, prompt: Text(options.isEmpty ? "Your answer" : "Or type an answer"))
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { send(q, reply) }
                Button("Send") { send(q, reply) }.buttonStyle(PrimaryButton(height: 26)).disabled(reply.isEmpty)
            }
        }
    }

    private func send(_ q: String, _ answer: String) {
        guard !answer.isEmpty else { return }
        Task { try? await store.answer(approval, ["allow": true, "answers": [q: answer]]) }
    }
}

private struct FlowButtons: View {
    let options: [String]
    let pick: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(options, id: \.self) { o in
                Button(o) { pick(o) }.buttonStyle(TonalButton(height: 26)).lineLimit(1)
            }
        }
    }
}
