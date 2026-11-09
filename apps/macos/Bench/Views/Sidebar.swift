import SwiftUI

/// Places a sidebar row can open besides a session, task or project.
enum Place: String, CaseIterable {
    case needs = "place:needs", running = "place:running", all = "place:all"
    var title: String {
        switch self {
        case .needs: "Needs you"
        case .running: "Running"
        case .all: "All sessions"
        }
    }
}

/// The sidebar as a project rail: projects as icons down the left, each showing whether
/// anything in it runs or needs you; the selected one's tasks and sessions on the right.
/// Inbox (what needs you and what runs, everywhere) leads the rail; Ask Bench ends it.
struct Sidebar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    @Binding var selection: String?

    var body: some View {
        VStack(spacing: 0) {
            top
            if !store.ready { ListSkeleton(rows: 6) }
            HStack(spacing: 0) {
                ProjectRail(store: store)
                    .zIndex(1)
                Rectangle().fill(theme.colors.fill(0.06)).frame(width: 1)
                list.frame(maxWidth: .infinity)
            }
            footer
        }
        // Opening a session from anywhere (⌘K, Spotlight, a notification) shows its project.
        .onChange(of: selection) { _, v in
            guard let v else { return }
            if let s = store.session(v) { model.rail = s.project_id.flatMap { store.project($0) != nil ? $0 : nil } ?? "ask" }
            else if v.hasPrefix("task:"), let t = store.task(String(v.dropFirst(5))), let p = t.project_id { model.rail = p }
            else if v.hasPrefix("project:") { model.rail = String(v.dropFirst(8)) }
        }
    }

    private func newest(_ a: Session, _ b: Session) -> Bool { (a.updated_at ?? "") > (b.updated_at ?? "") }

    @ViewBuilder private var list: some View {
        let rail = model.rail
        List(selection: $selection) {
            if rail == "inbox" {
                inbox
            } else if rail == "ask" {
                header(title: "Ask Bench", sub: "Questions about Bench itself", project: nil)
                let known = Set(store.projects.map(\.id))
                let ask = store.sessions.filter { ($0.project_id == nil || !known.contains($0.project_id!)) && $0.archived_at == nil }.sorted(by: newest)
                Section { ForEach(ask) { s in sessionRow(s) } }
            } else if let p = store.project(rail) {
                projectList(p)
            } else {
                inbox
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .environment(\.defaultMinListRowHeight, 28)
    }

    @ViewBuilder private var inbox: some View {
        let needs = store.sessions.filter { Mark.of($0, store: store) == .needs && $0.archived_at == nil }
        let running = store.sessions.filter { $0.status == "running" && $0.archived_at == nil && Mark.of($0, store: store) != .needs }
        let recent = store.sessions.filter { $0.archived_at == nil && Mark.of($0, store: store) == .idle }.sorted(by: newest).prefix(8)
        header(title: "Inbox", sub: needs.isEmpty && running.isEmpty ? "Nothing needs you" : [needs.isEmpty ? nil : "\(needs.count) need\(needs.count == 1 ? "s" : "") you", running.isEmpty ? nil : "\(running.count) running"].compactMap { $0 }.joined(separator: " · "), project: nil)
        if !needs.isEmpty { Section { ForEach(needs) { sessionRow($0, showProject: true) } } header: { label("Needs you") } }
        if !running.isEmpty { Section { ForEach(running) { sessionRow($0, showProject: true) } } header: { label("Running") } }
        if !recent.isEmpty { Section { ForEach(Array(recent)) { sessionRow($0, showProject: true) } } header: { label("Recent") } }
    }

    @ViewBuilder private func projectList(_ p: Project) -> some View {
        let tasks = store.tasks.filter { $0.project_id == p.id && $0.state == "active" }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        let loose = store.sessions.filter { $0.project_id == p.id && $0.task_id == nil && $0.archived_at == nil }.sorted(by: newest)
        header(title: p.name, sub: "\(loose.count) session\(loose.count == 1 ? "" : "s")" + (tasks.isEmpty ? "" : " · \(tasks.count) task\(tasks.count == 1 ? "" : "s")"), project: p)
        if !tasks.isEmpty {
            Section {
                ForEach(tasks) { t in
                    twoLine(title: t.title, status: Describe.task(t, store: store), mark: Mark.of(task: t, store: store), age: nil)
                        .tag("task:\(t.id)")
                }
            } header: { label("Tasks") }
        }
        Section {
            if loose.isEmpty {
                Text("No sessions yet").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            }
            ForEach(loose) { s in sessionRow(s) }
        } header: { label("Sessions") }
    }

    /// The list's heading: what the rail has selected. A project's name opens its page.
    private func header(title: String, sub: String, project: Project?) -> some View {
        Button { if let project { selection = "project:\(project.id)" } } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                Text(sub).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(project == nil)
        .help(project.map { "Open \($0.name)" } ?? "")
        .padding(.bottom, 4)
        .listRowSeparator(.hidden)
        .selectionDisabled()
    }

    private func label(_ s: String) -> some View {
        Text(s).textCase(nil).font(.system(size: 11, weight: .semibold))
    }

    private func sessionRow(_ s: Session, showProject: Bool = false) -> some View {
        let mark = Mark.of(s, store: store)
        let project = showProject ? (store.project(s.project_id)?.name ?? "Ask Bench") : nil
        let status: String = {
            switch mark {
            case .needs: return "Needs you"
            case .running: return "Running" + (store.runningSince[s.id].map { " · " + Format.since($0) } ?? "")
            case .failed: return "Stopped with an error"
            default: return Describe.session(s, store: store)
            }
        }()
        return twoLine(title: s.title, status: [project, status].compactMap { $0 }.joined(separator: " · "), mark: mark, age: mark == .idle ? Format.ago(s.updated_at) : nil)
            .tag(s.id)
            .contextMenu { SessionMenu(store: store, session: s) }
            .dropDestination(for: URL.self) { urls, _ in drop(urls, on: s.id) }
    }

    /// A row: the title over up to two lines, then where it stands. Idle rows carry no mark,
    /// so a mark always means something.
    private func twoLine(title: String, status: String, mark: Mark, age: String?) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Group {
                if mark == .idle { Color.clear.frame(width: 8, height: 8) } else { MarkView(mark: mark).frame(width: 10) }
            }
            .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 4 }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13)).lineLimit(2)
                Text(status).font(.system(size: 11.5))
                    .foregroundStyle(mark == .idle ? theme.colors.muted : mark.tint(theme.colors))
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if let age { Text(age).font(.system(size: 11)).foregroundStyle(theme.colors.muted).fixedSize() }
        }
        .padding(.vertical, 3)
        .accessibilityElement(children: .combine)
    }

    /// Files dropped on a session row open that session with them attached.
    private func drop(_ urls: [URL], on id: String) -> Bool {
        guard !urls.isEmpty else { return false }
        model.pendingAttachments[id, default: []] += urls
        selection = id
        return true
    }

    /// The pane's top: room for the traffic lights, then hide-sidebar and new-session.
    private var top: some View {
        HStack(spacing: 2) {
            TrafficLights().padding(.leading, 6)
            Spacer()
            Button { withAnimation(.snappy) { model.showSidebar = false } } label: {
                Image(systemName: "sidebar.left").font(.system(size: 13)).frame(width: 28, height: 26).contentShape(Rectangle())
            }
            .buttonStyle(.plain).help("Hide Sidebar (⌃⌘S)")
            Button { model.newSession(in: store.project(model.rail)?.id) } label: {
                Image(systemName: "square.and.pencil").font(.system(size: 13)).frame(width: 28, height: 26).contentShape(Rectangle())
            }
            .buttonStyle(.plain).help("New Session (⌘N)")
        }
        .foregroundStyle(theme.colors.muted)
        .padding(.horizontal, 10)
        .frame(height: 46)
        .background(WindowDragArea())
    }

    private var footer: some View {
        HStack(spacing: Space.sm) {
            Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 7, height: 7)
            Text(store.connected ? Describe.host(store) : "Connecting…")
                .lineLimit(1)
            Spacer()
            if let v = store.version { Text("Bench \(v)").foregroundStyle(theme.colors.muted).fixedSize() }
        }
        .font(.system(size: 12))
        .foregroundStyle(theme.colors.muted)
        .padding(.horizontal, 14)
        .frame(height: 40)
        .overlay(alignment: .top) { Rectangle().fill(theme.colors.fill(0.05)).frame(height: 1) }
    }
}

