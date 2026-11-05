import QuickLook
import MarkdownUI
import SwiftUI
import UniformTypeIdentifiers

/// A session, as the Mac v2 Session board draws it: the transcript in the middle with a
/// floating composer, model and permissions in the toolbar, the inspector (⌘J) on the
/// right and the terminal (⇧⌘T) underneath. Inside a task, the task draws the toolbar.
struct SessionView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    let store: BenchStore
    let sessionId: String
    var embedded = false
    @State private var model: SessionModel
    @State private var files: SessionFiles
    @State private var draft = ""
    @State private var follow = true
    @State private var dropTargeted = false
    @FocusState private var composerFocused: Bool

    init(store: BenchStore, sessionId: String, embedded: Bool = false) {
        self.embedded = embedded
        self.store = store
        self.sessionId = sessionId
        _model = State(initialValue: SessionModel(sessionId: sessionId, store: store))
        _files = State(initialValue: SessionFiles(api: store.api, sessionId: sessionId))
    }

    var body: some View {
        @Bindable var app = app
        let session = model.session
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                if !embedded {
                    PaneHeader {
                        HeaderTitle(title: session?.title ?? "Session", sub: subtitle(session))
                    } trailing: {
                        if let session {
                            ModelMenu(store: store, session: session, capsule: true)
                            ModeMenu(store: store, session: session)
                        }
                        Spacer().frame(width: Space.sm)
                        HeaderToggle(icon: "terminal", help: "Terminal (⇧⌘T)", on: $app.showTerminal)
                        HeaderToggle(icon: "sidebar.right", help: "Inspector (⌘J)", on: $app.showInspector)
                    }
                }
                if !embedded && app.showTerminal {
                    VSplitView {
                        conversation.frame(minHeight: 260)
                        RemoteTerminalView(store: store, query: "session=\(BenchAPI.enc(sessionId))&shell=1", place: session?.cwd ?? "Shell")
                            .frame(minHeight: 140, idealHeight: 250)
                    }
                } else {
                    conversation
                }
            }
            if !embedded && app.showInspector {
                Rectangle().fill(theme.colors.fill(0.06)).frame(width: 1)
                SessionInspector(store: store, sessionId: sessionId, files: files)
                    .frame(width: 340)
                    .transition(.move(edge: .trailing))
            }
        }
        .animation(.snappy, value: app.showInspector)
        .navigationTitle(session?.title ?? "Session")
        .environment(files)
        .sheet(item: Binding(get: { files.reading }, set: { files.reading = $0 })) { r in
            MarkdownViewer(reading: r, files: files).environment(\.theme, theme)
        }
        .quickLookPreview(Binding(get: { files.previewing }, set: { files.previewing = $0 }))
        .alert("Something went wrong", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .onAppear { model.open(); composerFocused = true }
        .onDisappear { model.close() }
        .task(id: model.rows.count) {
            let replies: [String] = model.blocks.compactMap { (b: Block) -> String? in
                if case .turn(_, _, let reply, _, _, _) = b { return reply }
                return nil
            }
            await files.stat(replies)
        }
    }

    private var conversation: some View {
        let session = model.session
        let cwd = session?.cwd ?? ""
        let approvals = model.approvals
        let running = session?.status == "running"
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 22) {
                    if model.hasOlder {
                        Button("Show earlier messages") { Task { await model.loadOlder() } }
                            .buttonStyle(.link)
                            .frame(maxWidth: .infinity)
                    }
                    if model.loading && model.rows.isEmpty {
                        ProgressView().frame(maxWidth: .infinity).padding(Space.xl)
                    } else if model.rows.isEmpty {
                        Text("Nothing here yet. Tell Claude what to work on.")
                            .foregroundStyle(theme.colors.muted)
                            .frame(maxWidth: .infinity)
                            .padding(Space.xl)
                    }
                    let blocks = model.blocks
                    ForEach(blocks) { b in
                        switch b {
                        case .user(let r), .note(let r): RowView(row: r, cwd: cwd, model: model).id(b.id)
                        case .turn(_, let steps, let reply, let label, let ok, let cost):
                            VStack(alignment: .leading, spacing: Space.md) {
                                TurnView(steps: steps, reply: reply, label: label, ok: ok, cost: cost,
                                         live: running && b.id == blocks.last?.id, cwd: cwd, model: model)
                                ArtifactCards(reply: reply)
                            }
                            .id(b.id)
                        }
                    }
                    if !model.streamText.isEmpty || !model.streamThinking.isEmpty {
                        VStack(alignment: .leading, spacing: Space.xs) {
                            if model.streamText.isEmpty {
                                Text("Thinking…").foregroundStyle(theme.colors.muted).font(.system(size: TypeScale.sm))
                            } else {
                                BenchMarkdown(text: model.streamText)
                            }
                        }
                        .id("stream")
                    }
                    if let p = session?.pause {
                        Text(p.message ?? "Paused by the usage limit")
                            .font(.system(size: TypeScale.sm))
                            .foregroundStyle(theme.colors.warn)
                    }
                    if let a = approvals.first {
                        VStack(alignment: .leading, spacing: Space.xs) {
                            if approvals.count > 1 {
                                Text("1 of \(approvals.count) waiting").font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted)
                            }
                            ApprovalCard(approval: a, cwd: cwd, busy: model.busyApprovals.contains(a.id)) { body in model.answer(a, body) }
                                .id(a.id)
                        }
                        .id("approval")
                    }
                    Color.clear.frame(height: approvals.isEmpty ? 150 : 24).id("bottom")
                        .onAppear { follow = true }
                        .onDisappear { follow = false }
                }
                .frame(maxWidth: 700)
                .padding(.horizontal, 40)
                .padding(.top, Space.md)
                .frame(maxWidth: .infinity)
            }
            .scrollContentBackground(.hidden)
            .background(theme.colors.bg)
            .onChange(of: model.rows.count) { _, _ in if follow { proxy.scrollTo("bottom", anchor: .bottom) } }
            .onChange(of: model.streamText.count / 120) { _, _ in if follow { proxy.scrollTo("bottom", anchor: .bottom) } }
            .onChange(of: approvals.first?.id) { _, _ in withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
            .onChange(of: model.loading) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            .overlay(alignment: .bottom) {
                if approvals.isEmpty {
                    Composer(model: model, draft: $draft, running: running, embedded: embedded, focused: $composerFocused)
                        .padding(.top, 28)
                        .background(alignment: .bottom) {
                            VStack(spacing: 0) {
                                LinearGradient(colors: [theme.colors.bg.opacity(0), theme.colors.bg], startPoint: .top, endPoint: .bottom).frame(height: 40)
                                theme.colors.bg
                            }
                            .allowsHitTesting(false)
                        }
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if !follow {
                    Button { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } } label: {
                        Image(systemName: "arrow.down").font(.system(size: 12, weight: .semibold)).frame(width: 30, height: 30)
                            .background(theme.colors.surface3, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, Space.lg)
                    .padding(.bottom, approvals.isEmpty ? 150 : Space.lg)
                    .help("Jump to latest")
                }
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
            for p in providers {
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    if let url { Task { @MainActor in model.attach([url]) } }
                }
            }
            return true
        }
        .overlay {
            if dropTargeted {
                RoundedRectangle(cornerRadius: theme.radius)
                    .strokeBorder(theme.colors.accent, style: StrokeStyle(lineWidth: 2, dash: [6]))
                    .padding(Space.sm)
                    .allowsHitTesting(false)
            }
        }
    }

    private func subtitle(_ s: Session?) -> String {
        guard let s else { return "" }
        let project = store.project(s.project_id)?.name ?? (s.project_id == nil ? "Ask Bench" : nil)
        let branch = store.task(s.task_id)?.branch
        return [project, branch].compactMap { $0 }.joined(separator: " · ")
    }
}

