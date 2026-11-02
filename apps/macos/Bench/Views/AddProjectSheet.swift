import SwiftUI

/// Add Project…, as its board draws it: clone from GitHub, a folder already on the server,
/// or a new repository. Everything happens on the server.
struct AddProjectSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.theme) private var theme
    let store: BenchStore
    let onAdded: (String) -> Void

    enum Source: String, CaseIterable, Identifiable { case folder = "Folder on the Server", clone = "Clone a Repository", new = "New Repository"; var id: String { rawValue } }
    struct Repo: Decodable, Hashable { let name: String; let description: String?; let `private`: Bool?; let updatedAt: String? }

    @State private var source: Source = .folder
    @State private var query = ""
    @State private var repos: [Repo] = []
    @State private var reposReady = true
    @State private var picked: Repo?
    @State private var name = ""
    @State private var folder = ""
    @State private var projectsDir = "~/bench-projects"
    @State private var busy = false
    @State private var error: String?

    private var matches: [Repo] {
        let q = query.lowercased()
        return (q.isEmpty ? repos : repos.filter { $0.name.lowercased().contains(q) }).prefix(5).map { $0 }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Add Project").font(.system(size: 15, weight: .semibold))
            Picker("", selection: $source) { ForEach(Source.allCases) { Text($0.rawValue).tag($0) } }
                .pickerStyle(.segmented).labelsHidden()
            switch source {
            case .clone: cloneForm
            case .folder: FolderColumns(store: store, picked: $folder)
            case .new: field("Name", text: $name, prompt: "my-new-app", mono: false)
            }
            if source != .folder {
                Grid(alignment: .leading, horizontalSpacing: Space.md, verticalSpacing: Space.sm) {
                    if source == .clone {
                        GridRow {
                            Text("Name").foregroundStyle(theme.colors.muted)
                            TextField("", text: $name).textFieldStyle(.roundedBorder)
                        }
                    }
                    GridRow {
                        Text(source == .clone ? "Clone into" : "Created in").foregroundStyle(theme.colors.muted)
                        Text(projectsDir).font(.system(size: 12, design: .monospaced))
                    }
                }
                .font(.system(size: 13))
            }
            if let error { Text(error).font(.system(size: 12)).foregroundStyle(theme.colors.bad).textSelection(.enabled) }
            HStack(spacing: Space.sm) {
                Text(source == .folder && !folder.isEmpty ? folder.replacingOccurrences(of: #"^/(home|Users)/[^/]+"#, with: "~", options: .regularExpression) : hint)
                    .font(.system(size: 12, design: source == .folder && !folder.isEmpty ? .monospaced : .default))
                    .foregroundStyle(theme.colors.muted)
                    .lineLimit(1).truncationMode(.head)
                Spacer()
                if busy { ProgressView().controlSize(.small) }
                Button("Cancel") { dismiss() }.buttonStyle(CapsuleButton(kind: .outline)).keyboardShortcut(.cancelAction)
                Button(source == .clone ? "Clone" : source == .folder ? "Add" : "Create") { add() }
                    .buttonStyle(CapsuleButton(kind: .primary))
                    .keyboardShortcut(.defaultAction)
                    .disabled(busy || !ready)
            }
        }
        .padding(20)
        .frame(width: 760)
        .background(theme.colors.surface2)
        .task { await load() }
    }

    private var cloneForm: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            HStack(spacing: Space.sm) {
                Image(systemName: "magnifyingglass").foregroundStyle(theme.colors.muted)
                TextField("", text: $query, prompt: Text(reposReady ? "Search your repositories, or paste a URL" : "owner/repo or a git URL"))
                    .textFieldStyle(.plain)
            }
            .font(.system(size: 13))
            .padding(.horizontal, 10).frame(height: 32)
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.colors.lineStrong))
            if !matches.isEmpty {
                VStack(spacing: 2) {
                    ForEach(matches, id: \.self) { r in
                        Button { picked = r; name = String(r.name.split(separator: "/").last ?? "") } label: {
                            HStack {
                                Text(r.name).font(.system(size: 13))
                                Spacer()
                                Text([r.private == true ? "Private" : "Public", r.updatedAt.map { "pushed \(Format.ago($0)) ago" }].compactMap { $0 }.joined(separator: " · "))
                                    .font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                            }
                            .padding(.horizontal, 10).frame(height: 36)
                            .background(picked == r ? theme.colors.accent.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 7))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func field(_ label: String, text: Binding<String>, prompt: String, mono: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            TextField("", text: text, prompt: Text(prompt)).textFieldStyle(.roundedBorder)
                .font(.system(size: 13, design: mono ? .monospaced : .default))
        }
    }

    private var hint: String {
        switch source {
        case .clone: "Clones on the server with its GitHub login"
        case .folder: "Uses a git repository already on the server"
        case .new: "Starts an empty git repository"
        }
    }

    private var ready: Bool {
        switch source {
        case .clone: picked != nil || query.contains("/")
        case .folder: !folder.trimmingCharacters(in: .whitespaces).isEmpty
        case .new: !name.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private func load() async {
        if let d = try? await store.api.call("/api/defaults"), let v = try? JSONDecoder().decode(JSONValue.self, from: d), let p = v["projectsDir"].string {
            projectsDir = p.replacingOccurrences(of: #"^/(home|Users)/[^/]+"#, with: "~", options: .regularExpression)
        }
        if let d = try? await store.api.call("/api/github/repos"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) {
            reposReady = v["ready"].bool
            repos = (try? JSONDecoder().decode([Repo].self, from: JSONEncoder().encode(v["repos"] ?? .array([])))) ?? []
        }
    }

    private func add() {
        busy = true
        error = nil
        Task {
            do {
                let d: Data
                switch source {
                case .clone:
                    d = try await store.api.call("/api/projects/clone", method: "POST", body: ["repo": picked?.name ?? query.trimmingCharacters(in: .whitespaces), "name": name])
                case .folder:
                    d = try await store.api.call("/api/projects", method: "POST", body: ["path": folder])
                case .new:
                    d = try await store.api.call("/api/projects/create", method: "POST", body: ["name": name])
                }
                await store.refresh()
                if let p = try? JSONDecoder().decode(Project.self, from: d) { onAdded(p.id) }
                dismiss()
            } catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}

/// The server's folders in Finder-style columns (GET /api/fs). Choosing a folder opens
/// the next column; the last one chosen is the project.
struct FolderColumns: View {
    struct Entry: Hashable { let name: String; let repo: Bool }
    struct Dir: Identifiable { let id = UUID(); let path: String; let entries: [Entry] }

    @Environment(\.theme) private var theme
    let store: BenchStore
    @Binding var picked: String
    @State private var columns: [Dir] = []
    @State private var chosen: [String] = []

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 0) {
                    ForEach(Array(columns.enumerated()), id: \.element.id) { i, d in
                        ScrollView {
                            VStack(alignment: .leading, spacing: 1) {
                                if d.entries.isEmpty {
                                    Text("No folders").font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(10)
                                }
                                ForEach(d.entries, id: \.name) { e in
                                    let on = chosen.indices.contains(i) && chosen[i] == e.name
                                    Button { open(column: i, name: e.name) } label: {
                                        HStack(spacing: 6) {
                                            Image(systemName: "folder.fill").font(.system(size: 11)).foregroundStyle(on ? theme.colors.onAccent : theme.colors.accent)
                                            Text(e.name).font(.system(size: 13)).lineLimit(1)
                                            Spacer(minLength: 4)
                                            if e.repo { Text("git").font(.system(size: 10.5)).opacity(0.7) }
                                            Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold)).opacity(0.5)
                                        }
                                        .foregroundStyle(on ? theme.colors.onAccent : theme.colors.text)
                                        .padding(.horizontal, 8).frame(height: 26)
                                        .background(on ? theme.colors.accent : .clear, in: RoundedRectangle(cornerRadius: 6))
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(6)
                        }
                        .frame(width: 236)
                        .id(d.id)
                        Rectangle().fill(theme.colors.fill(0.07)).frame(width: 1)
                    }
                }
            }
            .onChange(of: columns.count) { _, _ in if let last = columns.last { withAnimation { proxy.scrollTo(last.id, anchor: .trailing) } } }
        }
        .frame(height: 300)
        .background(theme.colors.fill(0.04), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(theme.colors.fill(0.07)))
        .task { if columns.isEmpty, let d = await read(nil) { columns = [d] } }
    }

    private func open(column i: Int, name: String) {
        let path = (columns[i].path as NSString).appendingPathComponent(name)
        chosen = Array(chosen.prefix(i)) + [name]
        columns = Array(columns.prefix(i + 1))
        picked = path
        Task { if let d = await read(path), chosen.count == i + 1, chosen[i] == name { columns.append(d) } }
    }

    private func read(_ path: String?) async -> Dir? {
        let q = path.map { "?path=\(BenchAPI.enc($0))" } ?? ""
        guard let data = try? await store.api.call("/api/fs" + q), let v = try? JSONDecoder().decode(JSONValue.self, from: data) else { return nil }
        let entries = v["entries"].array.compactMap { (e: JSONValue) -> Entry? in
            guard let n = e["name"].string, !n.hasPrefix(".") else { return nil }
            return Entry(name: n, repo: e["repo"].bool)
        }
        return Dir(path: v["path"].string ?? path ?? "/", entries: entries)
    }
}