/// The rail: Inbox, every project as a lettered tile, add, and Ask Bench at the foot.
/// Hovering a tile names it at once, with where it stands and its shortcut.
struct ProjectRail: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var hover: String?

    var body: some View {
        VStack(spacing: 8) {
            let needs = store.sessions.filter { Mark.of($0, store: store) == .needs && $0.archived_at == nil }.count
            tile(id: "inbox", label: "Inbox", sub: needs == 0 ? "Nothing needs you" : "\(needs) need\(needs == 1 ? "s" : "") you", keys: "⌃0",
                 fill: theme.colors.fill(0.08), mark: nil, badge: needs) {
                Image(systemName: "tray").font(.system(size: 14, weight: .medium)).foregroundStyle(theme.colors.text)
            }
            Rectangle().fill(theme.colors.fill(0.1)).frame(width: 22, height: 1)
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 8) {
                    ForEach(Array(store.projects.enumerated()), id: \.element.id) { i, p in
                        let mark = projectMark(p)
                        tile(id: p.id, label: p.name, sub: summary(p), keys: i < 9 ? "⌃\(i + 1)" : nil,
                             fill: Self.tint(p.name), mark: mark, badge: 0) {
                            Text(String(p.name.prefix(1)).uppercased()).font(.system(size: 14, weight: .bold)).foregroundStyle(.white)
                        }
                    }
                    Button { model.showAddProject = true } label: {
                        Image(systemName: "plus").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                            .frame(width: 34, height: 34)
                            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(theme.colors.fill(0.14)))
                    }
                    .buttonStyle(.plain).help("Add Project (⇧⌘N)")
                    .accessibilityLabel("Add project")
                }
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity)
            }
            Spacer(minLength: 0)
            tile(id: "ask", label: "Ask Bench", sub: "Questions about Bench itself", keys: nil,
                 fill: theme.colors.fill(0.08), mark: nil, badge: 0) {
                Image(systemName: "bubble.left").font(.system(size: 13, weight: .medium)).foregroundStyle(theme.colors.text)
            }
            .padding(.bottom, 10)
        }
        .padding(.top, 4)
        .frame(width: 56)
    }

    private func projectMark(_ p: Project) -> Mark? {
        let ss = store.sessions.filter { $0.project_id == p.id && $0.archived_at == nil }
        let marks = ss.map { Mark.of($0, store: store) }
        if marks.contains(.needs) { return .needs }
        if marks.contains(.running) { return .running }
        if marks.contains(.failed) { return .failed }
        return nil
    }

    private func summary(_ p: Project) -> String {
        let ss = store.sessions.filter { $0.project_id == p.id && $0.archived_at == nil }
        let needs = ss.filter { Mark.of($0, store: store) == .needs }.count
        let running = ss.filter { $0.status == "running" }.count
        return [needs > 0 ? "\(needs) need\(needs == 1 ? "s" : "") you" : nil, running > 0 ? "\(running) running" : nil,
                "\(ss.count) session\(ss.count == 1 ? "" : "s")"].compactMap { $0 }.joined(separator: " · ")
    }

    /// A steady colour per project, from its name, so a tile is recognisable at a glance.
    static func tint(_ name: String) -> Color {
        let palette: [(Double, Double, Double)] = [(0.23, 0.51, 0.77), (0.77, 0.42, 0.23), (0.48, 0.36, 0.77), (0.23, 0.64, 0.48),
                                                   (0.35, 0.41, 0.51), (0.72, 0.28, 0.48), (0.62, 0.55, 0.20), (0.20, 0.55, 0.62)]
        let h = name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xffff }
        let c = palette[h % palette.count]
        return Color(.sRGB, red: c.0, green: c.1, blue: c.2, opacity: 1)
    }

    private func tile<Icon: View>(id: String, label: String, sub: String, keys: String?, fill: Color, mark: Mark?, badge: Int,
                                   @ViewBuilder icon: () -> Icon) -> some View {
        let on = model.rail == id
        return Button { model.rail = id } label: {
            ZStack { icon() }
                .frame(width: 34, height: 34)
                .background(fill, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(on ? theme.colors.text : (hover == id ? theme.colors.fill(0.35) : .clear), lineWidth: 2).padding(-4))
                .overlay(alignment: .bottomTrailing) {
                    if let mark {
                        Group {
                            if mark == .running {
                                Circle().strokeBorder(theme.colors.accent, lineWidth: 2).background(Circle().fill(theme.colors.surface2))
                            } else {
                                Circle().fill(mark.tint(theme.colors))
                            }
                        }
                        .frame(width: 11, height: 11)
                        .overlay(Circle().strokeBorder(theme.colors.surface2, lineWidth: 2).padding(-2))
                        .offset(x: 3, y: 3)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if badge > 0 {
                        Text("\(badge)").font(.system(size: 10, weight: .bold)).foregroundStyle(Color.black.opacity(0.85))
                            .padding(.horizontal, 4).frame(minWidth: 16, minHeight: 16)
                            .background(theme.colors.warn, in: Capsule())
                            .offset(x: 5, y: -5)
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 ? id : (hover == id ? nil : hover) }
        // Named at once on hover, to the right, over the list: Discord's way, not a slow tooltip.
        .overlay(alignment: .leading) {
            if hover == id {
                HoverLabel(title: label, sub: sub, keys: keys, warn: sub.contains("need"))
                    .fixedSize()
                    .offset(x: 46)
                    .allowsHitTesting(false)
                    .transition(.opacity)
            }
        }
        .accessibilityLabel("\(label), \(sub)")
        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
    }
}

private struct HoverLabel: View {
    @Environment(\.theme) private var theme
    let title: String
    let sub: String
    let keys: String?
    let warn: Bool

    var body: some View {
        HStack(spacing: 0) {
            Triangle().fill(theme.colors.surface3).frame(width: 6, height: 12)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 10) {
                    Text(title).font(.system(size: 13, weight: .semibold))
                    Spacer(minLength: 0)
                    if let keys {
                        Text(keys).font(.system(size: 10.5)).foregroundStyle(theme.colors.muted)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 4))
                    }
                }
                Text(sub).font(.system(size: 11.5)).foregroundStyle(warn ? theme.colors.warn : theme.colors.muted)
            }
            .padding(.horizontal, 11).padding(.vertical, 7)
            .frame(minWidth: 170, alignment: .leading)
            .background(theme.colors.surface3, in: RoundedRectangle(cornerRadius: 9))
            .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(theme.colors.fill(0.08)))
            .shadow(color: .black.opacity(0.4), radius: 12, y: 6)
        }
    }
}

