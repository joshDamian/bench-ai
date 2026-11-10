import AppKit
import PDFKit
import QuickLookUI
import SwiftUI
import WebKit

// MARK: The panel

/// A file the session made, open beside the conversation: a strip to step through the
/// session's files, a header with what can be done with it, and a viewer for its kind.
struct ArtifactPanel: View {
    @Environment(\.theme) private var theme
    let files: SessionFiles
    let file: FileInfo
    @State private var mode = "view"

    private var kind: ArtifactKind { ArtifactKind(file) }
    private var text: String? { files.texts[file.path] }
    private var previous: String? { files.previous[file.path] }

    var body: some View {
        VStack(spacing: 0) {
            strip
            Divider()
            header
            Divider()
            content.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(theme.colors.bg)
        .onChange(of: file.path) { _, _ in mode = "view" }
        .onAppear { files.seen(file) }
        .onChange(of: file.path) { _, _ in files.seen(file) }
        .background {
            Button("") { files.step(-1) }.keyboardShortcut(.leftArrow, modifiers: [.command, .option]).hidden()
            Button("") { files.step(1) }.keyboardShortcut(.rightArrow, modifiers: [.command, .option]).hidden()
            Button("") { if files.full { files.full = false } else { files.viewing = nil } }.keyboardShortcut(.cancelAction).hidden()
            Button("") { files.full.toggle() }.keyboardShortcut(.return, modifiers: [.command, .shift]).hidden()
        }
    }

    // MARK: strip

    private var strip: some View {
        let all = files.all
        let at = all.firstIndex { $0.path == file.path } ?? 0
        return HStack(spacing: 4) {
            Button { files.step(-1) } label: { Image(systemName: "chevron.left").frame(width: 26, height: 26).contentShape(Rectangle()) }
                .buttonStyle(.plain).help("Previous File (⌥⌘←)").disabled(all.count < 2)
            Button { files.step(1) } label: { Image(systemName: "chevron.right").frame(width: 26, height: 26).contentShape(Rectangle()) }
                .buttonStyle(.plain).help("Next File (⌥⌘→)").disabled(all.count < 2)
            Text("\(at + 1) of \(all.count)").font(BenchFont.mono(11)).foregroundStyle(theme.colors.muted).padding(.horizontal, 4).fixedSize()
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 2) {
                        ForEach(all) { f in
                            let on = f.path == file.path
                            Button { files.preview(f) } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: ArtifactKind(f).icon).font(.system(size: 11)).foregroundStyle(theme.colors.accent)
                                    Text(f.name).font(.system(size: 12)).lineLimit(1)
                                    if files.updated.contains(f.path) { Circle().fill(theme.colors.accent).frame(width: 5, height: 5) }
                                }
                                .padding(.horizontal, 10).frame(height: 28)
                                .foregroundStyle(on ? theme.colors.text : theme.colors.muted)
                                .background(on ? theme.colors.fill(0.08) : .clear, in: RoundedRectangle(cornerRadius: 7))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .id(f.path)
                        }
                    }
                }
                .onAppear { proxy.scrollTo(file.path, anchor: .center) }
                .onChange(of: file.path) { _, p in withAnimation { proxy.scrollTo(p, anchor: .center) } }
            }
        }
        .foregroundStyle(theme.colors.muted)
        .padding(.horizontal, 10)
        .frame(height: 44)
    }

    // MARK: header

    private var modes: [(String, String)] {
        var m: [(String, String)]
        switch kind {
        case .markdown: m = [("view", "Read"), ("source", "Source")]
        case .csv: m = [("view", "Table"), ("source", "Raw")]
        case .html: m = [("view", "Page"), ("source", "Source")]
        case .json: m = [("view", "Formatted"), ("source", "Raw")]
        case .code: m = [("view", "Code")]
        default: m = []
        }
        if previous != nil && kind.isText { m.append(("changes", "Changes")) }
        return m.count > 1 ? m : []
    }

    private var header: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Text(file.name).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                    if previous != nil {
                        Text("UPDATED").font(.system(size: 9.5, weight: .bold)).tracking(0.4)
                            .foregroundStyle(theme.colors.accent)
                            .padding(.horizontal, 5).padding(.vertical, 2)
                            .background(theme.colors.accent.opacity(0.15), in: RoundedRectangle(cornerRadius: 4))
                    }
                }
                Text(meta).font(.system(size: 11)).foregroundStyle(theme.colors.muted).lineLimit(1)
            }
            // Drag the name out to Finder, Mail or Messages.
            .onDrag {
                if let u = files.viewingURL, let p = NSItemProvider(contentsOf: u) { return p }
                return NSItemProvider(object: file.name as NSString)
            }
            Spacer(minLength: 8)
            if !modes.isEmpty {
                Segments(options: modes, selection: $mode, compact: true).fixedSize()
            }
            HStack(spacing: 0) {
                icon("doc.on.doc", "Copy File") { files.copy(file) }
                if let u = files.viewingURL {
                    ShareLink(item: u) { Image(systemName: "square.and.arrow.up").frame(width: 28, height: 28).contentShape(Rectangle()) }
                        .buttonStyle(.plain).help("Share")
                }
                icon("arrow.down.to.line", "Save to Downloads") { files.save(file) }
                icon("arrow.up.forward.app", "Open in Default App") { files.openWith(file) }
                icon(files.full ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
                     files.full ? "Show the Conversation (⇧⌘↩)" : "Full View (⇧⌘↩)") { files.full.toggle() }
                icon("xmark", "Close") { files.viewing = nil; files.full = false }
            }
            .foregroundStyle(theme.colors.muted)
        }
        .padding(.leading, 18).padding(.trailing, 10)
        .frame(height: 52)
    }

    private var meta: String {
        var parts = [file.meta]
        if let previous, let text {
            let d = LineDiff(old: previous, new: text)
            parts.append("+\(d.added) −\(d.removed) since you last looked")
        } else if kind == .csv, let text {
            parts.append("\(max(CSV.parse(text).count - 1, 0)) rows")
        } else if kind.isText, let text {
            parts.append("\(text.components(separatedBy: "\n").count) lines")
        }
        return parts.joined(separator: " · ")
    }

    private func icon(_ name: String, _ help: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: name).font(.system(size: 12)).frame(width: 28, height: 28).contentShape(Rectangle()) }
            .buttonStyle(.plain).help(help).accessibilityLabel(help)
    }

    // MARK: content

    @ViewBuilder private var content: some View {
        if mode == "changes", let previous, let text {
            FileChangesView(old: previous, new: text)
        } else if let url = files.viewingURL {
            switch kind {
            case .markdown:
                if mode == "source", let text { codeView(text, "md") } else if let text { MarkdownDoc(text: text) } else { loading }
            case .json:
                if let text { codeView(mode == "source" ? text : JSONPretty.format(text), "json") } else { loading }
            case .csv:
                if let text { if mode == "source" { codeView(text, "csv") } else { CSVTable(text: text) } } else { loading }
            case .html:
                if mode == "source", let text { codeView(text, "html") } else { WebPage(url: url) }
            case .code:
                if let text { codeView(text, FilesView.language(of: file.name)) } else { QuickLookView(url: url) }
            case .image: ImageViewer(url: url)
            case .pdf: PDFViewer(url: url)
            case .other: QuickLookView(url: url)
            }
        } else {
            loading
        }
    }

    private var loading: some View { ProgressView().controlSize(.small).frame(maxWidth: .infinity, maxHeight: .infinity) }

    private func codeView(_ t: String, _ lang: String) -> some View {
        CodeTextView(text: .constant(t), editable: false, language: lang, dark: theme.dark, base: theme.colors.text,
                     gutter: theme.colors.muted, caret: .constant((line: 1, col: 1)))
            .id(file.path + lang + String(t.count))
    }
}