struct Composer: View {
    @Environment(\.theme) private var theme
    let model: SessionModel
    @Binding var draft: String
    let running: Bool
    var embedded = false
    var focused: FocusState<Bool>.Binding
    @State private var importing = false

    @State private var pick = 0

    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.attachments.contains { $0.ref != nil } }

    /// Typing "/name" with no space yet lists the matching commands.
    private var matches: [SlashCommand] {
        guard draft.hasPrefix("/"), !draft.contains(" "), !draft.contains("\n") else { return [] }
        let q = draft.dropFirst().lowercased()
        let all = model.commands.filter { q.isEmpty || $0.name.lowercased().hasPrefix(q) }
        let rest = model.commands.filter { !q.isEmpty && !$0.name.lowercased().hasPrefix(q) && $0.name.lowercased().contains(q) }
        return Array((all + rest).prefix(8))
    }

    private func choose(_ c: SlashCommand) {
        draft = "/\(c.name) "
        pick = 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            let list = matches
            if !list.isEmpty {
                VStack(alignment: .leading, spacing: 1) {
                    ForEach(Array(list.enumerated()), id: \.offset) { i, c in
                        Button { choose(c) } label: {
                            HStack(spacing: 10) {
                                Text("/\(c.name)").font(.system(size: 13, design: .monospaced)).fixedSize()
                                Text(c.description ?? "").font(.system(size: 12)).foregroundStyle(theme.colors.muted).lineLimit(1)
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 10).frame(height: 30)
                            .background(i == min(pick, list.count - 1) ? theme.colors.fill(0.1) : .clear, in: RoundedRectangle(cornerRadius: 8))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(6)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                .background(theme.colors.surface3.opacity(0.6), in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(theme.colors.fill(0.1)))
            }
            if !model.suggestion.isEmpty && !running && draft.isEmpty {
                Button(model.suggestion) { draft = model.suggestion }
                    .buttonStyle(TonalButton(height: 26))
                    .lineLimit(1)
            }
            VStack(alignment: .leading, spacing: Space.sm) {
                if !model.attachments.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: Space.sm) {
                            ForEach(model.attachments) { a in
                                HStack(spacing: Space.xs) {
                                    if a.ref == nil && a.error == nil { ProgressView(value: a.progress).frame(width: 36) }
                                    Text(a.error.map { "\(a.name): \($0)" } ?? a.name)
                                        .foregroundStyle(a.error == nil ? theme.colors.text : theme.colors.bad)
                                        .lineLimit(1)
                                    Button { model.remove(a.id) } label: { Image(systemName: "xmark.circle.fill") }
                                        .buttonStyle(.plain)
                                        .foregroundStyle(theme.colors.muted)
                                }
                                .font(.system(size: TypeScale.sm))
                                .padding(.horizontal, Space.sm)
                                .padding(.vertical, Space.xs)
                                .background(theme.colors.fill(0.08), in: Capsule())
                            }
                        }
                    }
                }
                GrowingEditor(text: $draft, placeholder: running ? "Queue a message" : "Reply to Claude, / for commands",
                              size: TypeScale.md, minLines: 2, maxLines: 10) {
                    if let c = matches[safe: pick] { choose(c) } else { send() }
                }
                    .focused(focused)
                    .onChange(of: draft) { _, _ in pick = 0 }
                    .onKeyPress(.downArrow) { guard !matches.isEmpty else { return .ignored }; pick = min(pick + 1, matches.count - 1); return .handled }
                    .onKeyPress(.upArrow) { guard !matches.isEmpty else { return .ignored }; pick = max(pick - 1, 0); return .handled }
                    .onKeyPress(.tab) { guard let c = matches[safe: pick] else { return .ignored }; choose(c); return .handled }
                    .onKeyPress(.escape) { guard !matches.isEmpty else { return .ignored }; draft = ""; return .handled }
                HStack(spacing: Space.sm) {
                    Button { importing = true } label: { Image(systemName: "plus").font(.system(size: 13, weight: .medium)).frame(width: 26, height: 26) }
                        .buttonStyle(.plain)
                        .foregroundStyle(theme.colors.muted)
                        .help("Attach Files")
                    if embedded, let s = model.session { ModelMenu(store: model.store, session: s, capsule: false) }
                    Text(running ? "Claude is working. Messages queue until it's done." : "")
                        .font(.system(size: 11.5))
                        .foregroundStyle(theme.colors.muted)
                        .lineLimit(1)
                    Spacer()
                    if running {
                        Button(action: model.interrupt) {
                            RoundedRectangle(cornerRadius: 2).fill(theme.colors.bg).frame(width: 10, height: 10)
                                .frame(width: 30, height: 30)
                                .background(theme.colors.text, in: Circle())
                        }
                        .buttonStyle(.plain)
                        .keyboardShortcut(".", modifiers: .command)
                        .help("Stop (⌘.)")
                    }
                    if !running || canSend {
                        Button(action: send) {
                            Image(systemName: "arrow.up").font(.system(size: 12, weight: .bold))
                                .foregroundStyle(theme.colors.onAccent)
                                .frame(width: 30, height: 30)
                                .background(canSend ? theme.colors.accent : theme.colors.accent.opacity(0.35), in: Circle())
                        }
                        .buttonStyle(.plain)
                        .keyboardShortcut(.return, modifiers: .command)
                        .disabled(!canSend)
                        .help("Send (Return)")
                    }
                }
            }
            .padding(.leading, 18)
            .padding([.top, .trailing], Space.md)
            .padding(.bottom, 10)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22))
            .background(theme.colors.surface3.opacity(0.6), in: RoundedRectangle(cornerRadius: 22))
            .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(theme.colors.fill(0.1)))
            .shadow(color: .black.opacity(0.22), radius: 18, y: 8)
        }
        .frame(maxWidth: 720)
        .padding(.horizontal, 40)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r { model.attach(urls) }
        }
    }

    private func send() {
        let text = draft
        guard canSend else { return }
        draft = ""
        Task { if !(await model.send(text)) { draft = text } }
    }
}