private struct Triangle: Shape {
    func path(in r: CGRect) -> Path {
        Path { p in p.move(to: CGPoint(x: r.maxX, y: r.minY)); p.addLine(to: CGPoint(x: r.minX, y: r.midY)); p.addLine(to: CGPoint(x: r.maxX, y: r.maxY)); p.closeSubpath() }
    }
}

/// Rename, archive and open-in-terminal, for context menus.
struct SessionMenu: View {
    @Environment(\.openWindow) private var openWindow
    @Environment(AppModel.self) private var app
    let store: BenchStore
    let session: Session

    var body: some View {
        Button("Open in New Tab") { app.openTab(session.id) }
        Button("Open to the Side") { app.openToSide(session.id) }.disabled(app.selection == session.id)
        Button("Open in New Window") { openWindow(id: "session", value: session.id) }
        Divider()
        Button("Open in Claude Code") { TerminalLauncher.open(session, store: store) }
            .disabled(session.claude_session_id == nil)
        Button("Copy Resume Command") { TerminalLauncher.copy(session) }
            .disabled(session.claude_session_id == nil)
        Divider()
        Menu("Permissions") {
            ForEach(Mode.allCases) { m in
                Button { Task { try? await store.api.setMode(session.id, m) } } label: {
                    if m == session.mode { Label(m.label, systemImage: "checkmark") } else { Text(m.label) }
                }
            }
        }
        Button(session.archived_at == nil ? "Archive" : "Unarchive") {
            Task { try? await store.api.archive(session.id, session.archived_at == nil) }
        }
    }
}

/// A session's state as a dot, for lists that still take a status string.
struct StatusDot: View {
    let status: String
    var body: some View {
        MarkView(mark: status == "running" ? .running : status == "waiting" ? .needs : status == "error" ? .failed : .idle)
    }
}

struct SessionRowView: View {
    @Environment(\.theme) private var theme
    let session: Session
    let project: String?
    let detail: String?

    var body: some View {
        HStack(spacing: Space.sm) {
            StatusDot(status: session.status)
            VStack(alignment: .leading, spacing: 2) {
                Text(session.title).lineLimit(1)
                let sub = [project, detail].compactMap { $0 }.joined(separator: " · ")
                if !sub.isEmpty { Text(sub).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted).lineLimit(1) }
            }
            Spacer(minLength: 0)
            Text(Format.ago(session.updated_at)).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.muted).fixedSize()
        }
        .font(.system(size: 13))
    }
}