/// What kind of viewer a file gets.
enum ArtifactKind: Equatable {
    case markdown, json, csv, html, code, image, pdf, other

    init(_ f: FileInfo) {
        let ext = (f.name as NSString).pathExtension.lowercased()
        switch ext {
        case "md", "markdown", "mdx": self = .markdown
        case "json", "jsonc", "geojson": self = .json
        case "csv", "tsv": self = .csv
        case "html", "htm": self = .html
        case "png", "jpg", "jpeg", "gif", "webp", "heic", "bmp", "tiff", "svg": self = ext == "svg" ? .html : .image
        case "pdf": self = .pdf
        default: self = (f.kind == "text" || ArtifactKind.codeExts.contains(ext)) ? .code : (f.kind == "image" ? .image : f.kind == "pdf" ? .pdf : .other)
        }
    }

    static let codeExts: Set<String> = ["ts", "tsx", "js", "mjs", "cjs", "jsx", "swift", "kt", "kts", "py", "go", "rs", "sh", "bash", "zsh", "css",
                                        "scss", "sql", "yml", "yaml", "toml", "rb", "java", "c", "h", "cpp", "m", "txt", "log", "xml", "env", "ini", "conf"]

    var isText: Bool { [.markdown, .json, .csv, .html, .code].contains(self) }

