import MarkdownUI
import SwiftUI
import UniformTypeIdentifiers

struct SessionView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let sessionId: String
    @State private var model: SessionModel
    @State private var draft = ""
    @State private var follow = true
    @State private var dropTargeted = false
    @State private var renaming = false
    @State private var newTitle = ""
    @FocusState private var composerFocused: Bool

    init(store: BenchStore, sessionId: String) {
        self.store = store
        self.sessionId = sessionId
        _model = State(initialValue: SessionModel(sessionId: sessionId, store: store))
    }

    var body: some View {
        let session = model.session
        let cwd = session?.cwd ?? ""
        let approvals = model.approvals
        let running = session?.status == "running"

        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: Space.md) {
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
                        ForEach(model.rows) { row in
                            RowView(row: row, cwd: cwd, model: model).id(row.id)
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
                        Color.clear.frame(height: 1).id("bottom")
                            .onAppear { follow = true }
                            .onDisappear { follow = false }
                    }
                    .frame(maxWidth: 860)
                    .padding(.horizontal, Space.xl)
                    .padding(.vertical, Space.lg)
                    .frame(maxWidth: .infinity)
                }
                .scrollContentBackground(.hidden)
                .background(theme.colors.bg)
                .onChange(of: model.rows.count) { _, _ in if follow { proxy.scrollTo("bottom", anchor: .bottom) } }
                .onChange(of: model.streamText.count / 120) { _, _ in if follow { proxy.scrollTo("bottom", anchor: .bottom) } }
                .onChange(of: approvals.count) { _, _ in if follow { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } } }
                .onChange(of: model.loading) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
                .overlay(alignment: .bottomTrailing) {
                    if !follow {
                        Button { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } } label: {
                            Image(systemName: "arrow.down").padding(Space.sm)
                        }
                        .buttonStyle(.borderedProminent)
                        .clipShape(Circle())
                        .padding(Space.lg)
                        .help("Jump to latest")
                    }
                }
            }

            if let p = session?.pause {
                Text(p.message ?? "Paused by the usage limit")
                    .font(.system(size: TypeScale.sm))
                    .foregroundStyle(theme.colors.warn)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.lg)
                    .padding(.vertical, Space.sm)
                    .background(theme.colors.warn.opacity(0.10))
            }
            // What Claude is waiting on takes the composer's place until it is answered.
            if let a = approvals.first {
                VStack(alignment: .leading, spacing: Space.xs) {
                    if approvals.count > 1 {
                        Text("1 of \(approvals.count) waiting")
                            .font(.system(size: TypeScale.xs))
                            .foregroundStyle(theme.colors.muted)
                    }
                    ScrollView {
                        ApprovalCard(approval: a, cwd: cwd, busy: model.busyApprovals.contains(a.id)) { body in model.answer(a, body) }
                            .id(a.id)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .frame(maxHeight: 460)
                    .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: 860)
                .padding(Space.md)
                .background(theme.colors.surface2, in: RoundedRectangle(cornerRadius: theme.radius))
                .overlay(RoundedRectangle(cornerRadius: theme.radius).strokeBorder(theme.colors.warn.opacity(0.5)))
                .padding(.horizontal, Space.xl)
                .padding(.bottom, Space.md)
                .frame(maxWidth: .infinity)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                Composer(model: model, draft: $draft, running: running, focused: $composerFocused)
            }
        }
        .animation(.snappy, value: approvals.first?.id)
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
        .navigationTitle(session?.title ?? "Session")
        .navigationSubtitle(subtitle(session))
        .toolbar {
            ToolbarItemGroup {
                if let session {
                    Picker("Mode", selection: Binding(get: { session.mode }, set: { m in Task { try? await store.api.setMode(session.id, m) } })) {
                        ForEach(Mode.allCases) { Text($0.label).tag($0) }
                    }
                    .help("Permission mode")
                    Button { TerminalLauncher.open(session, store: store) } label: { Label("Open in Terminal", systemImage: "terminal") }
                        .disabled(session.claude_session_id == nil)
                        .help(store.api.config.isLoopback ? "Open in Terminal with claude --resume" : "Copy the claude --resume command")
                    Menu {
                        Button("Rename…") { newTitle = session.title; renaming = true }
                        SessionMenu(store: store, session: session)
                    } label: { Label("More", systemImage: "ellipsis.circle") }
                }
            }
        }
        .alert("Rename session", isPresented: $renaming) {
            TextField("Title", text: $newTitle)
            Button("Rename") { Task { try? await store.api.rename(sessionId, newTitle) } }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Something went wrong", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .onAppear { model.open(); composerFocused = true }
        .onDisappear { model.close() }
    }

    private func subtitle(_ s: Session?) -> String {
        guard let s else { return "" }
        var parts = [statusLabel(s.status), s.mode.label]
        if let p = store.project(s.project_id)?.name { parts.insert(p, at: 0) }
        if let c = s.cost, c > 0 { parts.append(Format.cost(c)) }
        return parts.joined(separator: " · ")
    }
}

struct Composer: View {
    @Environment(\.theme) private var theme
    let model: SessionModel
    @Binding var draft: String
    let running: Bool
    var focused: FocusState<Bool>.Binding
    @State private var importing = false

    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.attachments.contains { $0.ref != nil } }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            if !model.suggestion.isEmpty && !running && draft.isEmpty {
                Button(model.suggestion) { draft = model.suggestion }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .lineLimit(1)
            }
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
                            .background(theme.colors.surface2, in: Capsule())
                        }
                    }
                }
            }
            HStack(alignment: .bottom, spacing: Space.sm) {
                Button { importing = true } label: { Image(systemName: "paperclip") }
                    .buttonStyle(.borderless)
                    .help("Attach files (or drop them on the session)")
                TextField(running ? "Queue a message…" : "Message Claude", text: $draft, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...10)
                    .focused(focused)
                    .onSubmit(send)
                    .padding(Space.sm)
                    .background(theme.colors.surface2, in: RoundedRectangle(cornerRadius: theme.radius))
                if running && !canSend {
                    Button(action: model.interrupt) { Image(systemName: "stop.fill") }
                        .keyboardShortcut(".", modifiers: .command)
                        .tint(theme.colors.bad)
                        .buttonStyle(.borderedProminent)
                        .help("Stop (⌘.)")
                } else {
                    Button(action: send) { Image(systemName: "arrow.up") }
                        .keyboardShortcut(.return, modifiers: .command)
                        .buttonStyle(.borderedProminent)
                        .disabled(!canSend)
                        .help("Send (Return, or ⌘Return)")
                }
            }
            Text("Return sends · ⌥Return for a new line")
                .font(.system(size: TypeScale.xs))
                .foregroundStyle(theme.colors.muted)
        }
        .padding(Space.md)
        .background(theme.colors.surfaceRail)
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
