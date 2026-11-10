import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// One file a turn changed: its path as Claude wrote it, the edits, and lines added and removed.
struct FileChange: Identifiable, Hashable {
    var id: String { path }
    let path: String
    let shown: String
    let edits: [Pair]
    let added: Int
    let removed: Int
    struct Pair: Hashable { let old: String; let new: String }

    /// The first line the turn's last edit touched, for opening the editor there.
    var line: Int? {
        guard let text = try? String(contentsOfFile: path, encoding: .utf8), let last = edits.last?.new, !last.isEmpty,
              let r = text.range(of: last) else { return nil }
        return text[..<r.lowerBound].reduce(1) { $1 == "\n" ? $0 + 1 : $0 }
    }
    var isLocal: Bool { FileManager.default.fileExists(atPath: path) }
}

enum TurnChanges {
    /// Every file a turn's Edit, MultiEdit and Write calls touched, in the order first touched.
    static func of(_ steps: [Row], cwd: String) -> [FileChange] {
        var order: [String] = []
        var edits: [String: [FileChange.Pair]] = [:]
        for r in steps {
            guard case .tools(_, let calls, _) = r else { continue }
            for c in calls where !c.failed {
                guard let path = c.input["file_path"].string else { continue }
                var list: [FileChange.Pair] = []
                switch c.name {
                case "Edit": list = [.init(old: c.input["old_string"].string ?? "", new: c.input["new_string"].string ?? "")]
                case "MultiEdit": list = c.input["edits"].array.map { .init(old: $0["old_string"].string ?? "", new: $0["new_string"].string ?? "") }
                case "Write": list = [.init(old: "", new: c.input["content"].string ?? "")]
                default: continue
                }
                if edits[path] == nil { order.append(path) }
                edits[path, default: []] += list
            }
        }
        func count(_ s: String) -> Int { s.isEmpty ? 0 : s.split(separator: "\n", omittingEmptySubsequences: false).count }
        return order.map { p in
            let e = edits[p] ?? []
            return FileChange(path: p, shown: Transcript.rel(p, cwd: cwd), edits: e, added: e.reduce(0) { $0 + count($1.new) }, removed: e.reduce(0) { $0 + count($1.old) })
        }
    }
}

/// Code editors Bench can open a file in, at a line. Only the ones installed are offered.
enum Editors {
    struct Editor: Identifiable { let id: String; let name: String; let bundle: String }
    static let all = [
        Editor(id: "vscode", name: "Visual Studio Code", bundle: "com.microsoft.VSCode"),
        Editor(id: "cursor", name: "Cursor", bundle: "com.todesktop.230313mzl4w4u92"),
        Editor(id: "zed", name: "Zed", bundle: "dev.zed.Zed"),
        Editor(id: "xcode", name: "Xcode", bundle: "com.apple.dt.Xcode"),
    ]
    static var installed: [Editor] { all.filter { NSWorkspace.shared.urlForApplication(withBundleIdentifier: $0.bundle) != nil } }

    /// The editor ⌘O uses: the one picked last, else the first installed.
    static var preferred: Editor? {
        let id = UserDefaults.standard.string(forKey: "editor")
        return installed.first { $0.id == id } ?? installed.first
    }

    static func open(_ path: String, line: Int?, in e: Editor) {
        UserDefaults.standard.set(e.id, forKey: "editor")
        let l = line ?? 1
        switch e.id {
        case "vscode", "cursor":
            let scheme = e.id == "vscode" ? "vscode" : "cursor"
            if let u = URL(string: "\(scheme)://file\(path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? path):\(l)") { NSWorkspace.shared.open(u) }
        case "zed":
            if let u = URL(string: "zed://file\(path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? path):\(l)") { NSWorkspace.shared.open(u) }
        default:
            // xed opens Xcode at a line.
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/xed")
            p.arguments = ["-l", "\(l)", path]
            try? p.run()
        }
    }

    static func reveal(_ path: String) { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)]) }
}

/// "Open in" and "Reveal in Finder" for a file on this Mac, as a menu's items.
struct FileActions: View {
    let path: String
    let line: Int?
    var body: some View {
        if FileManager.default.fileExists(atPath: path) {
            ForEach(Editors.installed) { e in
                Button("Open in \(e.name)") { Editors.open(path, line: line, in: e) }
            }
            Button("Open with Default App") { NSWorkspace.shared.open(URL(fileURLWithPath: path)) }
            Button("Reveal in Finder") { Editors.reveal(path) }
            Divider()
        }
        Button("Copy Path") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(path, forType: .string) }
    }
}

/// "3 files changed" under a finished turn; each file opens to its diff, opens in your
/// editor, and drags out to Finder or another app.
struct ChangedFilesCard: View {
    @Environment(\.theme) private var theme
    let files: [FileChange]
    @State private var open: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("\(files.count) file\(files.count == 1 ? "" : "s") changed")
                    .font(.system(size: TypeScale.sm, weight: .medium)).foregroundStyle(theme.colors.muted)
                Spacer()
                LineCounts(added: files.reduce(0) { $0 + $1.added }, removed: files.reduce(0) { $0 + $1.removed })
            }
            .padding(.horizontal, Space.md).frame(height: 32)
            ForEach(files) { f in
                Rectangle().fill(theme.colors.fill(0.06)).frame(height: 1)
                row(f)
                if open.contains(f.id) {
                    MiniDiff(edits: f.edits.map { ($0.old, $0.new) })
                        .padding(.horizontal, Space.md).padding(.bottom, Space.sm)
                }
            }
        }
        .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: theme.radiusSm))
        .overlay(RoundedRectangle(cornerRadius: theme.radiusSm).strokeBorder(theme.colors.fill(0.07)))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(files.count) files changed")
    }

    private func row(_ f: FileChange) -> some View {
        let isOpen = open.contains(f.id)
        return HStack(spacing: Space.sm) {
            Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold))
                .rotationEffect(.degrees(isOpen ? 90 : 0)).foregroundStyle(theme.colors.muted)
            Text(f.shown).font(BenchFont.mono(TypeScale.sm)).lineLimit(1).truncationMode(.head)
            Spacer(minLength: Space.sm)
            LineCounts(added: f.added, removed: f.removed)
            if f.isLocal, let e = Editors.preferred {
                Button { Editors.open(f.path, line: f.line, in: e) } label: {
                    Image(systemName: "arrow.up.forward.app").font(.system(size: 11))
                }
                .buttonStyle(.plain).foregroundStyle(theme.colors.muted)
                .help("Open in \(e.name)")
                .accessibilityLabel("Open \(f.shown) in \(e.name)")
            }
        }
        .padding(.horizontal, Space.md).frame(height: 30)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.easeOut(duration: 0.15)) { if isOpen { open.remove(f.id) } else { open.insert(f.id) } } }
        .contextMenu { FileActions(path: f.path, line: f.line) }
        .onDrag {
            f.isLocal ? NSItemProvider(contentsOf: URL(fileURLWithPath: f.path)) ?? NSItemProvider(object: f.path as NSString)
                      : NSItemProvider(object: f.path as NSString)
        }
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel("\(f.shown), \(f.added) added, \(f.removed) removed")
    }
}

struct LineCounts: View {
    @Environment(\.theme) private var theme
    let added: Int
    let removed: Int
    var body: some View {
        HStack(spacing: 6) {
            if added > 0 { Text("+\(added)").foregroundStyle(theme.colors.good) }
            if removed > 0 { Text("−\(removed)").foregroundStyle(theme.colors.bad) }
        }
        .font(BenchFont.mono(TypeScale.xs))
    }
}