    var icon: String {
        switch self {
        case .markdown: "doc.richtext"
        case .json: "curlybraces"
        case .csv: "tablecells"
        case .html: "globe"
        case .code: "chevron.left.forwardslash.chevron.right"
        case .image: "photo"
        case .pdf: "doc.text.image"
        case .other: "doc"
        }
    }
}

// MARK: Toast

struct ToastView: View {
    @Environment(\.theme) private var theme
    let files: SessionFiles

    var body: some View {
        if let t = files.toast {
            HStack(spacing: 12) {
                Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(theme.colors.good)
                Text(t.text).font(.system(size: 13))
                if let r = t.reveal {
                    Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([r]); files.toast = nil }
                        .buttonStyle(.plain).font(.system(size: 13, weight: .medium)).foregroundStyle(theme.colors.accent)
                }
            }
            .padding(.horizontal, 14).frame(height: 38)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(theme.colors.fill(0.1)))
            .shadow(color: .black.opacity(0.25), radius: 14, y: 6)
            .padding(.bottom, 24)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .id(t.id)
        }
    }
}

// MARK: Markdown

/// A Markdown file to read: an outline of its headings beside it, and ⌘F to find in it.
struct MarkdownDoc: View {
    @Environment(\.theme) private var theme
    let text: String
    @State private var query = ""
    @State private var finding = false
    @State private var at = 0
    @State private var section = 0
    @FocusState private var findFocused: Bool

    private struct Section: Identifiable { let id: Int; let title: String?; let level: Int; let body: String }