/// "Opus · High": the session's model and effort, chosen in a popover as the board draws
/// it: models grouped by provider with a check on the current one, effort underneath.
struct ModelMenu: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let session: Session
    var capsule = true
    @State private var open = false

    var body: some View {
        Button { open.toggle() } label: {
            HStack(spacing: 6) {
                Text(label).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
            }
            .foregroundStyle(capsule ? theme.colors.text : theme.colors.muted)
            .modifier(CapsuleIf(on: capsule))
        }
        .buttonStyle(.plain)
        .help("Model and effort")
        .popover(isPresented: $open, arrowEdge: .bottom) {
            ModelPopover(store: store, session: session).environment(\.theme, theme)
        }
    }

    private var label: String {
        let m = store.providers.flatMap(\.models).first { $0.id == session.model_id }
        let name = m?.label ?? store.providers.first { $0.id == session.provider_id }?.name ?? "Default model"
        guard let e = session.effort.flatMap(Effort.init(rawValue:)) else { return name }
        return "\(name) · \(e.label)"
    }
}

private struct CapsuleIf: ViewModifier {
    let on: Bool
    func body(content: Content) -> some View {
        if on { content.toolbarCapsule() } else { content.font(.system(size: TypeScale.xs)) }
    }
}

private struct ModelPopover: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let session: Session
    @State private var effort: String = ""

    var body: some View {
        let providers = store.providers.filter { $0.enabled && $0.ready != false && !$0.models.filter(\.enabled).isEmpty }
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 1) {
                    ForEach(providers) { p in
                        Text(p.name).font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.colors.muted)
                            .padding(.horizontal, 10).padding(.top, 8).padding(.bottom, 3)
                        ForEach(p.models.filter(\.enabled)) { m in
                            let on = m.id == session.model_id
                            Button { set(["providerId": p.id, "modelId": m.id]) } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).opacity(on ? 1 : 0).frame(width: 14)
                                    Text(m.label).font(.system(size: 13))
                                    Spacer()
                                }
                                .padding(.horizontal, 10)
                                .frame(height: 28)
                                .background(on ? theme.colors.fill(0.08) : .clear, in: RoundedRectangle(cornerRadius: 7))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(6)
            }
            .frame(maxHeight: 360)
            Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1).padding(.horizontal, 10)
            VStack(alignment: .leading, spacing: 8) {
                Text("Effort").font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.colors.muted)
                Segments(options: [("", "Default")] + Effort.allCases.map { ($0.rawValue, $0 == .xhigh ? "XHigh" : $0.label) },
                         selection: Binding(get: { effort }, set: { v in effort = v; set(["effort": v.isEmpty ? NSNull() : v]) }), fill: true)
                Text("Applies from your next message.").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
            }
            .padding(14)
        }
        .frame(width: 340)
        .onAppear { effort = session.effort ?? "" }
    }

    private func set(_ body: [String: Any]) {
        Task { try? await store.api.patchSession(session.id, body) }
    }
}

/// The session's permission mode, as a toolbar capsule.
struct ModeMenu: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let session: Session

    var body: some View {
        Menu {
            ForEach(Mode.allCases) { m in
                Button { Task { try? await store.api.setMode(session.id, m) } } label: {
                    if m == session.mode { Label(m.label, systemImage: "checkmark") } else { Text(m.label) }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(session.mode.label)
                Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
            }
            .foregroundStyle(theme.colors.text)
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .tint(theme.colors.text)
        .fixedSize()
        .toolbarCapsule()
        .help("Permissions")
    }
}

extension View {
    /// The capsule older views put their controls in.
    func pill(_ theme: BenchThemeSpec) -> some View {
        background(theme.colors.fill(0.08), in: Capsule())
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
