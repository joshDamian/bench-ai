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
    /// The split's second pane has no inspector of its own; one is enough.
    var showsInspector = true
    /// Shown as × in the header: closes the split pane.
    var onClose: (() -> Void)?
    /// In a window of its own, the window and its tab already name the session.
    var showsTitle = true
    @State private var model: SessionModel
    @State private var files: SessionFiles
    @State private var draft = ""
    @State private var follow = true
    @State private var dropTargeted = false
    @FocusState private var composerFocused: Bool
    @Environment(\.controlActiveState) private var active
    /// ⌘F: the find bar's query, and which match is showing.
    @State private var finding = false
    @State private var query = ""
    @State private var hit = 0
    @FocusState private var findFocused: Bool
    /// The turn ⌥⌘↑ / ⌥⌘↓ last moved to, and how many blocks there were when you last saw the end.
    @State private var turnIndex: Int?
    @State private var seen = 0
    /// Whether the thread has scrolled under the header: then the header shows its edge.
    @State private var scrolled = false

    init(store: BenchStore, sessionId: String, embedded: Bool = false, showsInspector: Bool = true, onClose: (() -> Void)? = nil, showsTitle: Bool = true) {
        self.embedded = embedded
        self.showsInspector = showsInspector
        self.onClose = onClose
        self.showsTitle = showsTitle
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
                        if showsTitle { HeaderTitle(title: session?.title ?? "Session", sub: subtitle(session)) }
                    } trailing: {
                        if let session {
                            ModelMenu(store: store, session: session, capsule: true)
                            ModeMenu(store: store, session: session)
                        }
                        Spacer().frame(width: Space.sm)
                        HeaderToggle(icon: "terminal", help: "Terminal (⇧⌘T)", on: $app.showTerminal)
                        if showsInspector && hasInspector(session) {
                            HeaderToggle(icon: "sidebar.right", help: "Inspector (⌘J)", on: $app.showInspector)
                        }
                        if let onClose {
                            Button(action: onClose) {
                                Image(systemName: "xmark").font(.system(size: 10, weight: .semibold)).frame(width: 26, height: 26)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(theme.colors.muted)
                            .help("Close This Side")
                            .accessibilityLabel("Close this side")
                        }
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
            if !embedded && showsInspector && app.showInspector && hasInspector(session) {
                Rectangle().fill(theme.colors.fill(0.08)).frame(width: 1)
                SessionInspector(store: store, sessionId: sessionId, files: files)
                    .frame(width: 340)
                    .transition(.move(edge: .trailing))
            }
        }
        // A hairline under the header once the thread is underneath it, carried on under the
        // inspector's tabs so the header reads as one framed band.
        .overlay(alignment: .top) {
            if !embedded {
                Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1)
                    .padding(.top, 54)
                    .opacity(scrolled ? 1 : 0)
                    .animation(.easeOut(duration: 0.15), value: scrolled)
                    .allowsHitTesting(false)
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
            VStack(spacing: 0) {
            if finding { findBar(proxy) }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 22) {
                    // At the very top of the thread: visible means nothing is under the header.
                    Color.clear.frame(height: 1).padding(.bottom, -22)
                        .onAppear { scrolled = false }
                        .onDisappear { scrolled = true }
                    if model.hasOlder {
                        Button("Show earlier messages") { Task { await model.loadOlder() } }
                            .buttonStyle(.link)
                            .frame(maxWidth: .infinity)
                    }
                    if model.loading && model.rows.isEmpty {
                        TranscriptSkeleton()
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
            .modifier(SessionKeys(proxy: proxy, follow: follow, seen: $seen, finding: $finding, findFocused: $findFocused,
                                  model: model, jump: { jumpTurn($0, proxy) }))
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
                    let unseen = max(0, model.blocks.count - seen)
                    Button { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "arrow.down").font(.system(size: 12, weight: .semibold))
                            if unseen > 0 { Text("\(unseen) new").font(.system(size: 12, weight: .medium)) }
                        }
                        .padding(.horizontal, unseen > 0 ? 12 : 0)
                        .frame(minWidth: 30, minHeight: 30)
                        .foregroundStyle(unseen > 0 ? theme.colors.onAccent : theme.colors.text)
                        .background(unseen > 0 ? theme.colors.accent : theme.colors.surface3, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(unseen > 0 ? "Jump to \(unseen) new messages" : "Jump to latest")
                    .padding(.trailing, Space.lg)
                    .padding(.bottom, approvals.isEmpty ? 150 : Space.lg)
                    .help("Jump to latest")
                }
            }
        }
            }
        .onAppear { takePending() }
        .onChange(of: app.pendingAttachments[sessionId]?.count ?? 0) { _, _ in takePending() }
        .onDrop(of: [.fileURL, .image], isTargeted: $dropTargeted) { providers in
            for p in providers where p.hasItemConformingToTypeIdentifier(UTType.image.identifier) && !p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                _ = p.loadDataRepresentation(forTypeIdentifier: UTType.png.identifier) { d, _ in
                    if let d, let url = PastedImage.write(d) { Task { @MainActor in model.attach([url]) } }
                }
            }
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

    /// Ask Bench sessions have no checkout: nothing for the inspector to show.
    private func hasInspector(_ s: Session?) -> Bool { s?.project_id != nil || !files.known.isEmpty }

    private func takePending() {
        guard let urls = app.pendingAttachments[sessionId], !urls.isEmpty else { return }
        app.pendingAttachments[sessionId] = nil
        model.attach(urls)
    }

    /// The text a block can be found by.
    private func text(_ b: Block) -> String {
        switch b {
        case .user(let r), .note(let r):
            if case .user(_, let t, _) = r { return t }
            if case .system(_, let t, _) = r { return t }
            return ""
        case .turn(_, let steps, let reply, _, _, _):
            let prose = steps.compactMap { r -> String? in if case .text(_, let t, _) = r { t } else { nil } }
            return ([reply] + prose).joined(separator: "\n")
        }
    }

    private var matches: [String] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return [] }
        return model.blocks.filter { text($0).localizedCaseInsensitiveContains(q) }.map(\.id)
    }

    @ViewBuilder private func findBar(_ proxy: ScrollViewProxy) -> some View {
        let m = matches
        HStack(spacing: Space.sm) {
            Image(systemName: "magnifyingglass").foregroundStyle(theme.colors.muted)
            TextField("", text: $query, prompt: Text("Find in conversation"))
                .textFieldStyle(.plain)
                .focused($findFocused)
                .onSubmit { step(1, m, proxy) }
                .onChange(of: query) { _, _ in hit = 0; if let first = matches.last { withAnimation { proxy.scrollTo(first, anchor: .center) } } }
                .onKeyPress(.escape) { finding = false; query = ""; composerFocused = true; return .handled }
            Text(m.isEmpty ? (query.isEmpty ? "" : "No matches") : "\(m.count - hit) of \(m.count)")
                .font(.system(size: TypeScale.xs).monospacedDigit()).foregroundStyle(theme.colors.muted).fixedSize()
            Button { step(-1, m, proxy) } label: { Image(systemName: "chevron.up") }.buttonStyle(.plain).disabled(m.isEmpty)
                .help("Previous (⇧Return)").keyboardShortcut(.return, modifiers: .shift)
            Button { step(1, m, proxy) } label: { Image(systemName: "chevron.down") }.buttonStyle(.plain).disabled(m.isEmpty)
                .help("Next (Return)")
            Button("Done") { finding = false; query = ""; composerFocused = true }.buttonStyle(.plain).foregroundStyle(theme.colors.accent)
        }
        .font(.system(size: TypeScale.sm))
        .padding(.horizontal, Space.lg).frame(height: 36)
        .background(theme.colors.surface1)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.colors.fill(0.07)).frame(height: 1) }
    }

    /// Return walks from the newest match back; ⇧Return walks forward again.
    private func step(_ dir: Int, _ m: [String], _ proxy: ScrollViewProxy) {
        guard !m.isEmpty else { return }
        hit = (hit + (dir > 0 ? 1 : -1) + m.count) % m.count
        let id = m[m.count - 1 - hit]
        withAnimation { proxy.scrollTo(id, anchor: .center) }
    }

    /// ⌥⌘↑ / ⌥⌘↓: from turn to turn, starting from the end.
    private func jumpTurn(_ dir: Int, _ proxy: ScrollViewProxy) {
        let turns = model.blocks.enumerated().filter { if case .turn = $0.element { true } else { false } }.map(\.offset)
        guard !turns.isEmpty else { return }
        let current = turnIndex ?? (dir < 0 ? turns.count : -1)
        let next = max(0, min(turns.count - 1, current + dir))
        turnIndex = next
        withAnimation(.snappy) { proxy.scrollTo(model.blocks[turns[next]].id, anchor: .top) }
        if next == turns.count - 1 && dir > 0 { turnIndex = nil; proxy.scrollTo("bottom", anchor: .bottom) }
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
    @State private var pasteMonitor: Any?

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
        // ⌘V with an image or copied files on the clipboard attaches them; text pastes as usual.
        .onAppear {
            guard pasteMonitor == nil else { return }
            pasteMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { e in
                guard focused.wrappedValue, e.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command,
                      e.charactersIgnoringModifiers == "v" else { return e }
                let urls = PastedImage.take(from: .general)
                if urls.isEmpty { return e }
                model.attach(urls)
                return nil
            }
        }
        .onDisappear { if let m = pasteMonitor { NSEvent.removeMonitor(m) }; pasteMonitor = nil }
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


/// The window-key-only responses to the Session menu and ⌘F, kept apart so the
/// conversation's modifier chain stays small enough for the compiler.
private struct SessionKeys: ViewModifier {
    @Environment(AppModel.self) private var app
    @Environment(\.controlActiveState) private var active
    let proxy: ScrollViewProxy
    let follow: Bool
    @Binding var seen: Int
    @Binding var finding: Bool
    var findFocused: FocusState<Bool>.Binding
    let model: SessionModel
    let jump: (Int) -> Void

    func body(content: Content) -> some View {
        content
            .onChange(of: follow) { _, f in if f { seen = model.blocks.count } }
            .onChange(of: model.blocks.count) { _, n in if follow { seen = n } }
            .onChange(of: app.findRequest) { _, _ in
                guard active == .key else { return }
                finding = true
                findFocused.wrappedValue = true
            }
            .onChange(of: app.turnUp) { _, _ in if active == .key { jump(-1) } }
            .onChange(of: app.turnDown) { _, _ in if active == .key { jump(1) } }
            .onChange(of: app.approveRequest) { _, _ in approve() }
            .onChange(of: app.stopRequest) { _, _ in
                if active == .key && model.session?.status == "running" { model.interrupt() }
            }
    }

    private func approve() {
        guard active == .key, let a = model.approvals.first else { return }
        if a.tool == "ExitPlanMode" {
            model.answer(a, ["allow": true, "mode": "acceptEdits"])
        } else if PanelText.answerable(a) {
            model.answer(a, ["allow": true])
        }
    }
}
