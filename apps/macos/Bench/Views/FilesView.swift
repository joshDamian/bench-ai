import AppKit
import SwiftUI

/// A project's files on the server: a tree to browse, and one file to read with syntax
/// colours or, after ⌘E, to fix and save. For quick changes without a session, an editor
/// or nano on the server; anything bigger goes to Claude or the person's own editor.
struct FilesView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let projectId: String

    @State private var paths: [String] = []
    @State private var loadError: String?
    @State private var open: Set<String> = []
    @State private var filter = ""
    @State private var current: String?
    @State private var text = ""
    @State private var saved = ""
    @State private var mtime: Double = 0
    @State private var editing = false
    @State private var fileError: String?
    @State private var saving = false
    @State private var caret = (line: 1, col: 1)
    @FocusState private var filterFocused: Bool

    private var dirty: Bool { editing && text != saved }

    var body: some View {
        HSplitView {
            tree.frame(minWidth: 200, idealWidth: 260, maxWidth: 420)
            file.frame(minWidth: 420, maxWidth: .infinity)
        }
        .background(theme.colors.bg)
        .task { await loadTree() }
        .background {
            Button("") { filterFocused = true }.keyboardShortcut("p", modifiers: .command).hidden()
            Button("") { if current != nil && !editing { editing = true } }.keyboardShortcut("e", modifiers: .command).hidden()
            Button("") { Task { await save() } }.keyboardShortcut("s", modifiers: .command).hidden().disabled(!dirty)
        }
    }

    // MARK: tree

    private var tree: some View {
        VStack(spacing: 0) {
            HStack(spacing: Space.sm) {
                Image(systemName: "magnifyingglass").font(.system(size: 11)).foregroundStyle(theme.colors.muted)
                TextField("Go to file", text: $filter).textFieldStyle(.plain).font(.system(size: 12.5)).focused($filterFocused)
                    .onSubmit { if let p = matches.first { pick(p) } }
                Text("⌘P").font(BenchFont.mono(11)).foregroundStyle(theme.colors.muted)
            }
            .padding(.horizontal, 10).frame(height: 30)
            .background(theme.colors.fill(0.07), in: RoundedRectangle(cornerRadius: 8))
            .padding(Space.md)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 1) {
                    if let loadError {
                        Text(loadError).font(.system(size: 12)).foregroundStyle(theme.colors.bad).padding(Space.md)
                    } else if filter.isEmpty {
                        ForEach(rows, id: \.path) { r in row(r) }
                    } else {
                        ForEach(matches, id: \.self) { p in
                            fileRow(name: (p as NSString).lastPathComponent, sub: (p as NSString).deletingLastPathComponent, depth: 0, path: p)
                        }
                    }
                }
                .padding(.horizontal, Space.sm).padding(.bottom, Space.md)
            }
        }
        .background(theme.colors.surface1)
    }

    private struct Row { let path: String; let name: String; let depth: Int; let dir: Bool }

    /// The tree flattened to the rows showing now: folders first, open ones expanded.
    private var rows: [Row] {
        var kids: [String: (dirs: Set<String>, files: [String])] = [:]
        for p in paths {
            let parts = p.split(separator: "/").map(String.init)
            var parent = ""
            for (i, part) in parts.enumerated() {
                let here = parent.isEmpty ? part : parent + "/" + part
                if i == parts.count - 1 { kids[parent, default: ([], [])].files.append(here) }
                else { kids[parent, default: ([], [])].dirs.insert(here) }
                parent = here
            }
        }
        var out: [Row] = []
        func walk(_ dir: String, _ depth: Int) {
            guard let k = kids[dir] else { return }
            for d in k.dirs.sorted(by: { $0.localizedStandardCompare($1) == .orderedAscending }) {
                out.append(Row(path: d, name: (d as NSString).lastPathComponent, depth: depth, dir: true))
                if open.contains(d) { walk(d, depth + 1) }
            }
            for f in k.files.sorted(by: { $0.localizedStandardCompare($1) == .orderedAscending }) {
                out.append(Row(path: f, name: (f as NSString).lastPathComponent, depth: depth, dir: false))
            }
        }
        walk("", 0)
        return out
    }

    private var matches: [String] {
        let q = filter.lowercased()
        let named = paths.filter { ($0 as NSString).lastPathComponent.lowercased().contains(q) }
        let rest = paths.filter { !($0 as NSString).lastPathComponent.lowercased().contains(q) && $0.lowercased().contains(q) }
        return Array((named.sorted { $0.count < $1.count } + rest).prefix(60))
    }

    @ViewBuilder private func row(_ r: Row) -> some View {
        if r.dir {
            Button {
                if open.contains(r.path) { open.remove(r.path) } else { open.insert(r.path) }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: open.contains(r.path) ? "chevron.down" : "chevron.right")
                        .font(.system(size: 9, weight: .semibold)).foregroundStyle(theme.colors.muted).frame(width: 12)
                    Image(systemName: "folder").font(.system(size: 11)).foregroundStyle(theme.colors.muted)
                    Text(r.name).font(.system(size: 13)).lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.leading, CGFloat(r.depth) * 14 + 6).frame(height: 26).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            fileRow(name: r.name, sub: nil, depth: r.depth, path: r.path)
        }
    }

    private func fileRow(name: String, sub: String?, depth: Int, path: String) -> some View {
        Button { pick(path) } label: {
            HStack(spacing: 6) {
                Color.clear.frame(width: 12, height: 1)
                Image(systemName: "doc").font(.system(size: 11)).foregroundStyle(theme.colors.muted)
                Text(name).font(.system(size: 13)).lineLimit(1)
                if let sub, !sub.isEmpty {
                    Text(sub).font(.system(size: 11)).foregroundStyle(theme.colors.muted).lineLimit(1).truncationMode(.head)
                }
                Spacer(minLength: 0)
            }
            .padding(.leading, CGFloat(depth) * 14 + 6).frame(height: 26)
            .background(current == path ? theme.colors.accent.opacity(0.14) : .clear, in: RoundedRectangle(cornerRadius: 6))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: file

    @ViewBuilder private var file: some View {
        VStack(spacing: 0) {
            if let current {
                HStack(spacing: 10) {
                    crumbs(current)
                    if dirty { Circle().fill(theme.colors.text.opacity(0.7)).frame(width: 7, height: 7).help("Not saved") }
                    Spacer(minLength: 0)
                    if editing {
                        Button("Discard") { text = saved; editing = false }.buttonStyle(TonalButton(height: 26))
                        Button(saving ? "Saving…" : "Save  ⌘S") { Task { await save() } }
                            .buttonStyle(PrimaryButton(height: 26)).disabled(!dirty || saving)
                    } else {
                        Button("Copy path") {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(current, forType: .string)
                        }
                        .buttonStyle(TonalButton(height: 26))
                        Button("Edit  ⌘E") { editing = true }.buttonStyle(TonalButton(height: 26)).disabled(fileError != nil)
                    }
                }
                .padding(.leading, Space.xl).padding(.trailing, Space.lg).frame(height: 46)
                Divider()
                if let fileError {
                    Text(fileError).font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    CodeTextView(text: $text, editable: editing, language: Self.language(of: current),
                                 dark: theme.dark, base: theme.colors.text, gutter: theme.colors.muted, caret: $caret)
                }
                Divider()
                HStack(spacing: 18) {
                    Text(editing ? "Ln \(caret.line), Col \(caret.col)" : "\(text.components(separatedBy: "\n").count) lines · \(ByteCountFormatter.string(fromByteCount: Int64(text.utf8.count), countStyle: .file))")
                    Text(Self.languageName(of: current))
                    Spacer(minLength: 0)
                    Text(editing ? (dirty ? "Edited · not saved" : "Editing") : "Read only · ⌘E to edit")
                }
                .font(BenchFont.mono(11.5)).foregroundStyle(theme.colors.muted)
                .padding(.horizontal, Space.xl).frame(height: 28)
            } else {
                VStack(spacing: Space.sm) {
                    Image(systemName: "doc.text").font(.system(size: 26)).foregroundStyle(theme.colors.muted)
                    Text("Pick a file to read it").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private func crumbs(_ p: String) -> some View {
        let dir = (p as NSString).deletingLastPathComponent
        return (Text(dir.isEmpty ? "" : dir.replacingOccurrences(of: "/", with: " / ") + " / ").foregroundColor(theme.colors.muted)
                + Text((p as NSString).lastPathComponent).foregroundColor(theme.colors.text))
            .font(BenchFont.mono(13)).lineLimit(1).truncationMode(.head)
    }

    // MARK: loading and saving

    private func loadTree() async {
        do { paths = try await store.api.tree(projectId); loadError = nil }
        catch { loadError = error.localizedDescription }
    }

    private func pick(_ p: String) {
        guard p != current else { return }
        if dirty {
            let a = NSAlert()
            a.messageText = "Discard your changes to \((current! as NSString).lastPathComponent)?"
            a.addButton(withTitle: "Discard"); a.addButton(withTitle: "Cancel")
            if a.runModal() != .alertFirstButtonReturn { return }
        }
        current = p; editing = false; fileError = nil; text = ""; saved = ""
        // Open the folders above it, so the tree shows where it is.
        var d = (p as NSString).deletingLastPathComponent
        while !d.isEmpty { open.insert(d); d = (d as NSString).deletingLastPathComponent }
        Task {
            do {
                let f = try await store.api.text(projectId, path: p)
                guard current == p else { return }
                text = f.text; saved = f.text; mtime = f.mtime
            } catch {
                if current == p { fileError = error.localizedDescription }
            }
        }
    }

    private func save() async {
        guard let current, dirty, !saving else { return }
        saving = true
        defer { saving = false }
        do {
            mtime = try await store.api.save(projectId, path: current, text: text, mtime: mtime)
            saved = text
        } catch let e as BenchError where e.status == 409 {
            let a = NSAlert()
            a.messageText = "\((current as NSString).lastPathComponent) changed on the server since you opened it."
            a.informativeText = "Saving now replaces that change with yours."
            a.addButton(withTitle: "Replace"); a.addButton(withTitle: "Cancel")
            if a.runModal() == .alertFirstButtonReturn {
                mtime = 0
                if let m = try? await store.api.save(projectId, path: current, text: text, mtime: 0) { mtime = m; saved = text }
            }
        } catch {
            let a = NSAlert(); a.messageText = "Couldn't save"; a.informativeText = error.localizedDescription; a.runModal()
        }
    }

    // MARK: languages

    static func language(of p: String) -> String {
        let ext = (p as NSString).pathExtension.lowercased()
        let name = (p as NSString).lastPathComponent.lowercased()
        if name == "dockerfile" { return "dockerfile" }
        if name == "makefile" { return "makefile" }
        if name.hasPrefix(".env") { return "sh" }
        return ["ts": "ts", "tsx": "ts", "js": "js", "mjs": "js", "cjs": "js", "jsx": "js", "swift": "swift", "kt": "kotlin", "kts": "kotlin",
                "py": "python", "go": "go", "rs": "rust", "sh": "sh", "bash": "sh", "zsh": "sh", "json": "json", "css": "css", "scss": "css",
                "sql": "sql", "yml": "yaml", "yaml": "yaml", "toml": "toml", "rb": "ruby"][ext] ?? ext
    }

    static func languageName(of p: String) -> String {
        let ext = (p as NSString).pathExtension.lowercased()
        return ["ts": "TypeScript", "tsx": "TypeScript", "js": "JavaScript", "mjs": "JavaScript", "cjs": "JavaScript", "jsx": "JavaScript",
                "swift": "Swift", "kt": "Kotlin", "kts": "Kotlin", "py": "Python", "go": "Go", "rs": "Rust", "sh": "Shell", "json": "JSON",
                "css": "CSS", "scss": "SCSS", "sql": "SQL", "yml": "YAML", "yaml": "YAML", "toml": "TOML", "md": "Markdown", "html": "HTML",
                "rb": "Ruby"][ext] ?? (ext.isEmpty ? "Plain text" : ext.uppercased())
    }
}

/// An NSTextView for code: JetBrains Mono, syntax colours, line numbers, read only or editable.
struct CodeTextView: NSViewRepresentable {
    @Binding var text: String
    let editable: Bool
    let language: String
    let dark: Bool
    let base: Color
    let gutter: Color
    @Binding var caret: (line: Int, col: Int)

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSTextView.scrollableTextView()
        let tv = scroll.documentView as! NSTextView
        tv.delegate = context.coordinator
        tv.font = BenchFont.monoNS(13)
        tv.isRichText = false
        tv.allowsUndo = true
        tv.isAutomaticQuoteSubstitutionEnabled = false
        tv.isAutomaticDashSubstitutionEnabled = false
        tv.isAutomaticTextReplacementEnabled = false
        tv.isAutomaticSpellingCorrectionEnabled = false
        tv.isContinuousSpellCheckingEnabled = false
        tv.smartInsertDeleteEnabled = false
        tv.drawsBackground = false
        tv.textContainerInset = NSSize(width: 8, height: 14)
        let para = NSMutableParagraphStyle(); para.minimumLineHeight = 22; para.maximumLineHeight = 22
        tv.defaultParagraphStyle = para
        scroll.drawsBackground = false
        // Nothing may draw outside the view: on macOS 14 views don't clip by default, and
        // the ruler's edge line ran up through the headers above it.
        scroll.automaticallyAdjustsContentInsets = false
        scroll.contentInsets = NSEdgeInsetsZero
        scroll.clipsToBounds = true
        let ruler = LineNumbers(textView: tv)
        ruler.clipsToBounds = true
        scroll.verticalRulerView = ruler
        scroll.hasVerticalRuler = true
        scroll.rulersVisible = true
        context.coordinator.apply(tv, text: text)
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        context.coordinator.parent = self
        let tv = scroll.documentView as! NSTextView
        tv.isEditable = editable
        tv.insertionPointColor = NSColor.controlAccentColor
        (scroll.verticalRulerView as? LineNumbers)?.color = NSColor(gutter).withAlphaComponent(0.7)
        if tv.string != text { context.coordinator.apply(tv, text: text) }
        if editable && tv.window?.firstResponder !== tv { DispatchQueue.main.async { tv.window?.makeFirstResponder(tv) } }
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: CodeTextView
        private var pending: DispatchWorkItem?
        init(_ p: CodeTextView) { parent = p }

        func apply(_ tv: NSTextView, text: String) {
            let sel = tv.selectedRanges
            tv.textStorage?.setAttributedString(colored(text))
            tv.selectedRanges = sel.filter { $0.rangeValue.upperBound <= (text as NSString).length }.isEmpty ? [NSValue(range: NSRange(location: 0, length: 0))] : sel
            tv.enclosingScrollView?.verticalRulerView?.needsDisplay = true
        }

        func colored(_ text: String) -> NSAttributedString {
            let para = NSMutableParagraphStyle(); para.minimumLineHeight = 22; para.maximumLineHeight = 22
            let out = NSMutableAttributedString(string: text, attributes: [.font: BenchFont.monoNS(13), .foregroundColor: NSColor(parent.base), .paragraphStyle: para])
            // Very large files stay plain: the tokenizer is regex-based.
            guard text.utf8.count < 400_000 else { return out }
            let colored = BenchHighlighter(dark: parent.dark, base: parent.base).attributed(text, language: parent.language)
            for run in colored.runs {
                guard let c = run.foregroundColor else { continue }
                out.addAttribute(.foregroundColor, value: NSColor(c), range: NSRange(run.range, in: colored))
            }
            return out
        }

        func textDidChange(_ n: Notification) {
            guard let tv = n.object as? NSTextView else { return }
            parent.text = tv.string
            tv.enclosingScrollView?.verticalRulerView?.needsDisplay = true
            // Re-colour after a pause in typing, keeping the caret where it is.
            pending?.cancel()
            let work = DispatchWorkItem { [weak self, weak tv] in
                guard let self, let tv, let storage = tv.textStorage else { return }
                let sel = tv.selectedRanges
                let colored = self.colored(tv.string)
                storage.beginEditing()
                colored.enumerateAttribute(.foregroundColor, in: NSRange(location: 0, length: colored.length)) { v, r, _ in
                    if let v { storage.addAttribute(.foregroundColor, value: v, range: r) }
                }
                storage.endEditing()
                tv.selectedRanges = sel
            }
            pending = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: work)
        }

        func textViewDidChangeSelection(_ n: Notification) {
            guard let tv = n.object as? NSTextView else { return }
            let s = tv.string as NSString
            let at = min(tv.selectedRange().location, s.length)
            let before = s.substring(to: at)
            let line = before.reduce(1) { $1 == "\n" ? $0 + 1 : $0 }
            let col = at - (before.range(of: "\n", options: .backwards).map { before.distance(from: before.startIndex, to: $0.upperBound) } ?? 0) + 1
            if parent.caret.line != line || parent.caret.col != col { DispatchQueue.main.async { self.parent.caret = (line, col) } }
        }
    }
}

/// Line numbers beside a text view.
final class LineNumbers: NSRulerView {
    var color: NSColor = .secondaryLabelColor { didSet { needsDisplay = true } }

    init(textView: NSTextView) {
        super.init(scrollView: textView.enclosingScrollView, orientation: .verticalRuler)
        clientView = textView
        ruleThickness = 52
        NotificationCenter.default.addObserver(self, selector: #selector(redraw), name: NSView.boundsDidChangeNotification, object: textView.enclosingScrollView?.contentView)
    }
    required init(coder: NSCoder) { fatalError() }

    @objc private func redraw() { needsDisplay = true }

    /// Numbers only: no background or edge line of the ruler's own.
    override func draw(_ dirtyRect: NSRect) {
        drawHashMarksAndLabels(in: dirtyRect.intersection(bounds))
    }

    override func drawHashMarksAndLabels(in rect: NSRect) {
        guard let tv = clientView as? NSTextView, let lm = tv.layoutManager, let tc = tv.textContainer else { return }
        let s = tv.string as NSString
        let attrs: [NSAttributedString.Key: Any] = [.font: BenchFont.monoNS(11.5), .foregroundColor: color]
        let visible = tv.visibleRect
        let glyphs = lm.glyphRange(forBoundingRect: visible, in: tc)
        let chars = lm.characterRange(forGlyphRange: glyphs, actualGlyphRange: nil)
        var line = s.substring(to: chars.location).reduce(1) { $1 == "\n" ? $0 + 1 : $0 }
        var i = chars.location
        let offset = convert(NSPoint.zero, from: tv).y + tv.textContainerInset.height
        while i <= NSMaxRange(chars) {
            let lr = s.lineRange(for: NSRange(location: min(i, max(s.length - 1, 0)), length: 0))
            let g = lm.glyphIndexForCharacter(at: min(lr.location, max(s.length - 1, 0)))
            let frag = s.length == 0 ? NSRect(x: 0, y: 0, width: 0, height: 22) : lm.lineFragmentRect(forGlyphAt: g, effectiveRange: nil)
            let label = "\(line)" as NSString
            let size = label.size(withAttributes: attrs)
            label.draw(at: NSPoint(x: ruleThickness - size.width - 14, y: offset + frag.minY + (frag.height - size.height) / 2), withAttributes: attrs)
            line += 1
            if NSMaxRange(lr) <= i || NSMaxRange(lr) >= s.length { break }
            i = NSMaxRange(lr)
        }
    }
}
