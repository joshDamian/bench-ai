import QuickLook
import SwiftUI

// MARK: Files a session made

/// What the server says a path in the transcript is (POST /api/sessions/:id/files/stat).
struct FileInfo: Identifiable, Hashable {
    var id: String { path }
    let path: String
    let name: String
    let mime: String
    let size: Double
    let kind: String
    let artifact: Bool

    var meta: String {
        let ext = name.contains(".") ? String(name.split(separator: ".").last ?? "") : kind
        return "\(ext.uppercased()) · \(FileInfo.bytes(size))"
    }

    static func bytes(_ n: Double) -> String {
        n < 1024 ? "\(Int(n)) B" : n < 1024 * 1024 ? "\(Int(n / 1024)) KB" : String(format: "%.1f MB", n / 1024 / 1024)
    }
}

/// The files a session's replies name, asked of the server once each, and their bytes
/// fetched into a local copy when one is opened.
@MainActor
@Observable
final class SessionFiles {
    let api: BenchAPI
    let sessionId: String
    private(set) var known: [String: FileInfo] = [:]
    @ObservationIgnored private var asked = Set<String>()
    /// The local copy Quick Look is showing.
    var previewing: URL?

    init(api: BenchAPI, sessionId: String) { self.api = api; self.sessionId = sessionId }

    private static let pattern = try! NSRegularExpression(pattern: #"(?:~?/|\.{1,2}/)?[\w@+.-]*[\w@+-](?:/[\w@+.-]+)*"#)

    static func paths(_ text: String) -> [String] {
        let ns = text as NSString
        var out: [String] = []
        for m in pattern.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            var t = ns.substring(with: m.range)
            while t.hasSuffix(".") { t.removeLast() }
            guard t.count >= 3, t.count <= 1024, !t.hasPrefix("~"), t.range(of: #"^\d[\d.]*$"#, options: .regularExpression) == nil else { continue }
            guard t.contains("/") || t.range(of: #"\.[A-Za-z][A-Za-z0-9]{0,7}$"#, options: .regularExpression) != nil else { continue }
            if !out.contains(t) { out.append(t) }
        }
        return out
    }

    /// The artifacts one reply names, in the order it names them.
    func artifacts(in text: String) -> [FileInfo] {
        Self.paths(text).compactMap { known[$0] }.filter(\.artifact)
    }

    /// Every artifact the session has named so far.
    var all: [FileInfo] {
        var seen = Set<String>()
        return known.values.filter { $0.artifact && seen.insert($0.path).inserted }.sorted { $0.name < $1.name }
    }

    func stat(_ texts: [String]) async {
        let ask = Array(texts.flatMap(Self.paths).filter { !asked.contains($0) }.prefix(500))
        guard !ask.isEmpty else { return }
        ask.forEach { asked.insert($0) }
        guard let d = try? await api.call("/api/sessions/\(BenchAPI.enc(sessionId))/files/stat", method: "POST", body: ["paths": ask]),
              let v = try? JSONDecoder().decode(JSONValue.self, from: d) else { return }
        for (p, f) in v.object {
            known[p] = FileInfo(path: f["path"].string ?? p, name: f["name"].string ?? p, mime: f["mime"].string ?? "",
                                size: f["size"].number ?? 0, kind: f["kind"].string ?? "", artifact: f["artifact"].bool)
        }
    }

    /// Fetches the file into this Mac's caches and returns the copy.
    func local(_ f: FileInfo) async -> URL? {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Bench/\(sessionId)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(f.name)
        guard let d = try? await api.call("/api/sessions/\(BenchAPI.enc(sessionId))/files?path=\(BenchAPI.enc(f.path))") else { return nil }
        do { try d.write(to: url); return url } catch { return nil }
    }

    func preview(_ f: FileInfo) { Task { previewing = await local(f) } }

    func save(_ f: FileInfo) {
        Task {
            guard let src = await local(f) else { return }
            let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            var dst = downloads.appendingPathComponent(f.name)
            var n = 2
            while FileManager.default.fileExists(atPath: dst.path) {
                dst = downloads.appendingPathComponent("\((f.name as NSString).deletingPathExtension) \(n).\((f.name as NSString).pathExtension)")
                n += 1
            }
            try? FileManager.default.copyItem(at: src, to: dst)
            NSWorkspace.shared.activateFileViewerSelecting([dst])
        }
    }

    func openWith(_ f: FileInfo) { Task { if let u = await local(f) { NSWorkspace.shared.open(u) } } }
}

/// Files a reply names, as cards under it: images inline, anything else by name and size.
struct ArtifactCards: View {
    @Environment(SessionFiles.self) private var files
    @Environment(\.theme) private var theme
    let reply: String

    var body: some View {
        let found = files.artifacts(in: reply)
        if !found.isEmpty {
            FlowLayout(spacing: Space.sm) {
                ForEach(found) { f in ArtifactCard(file: f) }
            }
        }
    }
}

struct ArtifactCard: View {
    @Environment(SessionFiles.self) private var files
    @Environment(\.theme) private var theme
    let file: FileInfo

    var body: some View {
        Button { files.preview(file) } label: {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 16)).foregroundStyle(theme.colors.accent)
                    .frame(width: 34, height: 34)
                    .background(theme.colors.fill(0.07), in: RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 1) {
                    Text(file.name).font(.system(size: 13)).lineLimit(1)
                    Text(file.meta).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                }
            }
            .padding(.leading, 8).padding(.trailing, 14).padding(.vertical, 8)
            .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Quick Look") { files.preview(file) }
            Button("Open With Default App") { files.openWith(file) }
            Button("Save to Downloads") { files.save(file) }
        }
        .help("Quick Look")
    }

    private var icon: String {
        switch file.kind {
        case "image": "photo"
        case "pdf": "doc.richtext"
        case "text": "doc.text"
        default: "doc"
        }
    }
}