    /// The document cut at each heading outside a code fence.
    private var sections: [Section] {
        var out: [Section] = []
        var title: String?, level = 0, body: [String] = []
        var fence = false
        func flush() {
            if title != nil || !body.joined().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                out.append(Section(id: out.count, title: title, level: level, body: body.joined(separator: "\n")))
            }
        }
        for line in text.components(separatedBy: "\n") {
            if line.hasPrefix("```") || line.hasPrefix("~~~") { fence.toggle() }
            if !fence, let m = line.range(of: #"^#{1,3} "#, options: .regularExpression) {
                flush()
                level = line[m].filter { $0 == "#" }.count
                title = String(line[m.upperBound...]).trimmingCharacters(in: .whitespaces)
                body = [line]
            } else {
                body.append(line)
            }
        }
        flush()
        return out
    }

    /// The sections holding a match, in order.
    private var hits: [Int] {
        guard !query.isEmpty else { return [] }
        return sections.filter { $0.body.localizedCaseInsensitiveContains(query) }.map(\.id)
    }

    var body: some View {
        let secs = sections
        let outline = secs.filter { $0.title != nil }
        ScrollViewReader { proxy in
            VStack(spacing: 0) {
                if finding {
                    findBar(proxy)
                    Divider()
                }
                HStack(alignment: .top, spacing: 0) {
                    if outline.count > 1 {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 2) {
                                ForEach(outline) { s in
                                    Button {
                                        section = s.id
                                        withAnimation(.snappy) { proxy.scrollTo(s.id, anchor: .top) }
                                    } label: {
                                        Text(s.title ?? "").font(.system(size: 12)).lineLimit(2)
                                            .foregroundStyle(section == s.id ? theme.colors.text : theme.colors.muted)
                                            .padding(.leading, CGFloat(max(s.level - 1, 0)) * 10 + 8)
                                            .padding(.vertical, 4)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .overlay(alignment: .leading) {
                                                if section == s.id { Rectangle().fill(theme.colors.accent).frame(width: 2) }
                                            }
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.top, 22).padding(.leading, 14)
                        }
                        .frame(width: 168)
                    }
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(secs) { s in
                                BenchMarkdown(text: s.body)
                                    .environment(\.findQuery, query)
                                    .environment(\.findCurrent, hits[safe: at] == s.id)
                                    .textSelection(.enabled)
                                    .padding(.bottom, 6)
                                    .id(s.id)
                                    .onAppear { if s.title != nil && abs(s.id - section) == 1 { section = s.id } }
                            }
                        }
                        .frame(maxWidth: 640, alignment: .leading)
                        .padding(.horizontal, 28).padding(.vertical, 22)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .background {
                Button("") { finding = true; findFocused = true }.keyboardShortcut("f", modifiers: .command).hidden()
            }
        }
    }

    private func findBar(_ proxy: ScrollViewProxy) -> some View {
        HStack(spacing: Space.sm) {
            Image(systemName: "magnifyingglass").font(.system(size: 11)).foregroundStyle(theme.colors.muted)
            TextField("Find in document", text: $query).textFieldStyle(.plain).font(.system(size: 12.5)).focused($findFocused)
                .onSubmit { next(proxy, 1) }
                .onChange(of: query) { _, _ in at = 0; if let h = hits.first { proxy.scrollTo(h, anchor: .top) } }
            if !query.isEmpty {
                Text(hits.isEmpty ? "No matches" : "\(at + 1) of \(hits.count)").font(BenchFont.mono(11)).foregroundStyle(theme.colors.muted)
            }
            Button { next(proxy, -1) } label: { Image(systemName: "chevron.up") }.buttonStyle(.plain).disabled(hits.isEmpty)
            Button { next(proxy, 1) } label: { Image(systemName: "chevron.down") }.buttonStyle(.plain).disabled(hits.isEmpty)
            Button("Done") { finding = false; query = "" }.buttonStyle(.plain).font(.system(size: 12, weight: .medium))
                .keyboardShortcut(.cancelAction)
        }
        .foregroundStyle(theme.colors.muted)
        .padding(.horizontal, 18).frame(height: 36)
    }

    private func next(_ proxy: ScrollViewProxy, _ by: Int) {
        guard !hits.isEmpty else { return }
        at = (at + by + hits.count) % hits.count
        withAnimation(.snappy) { proxy.scrollTo(hits[at], anchor: .top) }
    }
}

// MARK: Changes

/// Lines added and removed between two versions of a file: a longest-common-subsequence
/// diff over lines, via the standard library's `difference`.
struct LineDiff {
    enum Kind { case same, add, del }
    struct Line: Identifiable { let id: Int; let kind: Kind; let number: Int?; let text: String }
    let lines: [Line]
    let added: Int
    let removed: Int

