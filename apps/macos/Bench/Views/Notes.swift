import AppKit
import SwiftUI

// MARK: Which note

/// What a notes tab shows: one of the person's notes (a project's, or of no project), or
/// a project's CLAUDE.md, which sits with its notes because it is read and edited the same way.
enum NoteRef: Hashable {
    case note(project: String?, name: String)
    case instructions(project: String)

    /// `note:<project>:<name>` (the project empty for a note of none), or `doc:<project>:CLAUDE.md`.
    init?(tab id: String) {
        if id.hasPrefix("note:") {
            let rest = id.dropFirst(5)
            guard let colon = rest.firstIndex(of: ":") else { return nil }
            let project = String(rest[..<colon]), name = String(rest[rest.index(after: colon)...])
            guard !name.isEmpty else { return nil }
            self = .note(project: project.isEmpty ? nil : project, name: name)
        } else if id.hasPrefix("doc:") {
            let rest = id.dropFirst(4)
            guard let colon = rest.firstIndex(of: ":") else { return nil }
            self = .instructions(project: String(rest[..<colon]))
        } else {
            return nil
        }
    }

    var tab: String {
        switch self {
        case .note(let project, let name): "note:\(project ?? ""):\(name)"
        case .instructions(let project): "doc:\(project):CLAUDE.md"
        }
    }

    var project: String? {
        switch self {
        case .note(let project, _): project
        case .instructions(let project): project
        }
    }

    var fileName: String {
        switch self {
        case .note(_, let name): name
        case .instructions: "CLAUDE.md"
        }
    }
}

// MARK: The lists

/// The notes the sidebar lists, per project and for no project, fetched when a list shows
/// and again when a session stops (Claude may have written one).
@MainActor
@Observable
final class NotesStore {
    /// By project id; "" is the notes of no project.
    private(set) var lists: [String: NoteList] = [:]

    func list(_ project: String?) -> NoteList { lists[project ?? ""] ?? NoteList() }

    func title(_ ref: NoteRef) -> String? {
        guard case .note(let project, let name) = ref else { return nil }
        return list(project).notes.first { $0.name == name }?.title
    }

    func load(_ api: BenchAPI, project: String?) async {
        if let l = try? await api.notes(project: project) { lists[project ?? ""] = l }
    }
}

extension AppModel {
    /// A new note, opened ready to write in.
    func newNote(project: String?, title: String) {
        guard let store else { return }
        Task {
            guard let n = try? await store.api.createNote(project: project, title: title) else { return }
            await notes.load(store.api, project: project)
            let ref = NoteRef.note(project: project, name: n.name)
            editingNote = ref.tab
            go(ref.tab)
        }
    }
}

// MARK: The note