// MARK: The inspector

/// ⌘J: what the session changed, the files it made, and the session's own settings.
struct SessionInspector: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let sessionId: String
    let files: SessionFiles
    @State private var tab = "changes"

    var body: some View {
        @Bindable var files = files
        VStack(spacing: 0) {
            Segments(options: [("changes", "Changes"), ("files", "Files"), ("session", "Session")], selection: $tab, fill: true)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            switch tab {
            case "files": FilesTab(files: files)
            case "session": SessionTab(store: store, sessionId: sessionId)
            default: ChangesTab(store: store, sessionId: sessionId)
            }
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(theme.colors.surfaceRail)
        .quickLookPreview($files.previewing)
    }
}

private struct ChangesTab: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let sessionId: String
    @State private var diff: [DiffFile] = []
    @State private var dirty: [String] = []
    @State private var selected: String?

    var body: some View {
        let s = store.session(sessionId)
        ScrollView {
            VStack(alignment: .leading, spacing: Space.md) {
                if s?.task_id != nil {
                    if diff.isEmpty {
                        Text("No changes yet.").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
                    } else {
                        HStack(alignment: .firstTextBaseline, spacing: Space.sm) {
                            Text("\(diff.count) file\(diff.count == 1 ? "" : "s")").font(.system(size: 12.5, weight: .semibold))
                            Text("+\(diff.reduce(0) { $0 + $1.added })").foregroundStyle(theme.colors.good)
                            Text("−\(diff.reduce(0) { $0 + $1.removed })").foregroundStyle(theme.colors.bad)
                        }
                        .font(.system(size: 12, design: .monospaced))
                        VStack(spacing: 1) {
                            ForEach(diff) { f in
                                Button { selected = f.path } label: { fileRow(f) }.buttonStyle(.plain)
                            }
                        }
                        if let f = diff.first(where: { $0.path == selected }) ?? diff.first {
                            MiniDiffView(file: f)
                        }
                    }
                } else if dirty.isEmpty {
                    Text("Nothing uncommitted in the checkout.").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
                } else {
                    Text("\(dirty.count) uncommitted in the checkout").font(.system(size: 12.5, weight: .semibold))
                    ForEach(dirty, id: \.self) { p in
                        Text(p).font(.system(size: 12, design: .monospaced)).foregroundStyle(theme.colors.text.opacity(0.85)).lineLimit(1).truncationMode(.head)
                    }
                }
                if let s, let t = store.task(s.task_id), let c = t.check_command, !c.isEmpty {
                    Group2 {
                        GroupRow(title: "Check", first: true) {
                            HStack(spacing: 6) {
                                MarkView(mark: t.check_status == "passed" ? .ready : t.check_status == "failed" ? .failed : t.check_status == "running" ? .running : .idle)
                                Text(c).font(.system(size: 12, design: .monospaced)).lineLimit(1)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .task(id: s?.updated_at) { await load(s) }
    }

    private func fileRow(_ f: DiffFile) -> some View {
        HStack(spacing: Space.sm) {
            Text(f.status).font(.system(size: 11, design: .monospaced))
                .foregroundStyle(f.status == "A" ? theme.colors.good : f.status == "D" ? theme.colors.bad : theme.colors.warn)
                .frame(width: 12)
            Text(f.path).font(.system(size: 12.5)).lineLimit(1).truncationMode(.head)
            Spacer(minLength: Space.xs)
            Text("+\(f.added) −\(f.removed)").font(.system(size: 11, design: .monospaced)).foregroundStyle(theme.colors.muted).fixedSize()
        }
        .padding(.horizontal, Space.sm)
        .frame(height: 28)
        .background(selected == f.path ? theme.colors.fill(0.1) : .clear, in: RoundedRectangle(cornerRadius: 7))
        .contentShape(Rectangle())
    }

    private func load(_ s: Session?) async {
        guard let s else { return }
        if let t = s.task_id {
            if let d = try? await store.api.taskDiff(t) { diff = DiffFile.parse(d) }
        } else if let p = s.project_id, let g = try? await store.api.projectGit(p) {
            dirty = g.dirty
        }
    }
}

/// The first lines of one file's diff, for the inspector.
struct MiniDiffView: View {
    @Environment(\.theme) private var theme
    let file: DiffFile

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(file.lines.prefix(60).enumerated()), id: \.offset) { _, l in
                Text(l.isEmpty ? " " : l)
                    .font(.system(size: 11.5, design: .monospaced))
                    .lineLimit(1)
                    .foregroundStyle(l.hasPrefix("@@") ? theme.colors.muted : l.hasPrefix("+") ? theme.colors.good : l.hasPrefix("-") ? theme.colors.bad : theme.colors.text.opacity(0.8))
                    .padding(.horizontal, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(l.hasPrefix("+") && !l.hasPrefix("+++") ? theme.colors.good.opacity(0.1) : l.hasPrefix("-") && !l.hasPrefix("---") ? theme.colors.bad.opacity(0.1) : .clear)
            }
        }
        .padding(.vertical, 6)
        .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(theme.colors.fill(0.06)))
        .textSelection(.enabled)
    }
}

private struct FilesTab: View {
    @Environment(\.theme) private var theme
    let files: SessionFiles

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.sm) {
                let all = files.all
                if all.isEmpty {
                    Text("Files Claude makes or is given show up here.").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
                }
                ForEach(all) { f in ArtifactCard(file: f).frame(maxWidth: .infinity, alignment: .leading) }
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .environment(files)
    }
}

private struct SessionTab: View {
    @Environment(\.theme) private var theme
    @Environment(AppModel.self) private var app
    let store: BenchStore
    let sessionId: String
    @State private var name = ""
    @State private var routing: JSONValue?
    @State private var confirmDelete = false

    var body: some View {
        let s = store.session(sessionId)
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    GroupLabel("Name")
                    TextField("", text: $name)
                        .textFieldStyle(.plain)
                        .font(.system(size: 13))
                        .padding(.horizontal, 10).frame(height: 30)
                        .background(theme.colors.fill(0.06), in: RoundedRectangle(cornerRadius: 8))
                        .onSubmit { Task { try? await store.api.rename(sessionId, name) } }
                }
                VStack(alignment: .leading, spacing: 6) {
                    GroupLabel("Where")
                    Group2 {
                        GroupRow(title: "Project", first: true) { Text(store.project(s?.project_id)?.name ?? "Ask Bench").foregroundStyle(theme.colors.muted) }
                        if let t = store.task(s?.task_id) {
                            GroupRow(title: "Task") {
                                Button(t.title) { app.selection = "task:\(t.id)" }.buttonStyle(.link).lineLimit(1)
                            }
                        }
                        GroupRow(title: "Subagents") { Text(subagents).foregroundStyle(theme.colors.muted).lineLimit(1) }
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    GroupLabel("Spend")
                    Group2 {
                        let usage = (s?.model_usage?.object ?? [:]).compactMap { k, v in v["cost"].number.map { (k, $0) } }.sorted { $0.1 > $1.1 }
                        ForEach(Array(usage.enumerated()), id: \.offset) { i, u in
                            GroupRow(title: label(u.0), first: i == 0) { Text(Format.cost(u.1)).foregroundStyle(theme.colors.muted) }
                        }
                        GroupRow(title: "Total", first: usage.isEmpty) {
                            Text([Format.cost(s?.cost ?? 0), tokens(s)].compactMap { $0 }.joined(separator: " · "))
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    GroupLabel("Usage limit")
                    Group2 {
                        GroupRow(title: "Resume when the limit resets", first: true) {
                            Toggle("", isOn: Binding(get: { s?.auto_resume ?? store.autoResumeDefault }, set: { on in
                                Task { _ = try? await store.api.call("/api/sessions/\(BenchAPI.enc(sessionId))/limit", method: "POST", body: ["autoResume": on]) }
                            }))
                            .labelsHidden().toggleStyle(.switch).controlSize(.small)
                        }
                        if s?.pause != nil {
                            GroupRow(title: "Paused by the limit") {
                                Button("Resume Now") {
                                    Task { _ = try? await store.api.call("/api/sessions/\(BenchAPI.enc(sessionId))/limit", method: "POST", body: ["resumeNow": true]) }
                                }
                                .buttonStyle(TonalButton(height: 24))
                            }
                        }
                    }
                }
                Group2 {
                    if let s {
                        GroupRow(title: "Open in Claude Code", first: true) {
                            Button("Open") { TerminalLauncher.open(s, store: store) }.buttonStyle(TonalButton(height: 24)).disabled(s.claude_session_id == nil)
                        }
                        GroupRow(title: s.archived_at == nil ? "Archive" : "Unarchive") {
                            Button(s.archived_at == nil ? "Archive" : "Unarchive") {
                                Task { try? await store.api.archive(sessionId, s.archived_at == nil) }
                            }
                            .buttonStyle(TonalButton(height: 24))
                        }
                        GroupRow(title: "Delete this session") {
                            Button("Delete…") { confirmDelete = true }.buttonStyle(TonalButton(danger: true, height: 24))
                        }
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 14)
        }
        .onAppear { name = s?.title ?? "" }
        .task(id: sessionId) {
            if let d = try? await store.api.call("/api/sessions/\(BenchAPI.enc(sessionId))/routing") { routing = try? JSONDecoder().decode(JSONValue.self, from: d) }
        }
        .confirmationDialog("Delete this session?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                Task {
                    _ = try? await store.api.call("/api/sessions/\(BenchAPI.enc(sessionId))", method: "DELETE")
                    app.selection = nil
                }
            }
        } message: { Text("Its transcript is removed from the server. Changes it made stay.") }
    }

    private var subagents: String {
        guard let r = routing else { return "Default" }
        if let all = r["all"].string { return "All: \(all.capitalized)" }
        let parts = ["architect", "implementer", "explorer"].compactMap { k in r[k].string.map { "\(k.prefix(1).uppercased())\(k.dropFirst()): \($0.capitalized)" } }
        return parts.isEmpty ? "Default" : parts.joined(separator: ", ")
    }

    private func label(_ model: String) -> String {
        store.providers.flatMap(\.models).first { $0.id == model || $0.name == model }?.label ?? model
    }

    private func tokens(_ s: Session?) -> String? {
        let n = (s?.input_tokens ?? 0) + (s?.output_tokens ?? 0)
        guard n > 0 else { return nil }
        return n >= 1000 ? "\(Int(n / 1000))k tokens" : "\(Int(n)) tokens"
    }
}