    init(old: String, new: String) {
        let a = old.components(separatedBy: "\n"), b = new.components(separatedBy: "\n")
        let diff = b.difference(from: a)
        var removedAt = Set<Int>(), insertedAt = Set<Int>()
        for c in diff {
            switch c {
            case .remove(let o, _, _): removedAt.insert(o)
            case .insert(let o, _, _): insertedAt.insert(o)
            }
        }
        var out: [Line] = []
        var i = 0, j = 0
        while i < a.count || j < b.count {
            if i < a.count, removedAt.contains(i) {
                out.append(Line(id: out.count, kind: .del, number: nil, text: a[i])); i += 1
            } else if j < b.count, insertedAt.contains(j) {
                out.append(Line(id: out.count, kind: .add, number: j + 1, text: b[j])); j += 1
            } else {
                if j < b.count { out.append(Line(id: out.count, kind: .same, number: j + 1, text: b[j])) }
                i += 1; j += 1
            }
        }
        lines = out
        added = insertedAt.count
        removed = removedAt.count
    }
}

struct FileChangesView: View {
    @Environment(\.theme) private var theme
    let old: String
    let new: String

    var body: some View {
        let d = LineDiff(old: old, new: new)
        ScrollView([.vertical, .horizontal]) {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(d.lines) { l in
                    HStack(spacing: 0) {
                        Text(l.number.map(String.init) ?? "").frame(width: 44, alignment: .trailing).padding(.trailing, 10)
                            .foregroundStyle(theme.colors.muted.opacity(0.6))
                        Text(l.kind == .add ? "+" : l.kind == .del ? "−" : " ").frame(width: 14)
                            .foregroundStyle(l.kind == .add ? theme.colors.good : l.kind == .del ? theme.colors.bad : .clear)
                        Text(l.text.isEmpty ? " " : l.text).opacity(l.kind == .del ? 0.7 : 1).fixedSize()
                        Spacer(minLength: 0)
                    }
                    .font(BenchFont.mono(12.5))
                    .frame(height: 21)
                    .background(l.kind == .add ? theme.colors.good.opacity(0.1) : l.kind == .del ? theme.colors.bad.opacity(0.1) : .clear)
                }
            }
            .padding(.vertical, 14)
            .textSelection(.enabled)
        }
    }
}

// MARK: JSON, CSV

enum JSONPretty {
    static func format(_ s: String) -> String {
        guard let d = s.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed]),
              let out = try? JSONSerialization.data(withJSONObject: o, options: [.prettyPrinted, .withoutEscapingSlashes]),
              let t = String(data: out, encoding: .utf8) else { return s }
        return t
    }
}

enum CSV {
    /// Rows of fields, with quoted fields and commas or tabs, whichever the header uses.
    static func parse(_ text: String, limit: Int = 5000) -> [[String]] {
        let sep: Character = (text.prefix(while: { $0 != "\n" }).filter { $0 == "\t" }.count > text.prefix(while: { $0 != "\n" }).filter { $0 == "," }.count) ? "\t" : ","
        var rows: [[String]] = [], row: [String] = [], field = "", quoted = false
        var it = text.makeIterator()
        while let c = it.next() {
            if quoted {
                if c == "\"" {
                    if let n = it.next() {
                        if n == "\"" { field.append("\"") } else { quoted = false; if n == sep { row.append(field); field = "" } else if n == "\n" { row.append(field); rows.append(row); row = []; field = "" } else if n != "\r" { field.append(n) } }
                    } else { quoted = false }
                } else { field.append(c) }
            } else if c == "\"" && field.isEmpty { quoted = true }
            else if c == sep { row.append(field); field = "" }
            else if c == "\n" { row.append(field); rows.append(row); row = []; field = ""; if rows.count >= limit { return rows } }
            else if c != "\r" { field.append(c) }
        }
        if !field.isEmpty || !row.isEmpty { row.append(field); rows.append(row) }
        return rows
    }
}

/// A CSV as a table: the header stays put, a column sorts on click, rows filter as you type.
struct CSVTable: View {
    @Environment(\.theme) private var theme
    let text: String
    @State private var filter = ""
    @State private var sortColumn: Int?
    @State private var ascending = true