/// A note open in a tab: read as a document, or edited as Markdown beside its preview.
/// It saves itself a moment after you stop typing; "Start a task from this" hands its text
/// to a new task as the prompt.
struct NoteView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    let store: BenchStore
    let ref: NoteRef

    @State private var text = ""
    @State private var saved = ""
    @State private var preview = ""
    @State private var mtime: Double = 0
    @State private var path = ""
    @State private var include = false
    @State private var editing = false
    @State private var loaded = false
    @State private var missing: String?
    @State private var saving = false
    @State private var conflict = false
    @State private var problem: String?
    @State private var caret = (line: 1, col: 1)
    @State private var pendingSave: Task<Void, Never>?
    @State private var promoting = false
    @State private var projectName = ""
    @State private var confirmDelete = false

    private var dirty: Bool { text != saved }
    private var isNote: Bool { if case .note = ref { true } else { false } }
    private var title: String { NoteView.title(of: text) ?? ref.fileName.replacingOccurrences(of: ".md", with: "") }
    private var project: Project? { store.project(ref.project) }

    static func title(of text: String) -> String? {
        for line in text.split(separator: "\n", maxSplits: 30, omittingEmptySubsequences: false).prefix(30) {
            if line.hasPrefix("# ") { return line.dropFirst(2).trimmingCharacters(in: .whitespaces) }
            if !line.trimmingCharacters(in: .whitespaces).isEmpty && !line.hasPrefix("---") { break }
        }
        return nil
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1)
            if let missing {
                Text(missing).font(.system(size: 13)).foregroundStyle(theme.colors.muted).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if !loaded {
                ProgressView().controlSize(.small).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if editing {
                editor
            } else {
                MarkdownDoc(text: text)
            }
        }
        .background(theme.colors.bg)
        .navigationTitle(title)
        .task(id: ref) { await load() }
        // Claude may have edited it: when a session stops, show what the file holds now.
        .onChange(of: store.runningSince.count) { _, _ in if !dirty { Task { await load(quietly: true) } } }
        .onChange(of: text) { _, t in
            schedulePreview(t)
            if loaded && dirty { scheduleSave() }
        }
        .onDisappear { pendingSave?.cancel(); if dirty { Task { await save() } } }
        .background {
            Button("") { editing.toggle() }.keyboardShortcut("e", modifiers: .command).hidden()
            Button("") { Task { await save() } }.keyboardShortcut("s", modifiers: .command).hidden()
        }
        .alert("This note changed since you opened it", isPresented: $conflict) {
            Button("Keep Mine") { mtime = 0; Task { await save() } }
            Button("Load the Other Version", role: .destructive) { Task { await load() } }
        } message: { Text("Something else saved it while you were editing, most likely Claude in a session.") }
        .alert("Make it a project", isPresented: $promoting) {
            TextField("Project name", text: $projectName)
            Button("Create Project") { promote() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Creates a new project on the server and moves this note into its notes.") }
        .confirmationDialog("Delete “\(title)”?", isPresented: $confirmDelete) {
            Button("Delete Note", role: .destructive) { delete() }
        } message: { Text("The file is removed from the server. This can't be undone.") }
        .alert("Something went wrong", isPresented: Binding(get: { problem != nil }, set: { if !$0 { problem = nil } })) {
            Button("OK") { problem = nil }
        } message: { Text(problem ?? "") }
    }

    // MARK: header

    private var header: some View {
        PaneHeader {
            HeaderTitle(title: title, sub: where_, mono: true)
        } trailing: {
            if isNote {
                Toggle(isOn: Binding(get: { include }, set: { setInclude($0) })) {
                    Text("In every session").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                }
                .toggleStyle(.switch).controlSize(.mini)
                .help(ref.project == nil ? "Add this note to the instructions of every new session, in every project"
                                         : "Add this note to the instructions of every new session in this project")
                .padding(.trailing, 4)
            }
            Segments(options: [(false, "Read"), (true, "Edit")], selection: $editing, compact: true).fixedSize()
            startButton
            Menu {
                if case .note(nil, _) = ref { Button("Make It a Project…") { projectName = title; promoting = true } }
                Button("Copy Path") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(path.isEmpty ? ref.fileName : path, forType: .string)
                }
                if isNote {
                    Divider()
                    Button("Delete Note…", role: .destructive) { confirmDelete = true }
                }
            } label: { Image(systemName: "ellipsis").font(.system(size: 13)).foregroundStyle(theme.colors.muted).frame(width: 30, height: 30) }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            .help("More")
        }
    }

    /// Where the file is, as the person would say it.
    private var where_: String {
        switch ref {
        case .note(let p, let name): p == nil ? "notes/\(name) · on the server" : "notes/\(name)"
        case .instructions: "CLAUDE.md · every session in this project reads it"
        }
    }

    /// The page's one filled button. A note of no project asks which project the task is for.
    @ViewBuilder private var startButton: some View {
        let empty = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if let pid = ref.project {
            if isNote {
                Button("Start a Task from This") { startTask(in: pid) }
                    .buttonStyle(PrimaryButton(height: 28)).fixedSize().disabled(empty)
            }
        } else {
            Menu {
                ForEach(store.projects) { p in Button(p.name) { startTask(in: p.id) } }
            } label: {
                Text("Start a Task from This").font(.system(size: TypeScale.sm, weight: .semibold)).foregroundStyle(theme.colors.onAccent)
                    .padding(.horizontal, 14).frame(height: 28)
                    .background(theme.colors.accent, in: Capsule())
            }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            .disabled(empty || store.projects.isEmpty)
            .help("Choose the project the task is for")
        }
    }

    // MARK: editing

    private var editor: some View {
        VStack(spacing: 0) {
            HSplitView {
                CodeTextView(text: $text, editable: true, language: "md", dark: theme.dark, base: theme.colors.text,
                             gutter: theme.colors.muted, caret: $caret)
                    .frame(minWidth: 320, maxWidth: .infinity)
                MarkdownDoc(text: preview)
                    .frame(minWidth: 280, maxWidth: .infinity)
            }
            Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1)
            HStack(spacing: 18) {
                Text("Markdown")
                Text("Ln \(caret.line), Col \(caret.col)")
                Spacer(minLength: 0)
                Text(saving ? "Saving…" : dirty ? "Edited" : "Saved")
            }
            .font(BenchFont.mono(11.5)).foregroundStyle(theme.colors.muted)
            .padding(.horizontal, 24).frame(height: 28)
        }
    }

    /// The preview follows a beat behind the typing, so a long note isn't laid out per keystroke.
    private func schedulePreview(_ t: String) {
        Task {
            try? await Task.sleep(for: .milliseconds(180))
            if text == t { preview = t }
        }
    }

    private func scheduleSave() {
        pendingSave?.cancel()
        pendingSave = Task {
            try? await Task.sleep(for: .milliseconds(1200))
            if !Task.isCancelled { await save() }
        }
    }

    // MARK: loading and saving

    private func load(quietly: Bool = false) async {
        do {
            switch ref {
            case .note(let p, let name):
                let n = try await store.api.noteText(project: p, name: name)
                (text, saved, mtime, include, path) = (n.text, n.text, n.mtime, n.include, n.path)
            case .instructions(let p):
                let f = try await store.api.text(p, path: "CLAUDE.md")
                (text, saved, mtime) = (f.text, f.text, f.mtime)
                path = ((project?.path ?? "") as NSString).appendingPathComponent("CLAUDE.md")
            }
            preview = text
            missing = nil
            if !loaded {
                loaded = true
                // A note just made opens ready to write in.
                if app.editingNote == ref.tab { editing = true; app.editingNote = nil }
            }
        } catch {
            if !quietly || !loaded { missing = (error as? BenchError)?.status == 404 ? "This note is no longer there." : error.localizedDescription }
        }
    }

    private func save() async {
        guard loaded, dirty, !saving, missing == nil else { return }
        saving = true
        defer { saving = false }
        let sending = text
        do {
            switch ref {
            case .note(let p, let name): mtime = try await store.api.saveNote(project: p, name: name, text: sending, mtime: mtime)
            case .instructions(let p): mtime = try await store.api.save(p, path: "CLAUDE.md", text: sending, mtime: mtime)
            }
            saved = sending
            // Its title may have changed: the sidebar's list shows it.
            if isNote { await app.notes.load(store.api, project: ref.project) }
            if text != sending { scheduleSave() }
        } catch let e as BenchError where e.status == 409 {
            conflict = true
        } catch {
            problem = error.shown
        }
    }

    private func setInclude(_ on: Bool) {
        guard case .note(let p, let name) = ref else { return }
        include = on
        Task {
            do {
                try await store.api.includeNote(project: p, name: name, on)
                await app.notes.load(store.api, project: p)
            } catch { include = !on; problem = error.shown }
        }
    }

    /// The note as a task's prompt: its title first, since that becomes the task's name.
    private func startTask(in pid: String) {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let whereFrom = path.isEmpty ? ref.fileName : path
        app.taskDraft = "\(title)\n\nFrom my note at \(whereFrom):\n\n\(body)"
        app.startTask(in: pid)
    }

    private func promote() {
        guard case .note(nil, let name) = ref else { return }
        let wanted = projectName.trimmingCharacters(in: .whitespaces)
        guard !wanted.isEmpty else { return }
        Task {
            if dirty { await save() }
            do {
                let made = try await store.api.promoteNote(name: name, projectName: wanted)
                await store.refresh()
                await app.notes.load(store.api, project: nil)
                await app.notes.load(store.api, project: made.project)
                let old = ref.tab
                app.selection = NoteRef.note(project: made.project, name: made.name).tab
                app.tabs.removeAll { $0 == old }
            } catch { problem = error.shown }
        }
    }

    private func delete() {
        guard case .note(let p, let name) = ref else { return }
        Task {
            do {
                saved = text
                try await store.api.deleteNote(project: p, name: name)
                await app.notes.load(store.api, project: p)
                missing = "This note was deleted."
                if app.tabs.count > 1 { app.closeTab(ref.tab) }
            } catch { problem = error.shown }
        }
    }
}

// MARK: Sidebar rows

/// One note in a sidebar list.
struct NoteRow: View {
    @Environment(\.theme) private var theme
    let icon: String
    let title: String
    let sub: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: icon).font(.system(size: 10.5)).foregroundStyle(theme.colors.muted).frame(width: 12)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13)).lineLimit(1)
                Text(sub).font(.system(size: 11)).foregroundStyle(theme.colors.muted).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        // The row's own padding comes from the sidebar's selection highlight.
        .padding(.horizontal, 2).padding(.vertical, 3)
        .contentShape(Rectangle())
    }

    /// "2h", or that it's in every session.
    static func sub(_ n: Note) -> String {
        let age = Format.ago(Date(timeIntervalSince1970: n.mtime / 1000))
        return n.include ? "In every session · \(age)" : age
    }
}
