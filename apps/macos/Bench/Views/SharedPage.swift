import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Settings › Shared with Bench (the "Shared with Bench" board): everything Bench on the server
/// may use on this Mac, in three groups, each with its rule. Nothing else here is reachable.
/// New shares are added only here; what Bench asks for waits at the top until you decide.
struct SharedPage: View {
    let link: MacLink
    let server: String
    @Environment(\.theme) private var theme

    private let groups: [(String, [String])] = [("Folders", ["folder"]), ("Apps and screen", ["app", "screen"]), ("Things it can run", ["run"])]

    var body: some View {
        HStack(alignment: .top) {
            Text("Bench on \(host) can use only what is listed here.").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
            Spacer()
            Menu {
                Button("A folder…") { addFolder() }
                Button("An app…") { addApp() }
                Button("The screen") { link.add(Share(id: "screen", kind: "screen", name: "Screen", path: nil, access: "look", rule: "ask")) }
                Divider()
                Button("Android emulators (adb)") { addCommand("adb", name: "Android emulators") }
                Button("Another command…") { addCommandPicked() }
            } label: { Label("Share…", systemImage: "plus") }
                .menuStyle(.borderedButton).fixedSize()
        }

        if !link.asks.isEmpty {
            GroupLabel("Bench asks")
            Group2 {
                ForEach(Array(link.asks.enumerated()), id: \.element.id) { i, a in
                    GroupRow(title: a.title, sub: a.why.isEmpty ? nil : a.why, first: i == 0) {
                        Button("Decline") { link.answer(a, approved: false) }
                        Button("Allow") { link.answer(a, approved: true) }.buttonStyle(.borderedProminent)
                    }
                }
            }
        }

        if link.shares.isEmpty {
            Text("Nothing is shared yet. Share a folder, an app, the screen or a command, and sessions can use it while this Mac is awake.")
                .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
        }
        ForEach(groups, id: \.0) { title, kinds in
            let list = link.shares.filter { kinds.contains($0.kind) }
            if !list.isEmpty {
                GroupLabel(title)
                Group2 {
                    ForEach(Array(list.enumerated()), id: \.element.id) { i, s in
                        HStack(spacing: 12) {
                            Image(systemName: s.icon).font(.system(size: 13)).frame(width: 30, height: 30)
                                .background(theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 7))
                            VStack(alignment: .leading, spacing: 1) {
                                Text(s.kind == "folder" ? (s.shownPath ?? s.name) : s.name).font(.system(size: 13))
                                Text(s.accessLabel).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                            }
                            Spacer()
                            Picker("", selection: Binding(get: { s.rule }, set: { link.setRule(s, $0) })) {
                                Text("Asks first").tag("ask")
                                Text("Allowed").tag("allow")
                            }
                            .labelsHidden().fixedSize()
                            Button { link.remove(s) } label: { Image(systemName: "minus") }
                                .buttonStyle(.borderless).help("Stop sharing")
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .overlay(alignment: .top) { if i > 0 { Divider().padding(.leading, 54) } }
                    }
                }
            }
        }

        HStack(spacing: 6) {
            Circle().fill(link.connected ? Color.green : theme.colors.muted).frame(width: 7, height: 7)
            Text(link.connected ? "Connected to \(host) · new shares need your approval here" : "Not connected to \(host) · shares work again when it is")
                .font(.system(size: 12)).foregroundStyle(theme.colors.muted)
        }
    }

    private var host: String { URL(string: server)?.host ?? server }

    private func addFolder() {
        let p = NSOpenPanel()
        p.canChooseDirectories = true
        p.canChooseFiles = false
        p.prompt = "Share"
        p.message = "Choose a folder to share with Bench"
        guard p.runModal() == .OK, let url = p.url else { return }
        let a = NSAlert()
        a.messageText = "Share \(url.lastPathComponent)"
        a.informativeText = "Can Bench change files in it, or only read them?"
        a.addButton(withTitle: "Read only")
        a.addButton(withTitle: "Read and write")
        a.addButton(withTitle: "Cancel")
        let r = a.runModal()
        guard r != .alertThirdButtonReturn else { return }
        link.add(Share(id: link.uniqueID(url.lastPathComponent), kind: "folder", name: url.lastPathComponent, path: url.path, access: r == .alertSecondButtonReturn ? "write" : "read", rule: "ask"))
    }

    private func addApp() {
        let p = NSOpenPanel()
        p.allowedContentTypes = [.application]
        p.directoryURL = URL(fileURLWithPath: "/Applications")
        p.prompt = "Share"
        guard p.runModal() == .OK, let url = p.url else { return }
        let name = url.deletingPathExtension().lastPathComponent
        link.add(Share(id: link.uniqueID(name), kind: "app", name: name, path: url.path, access: "open", rule: "ask"))
    }

    private func addCommand(_ cmd: String, name: String) {
        guard let path = ShareRunner.which(cmd) else {
            let a = NSAlert()
            a.messageText = "\(cmd) is not installed"
            a.informativeText = cmd == "adb" ? "Install Android Studio's platform tools, then share it again." : "Install it, then share it again."
            a.runModal()
            return
        }
        link.add(Share(id: link.uniqueID(cmd), kind: "run", name: name, path: path, access: "run", rule: "ask"))
    }

    private func addCommandPicked() {
        let p = NSOpenPanel()
        p.canChooseFiles = true
        p.prompt = "Share"
        p.message = "Choose a program Bench may run (it is run directly, not through a shell)"
        guard p.runModal() == .OK, let url = p.url, FileManager.default.isExecutableFile(atPath: url.path) else { return }
        link.add(Share(id: link.uniqueID(url.lastPathComponent), kind: "run", name: url.lastPathComponent, path: url.path, access: "run", rule: "ask"))
    }
}