    var body: some View {
        let all = CSV.parse(text)
        let head = all.first ?? []
        let shown = rows(Array(all.dropFirst()))
        let widths = columnWidths(all)
        VStack(spacing: 0) {
            HStack(spacing: Space.sm) {
                Image(systemName: "line.3.horizontal.decrease").font(.system(size: 11)).foregroundStyle(theme.colors.muted)
                TextField("Filter rows", text: $filter).textFieldStyle(.plain).font(.system(size: 12.5))
                Text("\(shown.count) of \(max(all.count - 1, 0)) rows").font(BenchFont.mono(11)).foregroundStyle(theme.colors.muted)
            }
            .padding(.horizontal, 18).frame(height: 36)
            Divider()
            ScrollView([.vertical, .horizontal]) {
                LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                    Section {
                        ForEach(Array(shown.enumerated()), id: \.offset) { i, r in
                            HStack(spacing: 0) {
                                ForEach(0..<head.count, id: \.self) { c in
                                    cell(r[safe: c] ?? "", width: widths[safe: c] ?? 120)
                                }
                            }
                            .background(i % 2 == 1 ? theme.colors.fill(0.025) : .clear)
                            Divider().opacity(0.5)
                        }
                    } header: {
                        HStack(spacing: 0) {
                            ForEach(Array(head.enumerated()), id: \.offset) { c, h in
                                Button {
                                    if sortColumn == c { ascending.toggle() } else { sortColumn = c; ascending = true }
                                } label: {
                                    HStack(spacing: 4) {
                                        Text(h).lineLimit(1)
                                        if sortColumn == c { Image(systemName: ascending ? "chevron.up" : "chevron.down").font(.system(size: 8, weight: .bold)) }
                                    }
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(theme.colors.muted)
                                    .padding(.horizontal, 12).frame(width: widths[safe: c] ?? 120, height: 32, alignment: numeric(c, shown) ? .trailing : .leading)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .background(theme.colors.surface2)
                    }
                }
                .textSelection(.enabled)
            }
        }
    }

    private func cell(_ s: String, width: CGFloat) -> some View {
        let num = Double(s.replacingOccurrences(of: #"[$,%₦ ]"#, with: "", options: .regularExpression)) != nil
        return Text(s).font(num ? BenchFont.mono(12) : .system(size: 12.5)).lineLimit(1)
            .padding(.horizontal, 12).frame(width: width, height: 30, alignment: num ? .trailing : .leading)
    }

    private func numeric(_ c: Int, _ rows: [[String]]) -> Bool {
        guard let v = rows.first?[safe: c] else { return false }
        return Double(v.replacingOccurrences(of: #"[$,%₦ ]"#, with: "", options: .regularExpression)) != nil
    }

    private func rows(_ r: [[String]]) -> [[String]] {
        var out = filter.isEmpty ? r : r.filter { $0.contains { $0.localizedCaseInsensitiveContains(filter) } }
        if let c = sortColumn {
            let num = numeric(c, out)
            out.sort { a, b in
                let x = a[safe: c] ?? "", y = b[safe: c] ?? ""
                let less: Bool
                if num, let dx = Double(x.replacingOccurrences(of: #"[$,%₦ ]"#, with: "", options: .regularExpression)),
                   let dy = Double(y.replacingOccurrences(of: #"[$,%₦ ]"#, with: "", options: .regularExpression)) { less = dx < dy }
                else { less = x.localizedStandardCompare(y) == .orderedAscending }
                return ascending ? less : !less
            }
        }
        return out
    }

    private func columnWidths(_ rows: [[String]]) -> [CGFloat] {
        let cols = rows.first?.count ?? 0
        return (0..<cols).map { c in
            let longest = rows.prefix(200).map { ($0[safe: c] ?? "").count }.max() ?? 8
            return min(max(CGFloat(longest) * 7.6 + 26, 80), 320)
        }
    }
}

// MARK: Images, PDFs, pages, anything else

/// An image to fit or see at full size, on a checkerboard so transparency shows.
struct ImageViewer: View {
    let url: URL
    @State private var zoom: CGFloat? = nil
    @State private var image: NSImage?

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topTrailing) {
                Checkerboard()
                if let image {
                    let fit = min(geo.size.width / max(image.size.width, 1), geo.size.height / max(image.size.height, 1), 1)
                    let scale = zoom ?? fit
                    ScrollView([.horizontal, .vertical]) {
                        Image(nsImage: image).resizable().interpolation(scale > 1 ? .none : .high)
                            .frame(width: image.size.width * scale, height: image.size.height * scale)
                            .frame(minWidth: geo.size.width, minHeight: geo.size.height)
                    }
                    HStack(spacing: 4) {
                        zoomButton("Fit", on: zoom == nil) { zoom = nil }
                        zoomButton("100%", on: zoom == 1) { zoom = 1 }
                        zoomButton("−", on: false) { zoom = max((zoom ?? fit) / 1.25, 0.05) }
                        zoomButton("+", on: false) { zoom = min((zoom ?? fit) * 1.25, 8) }
                    }
                    .padding(4)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 9))
                    .padding(12)
                } else {
                    ProgressView().controlSize(.small).frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
        .task(id: url) { image = NSImage(contentsOf: url) }
    }

    private func zoomButton(_ t: String, on: Bool, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Text(t).font(.system(size: 11.5, weight: .medium)).frame(minWidth: 26).padding(.horizontal, 6).frame(height: 24)
                .background(on ? Color.primary.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 6))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct Checkerboard: View {
    @Environment(\.theme) private var theme
    var body: some View {
        Canvas { ctx, size in
            let s: CGFloat = 8
            for x in stride(from: 0, to: size.width, by: s) {
                for y in stride(from: 0, to: size.height, by: s) where (Int(x / s) + Int(y / s)) % 2 == 0 {
                    ctx.fill(Path(CGRect(x: x, y: y, width: s, height: s)), with: .color(theme.colors.fill(0.05)))
                }
            }
        }
    }
}

/// A PDF with page thumbnails down the side.
struct PDFViewer: NSViewRepresentable {
    let url: URL
    func makeNSView(context: Context) -> NSSplitView {
        let split = NSSplitView()
        split.isVertical = true
        split.dividerStyle = .thin
        let pdf = PDFView()
        pdf.autoScales = true
        pdf.displayMode = .singlePageContinuous
        pdf.backgroundColor = .clear
        let thumbs = PDFThumbnailView()
        thumbs.pdfView = pdf
        thumbs.thumbnailSize = NSSize(width: 64, height: 84)
        thumbs.backgroundColor = .clear
        split.addArrangedSubview(thumbs)
        split.addArrangedSubview(pdf)
        thumbs.widthAnchor.constraint(equalToConstant: 96).isActive = true
        pdf.document = PDFDocument(url: url)
        return split
    }
    func updateNSView(_ split: NSSplitView, context: Context) {
        guard let pdf = split.arrangedSubviews.last as? PDFView else { return }
        if pdf.document?.documentURL != url { pdf.document = PDFDocument(url: url) }
    }
}

/// An HTML page or SVG, rendered from the local copy; links open in the browser.
struct WebPage: NSViewRepresentable {
    let url: URL
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> WKWebView {
        let w = WKWebView()
        w.navigationDelegate = context.coordinator
        w.setValue(false, forKey: "drawsBackground")
        w.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        return w
    }
    func updateNSView(_ w: WKWebView, context: Context) {
        if w.url != url { w.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent()) }
    }
    final class Coordinator: NSObject, WKNavigationDelegate {
        @MainActor
        func webView(_ w: WKWebView, decidePolicyFor a: WKNavigationAction) async -> WKNavigationActionPolicy {
            if a.navigationType == .linkActivated, let u = a.request.url, !u.isFileURL { NSWorkspace.shared.open(u); return .cancel }
            return .allow
        }
    }
}

/// Anything else, as Quick Look shows it.
struct QuickLookView: NSViewRepresentable {
    let url: URL
    func makeNSView(context: Context) -> QLPreviewView {
        let v = QLPreviewView(frame: .zero, style: .normal)!
        v.previewItem = url as NSURL
        return v
    }
    func updateNSView(_ v: QLPreviewView, context: Context) {
        if (v.previewItem as? NSURL)?.absoluteURL != url { v.previewItem = url as NSURL }
    }
}

// MARK: Card previews

/// What a card shows of its file before it's opened: an image, a PDF's first page, the
/// start of a text, or a CSV's first rows.
struct ArtifactPreview: View {
    @Environment(\.theme) private var theme
    let files: SessionFiles
    let file: FileInfo
    @State private var image: NSImage?

    var body: some View {
        let kind = ArtifactKind(file)
        Group {
            if let image {
                ZStack { Checkerboard(); Image(nsImage: image).resizable().aspectRatio(contentMode: .fit).padding(8) }
            } else if let text = files.texts[file.path] {
                switch kind {
                case .csv:
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(CSV.parse(text, limit: 6).enumerated()), id: \.offset) { i, r in
                            HStack(spacing: 10) {
                                ForEach(Array(r.prefix(3).enumerated()), id: \.offset) { _, v in Text(v).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading) }
                            }
                            .font(BenchFont.mono(10.5))
                            .foregroundStyle(i == 0 ? theme.colors.muted : theme.colors.text)
                            .padding(.horizontal, 12).frame(height: 22)
                            Divider().opacity(0.5)
                        }
                        Spacer(minLength: 0)
                    }
                case .markdown:
                    BenchMarkdown(text: String(text.prefix(500))).scaleEffect(0.78, anchor: .topLeading)
                        .frame(width: 380, alignment: .topLeading).padding(12)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                default:
                    Text(BenchHighlighter(dark: theme.dark, base: theme.colors.text)
                        .attributed(text.components(separatedBy: "\n").prefix(9).joined(separator: "\n"), language: FilesView.language(of: file.name)))
                        .font(BenchFont.mono(10.5)).lineSpacing(3)
                        .padding(12).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
            } else {
                Image(systemName: kind.icon).font(.system(size: 26)).foregroundStyle(theme.colors.accent.opacity(0.8))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .clipped()
        .task(id: file.path) {
            // Small files only: a preview should never be the reason a reply is slow.
            guard file.size < 4_000_000, let url = await files.local(file) else { return }
            switch kind {
            case .image: image = NSImage(contentsOf: url)
            case .pdf:
                if let page = PDFDocument(url: url)?.page(at: 0) { image = page.thumbnail(of: NSSize(width: 400, height: 520), for: .mediaBox) }
            default: break
            }
        }
    }
}

/// The line between the conversation and the viewer: drag to resize, double-click to reset.
struct PanelDivider: View {
    @Environment(\.theme) private var theme
    @Binding var width: Double
    @State private var start: Double?
    @State private var hover = false

    var body: some View {
        Rectangle().fill(theme.colors.fill(hover || start != nil ? 0.2 : 0.08)).frame(width: 1)
            .overlay {
                Color.clear.frame(width: 9).contentShape(Rectangle())
                    .onHover { h in
                        hover = h
                        if h { NSCursor.resizeLeftRight.push() } else { NSCursor.pop() }
                    }
                    .gesture(DragGesture(minimumDistance: 1, coordinateSpace: .global)
                        .onChanged { v in
                            if start == nil { start = width }
                            width = min(max((start ?? width) - v.translation.width, 380), 1400)
                        }
                        .onEnded { _ in start = nil })
                    .onTapGesture(count: 2) { withAnimation(.snappy) { width = 640 } }
            }
            .zIndex(1)
    }
}
