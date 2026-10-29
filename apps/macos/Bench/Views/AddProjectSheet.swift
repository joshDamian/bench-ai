import SwiftUI

/// Add Project…, as its board draws it: clone from GitHub, a folder already on the server,
/// or a new repository. Everything happens on the server.
struct AddProjectSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.theme) private var theme
    let store: BenchStore
    let onAdded: (String) -> Void

    enum Source: String, CaseIterable, Identifiable { case clone = "Clone from GitHub", folder = "Folder on Server", new = "New Repository"; var id: String { rawValue } }
    struct Repo: Decodable, Hashable { let name: String; let description: String?; let `private`: Bool?; let updatedAt: String? }

    @State private var source: Source = .clone
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
            case .folder: field("Path on the server", text: $folder, prompt: "~/code/my-app", mono: true)
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
                Text(hint).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
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
        .frame(width: 560)
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
