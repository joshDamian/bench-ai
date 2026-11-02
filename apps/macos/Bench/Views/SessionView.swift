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
        Group {
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
        .navigationTitle(session?.title ?? "Session")
        .hideToolbarTitle()
        .toolbar { if !embedded { toolbar(session) } }
        .inspector(isPresented: embedded ? .constant(false) : $app.showInspector) {
            SessionInspector(store: store, sessionId: sessionId, files: files)
                .inspectorColumnWidth(min: 280, ideal: 340, max: 480)
        }
        .environment(files)
        .alert("Something went wrong", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .onAppear { model.open(); composerFocused = true }
        .onDisappear { model.close() }
        .task(id: model.rows.count) {
            await files.stat(Transcript.blocks(model.rows).compactMap { if case .turn(_, _, let reply, _, _, _) = $0 { return reply } else { return nil } })
        }
    }

    @ToolbarContentBuilder
    private func toolbar(_ session: Session?) -> some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            VStack(alignment: .leading, spacing: 1) {
                Text(session?.title ?? "Session").font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                Text(subtitle(session)).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1)
            }
            .padding(.leading, Space.xs)
        }
        ToolbarItemGroup(placement: .primaryAction) {
            if let session {
                ModelMenu(store: store, session: session, capsule: true)
                ModeMenu(store: store, session: session)
            }
            Spacer().frame(width: Space.sm)
            Toggle(isOn: Binding(get: { app.showTerminal }, set: { app.showTerminal = $0 })) {
                Label("Terminal", systemImage: "terminal")
            }
            .help("Terminal (⇧⌘T)")
            Toggle(isOn: Binding(get: { app.showInspector }, set: { app.showInspector = $0 })) {
                Label("Inspector", systemImage: "sidebar.right")
            }
            .help("Inspector (⌘J)")
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
                    let blocks = Transcript.blocks(model.rows)
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

    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.attachments.contains { $0.ref != nil } }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
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
                TextField("", text: $draft, prompt: Text(running ? "Queue a message" : "Reply to Claude, / for commands"), axis: .vertical)
                    .textFieldStyle(.plain)
                    .font(.system(size: TypeScale.md))
                    .lineLimit(2...10)
                    .focused(focused)
                    .onSubmit(send)
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

/// "Opus · High": the session's model and effort. Applies from the next message.
struct ModelMenu: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let session: Session
    var capsule = true

    var body: some View {
        let menu = Menu {
            ForEach(store.providers.filter { $0.enabled }) { p in
                Section(p.ready == false ? "\(p.name) (not set up)" : p.name) {
                    ForEach(p.models.filter(\.enabled)) { m in
                        Button { set(["providerId": p.id, "modelId": m.id]) } label: {
                            if m.id == session.model_id { Label(m.label, systemImage: "checkmark") } else { Text(m.label) }
                        }
                        .disabled(p.ready == false)
                    }
                }
            }
            Divider()
            Section("Effort") {
                Button { set(["effort": NSNull()]) } label: {
                    if session.effort == nil { Label("Model default", systemImage: "checkmark") } else { Text("Model default") }
                }
                ForEach(Effort.allCases) { e in
                    Button { set(["effort": e.rawValue]) } label: {
                        if session.effort == e.rawValue { Label(e.label, systemImage: "checkmark") } else { Text(e.label) }
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(label)
                Image(systemName: "chevron.down").font(.system(size: 8, weight: .semibold))
            }
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Model and effort. Applies from your next message.")
        if capsule { menu.toolbarCapsule() } else { menu.font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted) }
    }

    /// "Opus · High"
    private var label: String {
        let m = store.providers.flatMap(\.models).first { $0.id == session.model_id }
        let name = m?.label ?? store.providers.first { $0.id == session.provider_id }?.name ?? "Default model"
        guard let e = session.effort.flatMap(Effort.init(rawValue:)) else { return name }
        return "\(name) · \(e.label)"
    }

    private func set(_ body: [String: Any]) {
        Task { try? await store.api.patchSession(session.id, body) }
    }
}

/// The session's permission mode, as a toolbar capsule.
struct ModeMenu: View {
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
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
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
