import CoreImage.CIFilterBuiltins
import SwiftUI

/// ⌘, as the Mac v2 Settings boards draw it: a sidebar of sections in the System
/// Settings style, each a page of grouped rows.
struct SettingsView: View {
    enum Section: String, CaseIterable, Identifiable {
        case server, models, subagents, limit, devices, notifications, appearance, shortcuts
        var id: String { rawValue }
        var title: String {
            switch self {
            case .server: "Server"
            case .models: "Models"
            case .subagents: "Subagents"
            case .limit: "Usage limit"
            case .devices: "Devices"
            case .notifications: "Notifications"
            case .appearance: "Appearance"
            case .shortcuts: "Shortcuts"
            }
        }
        var icon: String {
            switch self {
            case .server: "server.rack"
            case .models: "cpu"
            case .subagents: "point.3.connected.trianglepath.dotted"
            case .limit: "gauge.with.dots.needle.67percent"
            case .devices: "laptopcomputer.and.iphone"
            case .notifications: "bell.badge"
            case .appearance: "circle.lefthalf.filled"
            case .shortcuts: "command"
            }
        }
        var tint: Color {
            switch self {
            case .models: .blue
            case .subagents: .purple
            case .limit: .orange
            case .notifications: .red
            default: .gray
            }
        }
    }

    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var section: Section = .server

    var body: some View {
        HStack(spacing: 0) {
            // The board's sidebar: a tonal pane inset from the window, with a quiet selection.
            VStack(alignment: .leading, spacing: 1) {
                HStack { TrafficLights(); Spacer() }
                    .padding(.leading, 8)
                    .frame(height: 40)
                    .background(WindowDragArea())
                ForEach(Section.allCases) { s in
                    Button { section = s } label: {
                        HStack(spacing: 10) {
                            Image(systemName: s.icon).font(.system(size: 11, weight: .medium)).foregroundStyle(.white)
                                .frame(width: 22, height: 22).background(s.tint.gradient, in: RoundedRectangle(cornerRadius: 6))
                            Text(s.title).font(.system(size: 13, weight: s == section ? .medium : .regular))
                            Spacer(minLength: 0)
                        }
                        .foregroundStyle(theme.colors.text)
                        .padding(.horizontal, 6)
                        .frame(height: 32)
                        .background(s == section ? theme.colors.fill(0.11) : .clear, in: RoundedRectangle(cornerRadius: 7))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
            }
            .padding(.horizontal, 8)
            .padding(.top, 4)
            .frame(width: 220)
            .frame(maxHeight: .infinity)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding(8)

            VStack(alignment: .leading, spacing: 0) {
                Text(section.title).font(.system(size: 15, weight: .semibold))
                    .padding(.horizontal, 28)
                    .frame(height: 52, alignment: .center)
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        if let store = model.store {
                            switch section {
                            case .server: ServerPage(store: store)
                            case .models: ModelsPage(store: store)
                            case .subagents: SubagentsPage(store: store)
                            case .limit: LimitPage(store: store)
                            case .devices: DevicesPage(store: store)
                            case .notifications: NotificationsPage()
                            case .appearance: AppearancePage()
                            case .shortcuts: ShortcutsPage()
                            }
                        } else if section == .appearance {
                            AppearancePage()
                        } else {
                            Text("Not connected to a server").foregroundStyle(theme.colors.muted)
                        }
                    }
                    .padding(.horizontal, 28)
                    .padding(.top, 4)
                    .padding(.bottom, 24)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .frame(width: 920, height: 640)
        .background(theme.colors.bg)
        .ignoresSafeArea()
        .hideToolbarTitle()
        .toolbarBackground(.hidden, for: .windowToolbar)
    }
}

// MARK: Server

private struct ServerPage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var uptime: String?
    @State private var newer: String?
    @State private var canUpdate = false
    @State private var note: String?
    @State private var projectsDir = ""
    @State private var editingDir = false
    @State private var confirmUnpair = false
    @State private var confirmRestart = false

    var body: some View {
        Group2 {
            GroupRow(title: "Server", first: true) {
                Text(store.api.config.baseURL.replacingOccurrences(of: #"^https?://"#, with: "", options: .regularExpression))
                    .font(.system(size: 12, design: .monospaced)).foregroundStyle(theme.colors.muted).textSelection(.enabled).lineLimit(1)
            }
            GroupRow(title: "Status") {
                HStack(spacing: 6) {
                    Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 7, height: 7)
                    Text(store.connected ? (["Connected", uptime.map { "up \($0)" }].compactMap { $0 }.joined(separator: " · ")) : "Connecting…").foregroundStyle(theme.colors.muted)
                }
            }
            GroupRow(title: store.version.map { "Bench \($0)" } ?? "Bench", sub: note) {
                if canUpdate {
                    Button("Update") { runUpdate() }.buttonStyle(PrimaryButton(height: 26))
                } else {
                    Button("Check for Updates") { checkUpdate() }.buttonStyle(TonalButton(height: 24))
                }
            }
            GroupRow(title: "Restart the server") {
                Button("Restart…") { confirmRestart = true }.buttonStyle(TonalButton(height: 24))
            }
        }
        Group2 {
            GroupRow(title: "This Mac", first: true) { Text(store.me?.device?.name ?? (store.api.config.isLoopback ? "On the server itself" : "Paired")).foregroundStyle(theme.colors.muted) }
            GroupRow(title: "Projects folder") {
                if editingDir {
                    TextField("", text: $projectsDir).textFieldStyle(.roundedBorder).font(.system(size: 12, design: .monospaced)).frame(width: 240)
                        .onSubmit { saveDir() }
                    Button("Save") { saveDir() }.buttonStyle(TonalButton(height: 24))
                } else {
                    Text(projectsDir).font(.system(size: 12, design: .monospaced)).foregroundStyle(theme.colors.muted)
                    Button("Change…") { editingDir = true }.buttonStyle(TonalButton(height: 24))
                }
            }
            GroupRow(title: "Disconnect this Mac") {
                Button("Disconnect…") { confirmUnpair = true }.buttonStyle(TonalButton(danger: true, height: 24))
            }
        }
        .task { await load() }
        .confirmationDialog("Disconnect this Mac?", isPresented: $confirmUnpair) {
            Button("Disconnect", role: .destructive) { model.unpair() }
        } message: { Text("You'll need to pair again to reconnect.") }
        .confirmationDialog("Restart the server?", isPresented: $confirmRestart) {
            Button("Restart", role: .destructive) { Task { _ = try? await store.api.call("/api/server/restart", method: "POST", body: [:]) } }
        } message: { Text("Running sessions stop and pick up again when Bench is back.") }
    }

    private func load() async {
        if let d = try? await store.api.call("/api/server/status"), let v = try? JSONDecoder().decode(JSONValue.self, from: d), let ms = v["uptimeMs"].number {
            let days = Int(ms / 86_400_000), hours = Int(ms / 3_600_000)
            uptime = days >= 1 ? "\(days) day\(days == 1 ? "" : "s")" : hours >= 1 ? "\(hours)h" : "under an hour"
        }
        if let d = try? await store.api.call("/api/defaults"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) {
            projectsDir = (v["projectsDir"].string ?? "~/bench-projects").replacingOccurrences(of: #"^/(home|Users)/[^/]+"#, with: "~", options: .regularExpression)
        }
    }

    private func checkUpdate() {
        Task {
            do {
                let v = try JSONDecoder().decode(JSONValue.self, from: try await store.api.call("/api/server/update"))
                canUpdate = v["canUpdate"].bool && !(v["newer"]?.isNull ?? true)
                newer = v["newer"]?.string
                note = canUpdate ? (newer.map { "\($0) is available" } ?? "A newer Bench is available") : (v["reason"].string ?? "Up to date")
            } catch { note = error.localizedDescription }
        }
    }

    private func runUpdate() {
        Task {
            do {
                _ = try await store.api.call("/api/server/update", method: "POST", body: [:])
                note = "Updating. Bench restarts when it's done."
                canUpdate = false
            } catch { note = error.localizedDescription }
        }
    }

    private func saveDir() {
        editingDir = false
        let dir = projectsDir
        Task { _ = try? await store.api.call("/api/defaults", method: "PUT", body: ["projectsDir": dir]); await load() }
    }
}

// MARK: Models

private struct ModelsPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var defaults: JSONValue?

    var body: some View {
        Group2 {
            GroupRow(title: "Default model", first: true) {
                Menu {
                    Button("Provider default") { put(["modelId": NSNull()]) }
                    ForEach(store.providers.filter { $0.enabled && $0.ready != false }) { p in
                        Section(p.name) {
                            ForEach(p.models.filter(\.enabled)) { m in Button(m.label) { put(["modelId": m.id, "providerId": p.id]) } }
                        }
                    }
                } label: { Text(defaultModel) }
                .fixedSize()
            }
            GroupRow(title: "Effort") {
                Picker("", selection: Binding(get: { defaults?["effort"]?.string ?? "" }, set: { put(["effort": $0.isEmpty ? NSNull() : $0]) })) {
                    Text("Default").tag("")
                    ForEach(Effort.allCases) { Text($0.label).tag($0.rawValue) }
                }
                .pickerStyle(.segmented).labelsHidden().fixedSize()
            }
            GroupRow(title: "Permission mode") {
                Picker("", selection: Binding(get: { defaults?["mode"]?.string ?? "acceptEdits" }, set: { put(["mode": $0]) })) {
                    ForEach(Mode.allCases) { Text($0.label).tag($0.rawValue) }
                }
                .labelsHidden().fixedSize()
            }
        }
        ForEach(store.providers) { p in
            Group2 {
                GroupRow(title: p.name, sub: p.ready == false ? (p.reason ?? "Not set up") : "Ready", first: true) {
                    Toggle("", isOn: Binding(get: { p.enabled }, set: { setProvider(p.id, $0) }))
                        .labelsHidden().toggleStyle(.switch).help("Use \(p.name)")
                }
                if p.enabled {
                    ForEach(p.models) { m in
                        GroupRow(title: m.label) {
                            Toggle("", isOn: Binding(get: { m.enabled }, set: { setModel(m.id, $0) }))
                                .labelsHidden().toggleStyle(.switch).controlSize(.small)
                        }
                    }
                } else {
                    GroupRow(title: "Off. Its models aren't offered in sessions or as a fallback.") { EmptyView() }
                        .foregroundStyle(theme.colors.muted)
                }
            }
        }
        .task { await load() }
    }

    private var defaultModel: String {
        guard let id = defaults?["modelId"]?.string else { return "Provider default" }
        return store.providers.flatMap(\.models).first { $0.id == id }?.label ?? id
    }

    private func load() async {
        if let d = try? await store.api.call("/api/defaults") { defaults = try? JSONDecoder().decode(JSONValue.self, from: d) }
        if let p = try? await store.api.providers() { store.providers = p }
    }
    private func put(_ body: [String: Any]) { Task { _ = try? await store.api.call("/api/defaults", method: "PUT", body: body); await load() } }
    private func setModel(_ id: String, _ on: Bool) { Task { _ = try? await store.api.call("/api/models/\(BenchAPI.enc(id))", method: "PATCH", body: ["enabled": on]); await load() } }
    private func setProvider(_ id: String, _ on: Bool) { Task { _ = try? await store.api.call("/api/providers/\(BenchAPI.enc(id))", method: "PATCH", body: ["enabled": on]); await load() } }
}

// MARK: Subagents

private struct SubagentsPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var routing: JSONValue?

    static let tiers: [(String, String, String)] = [
        ("architect", "Architect", "Unclear problems, design, hard debugging"),
        ("implementer", "Implementer", "Well-specified edits, tests, running the check"),
        ("explorer", "Explorer", "Read-only lookups: finding, reading, summarising"),
    ]

    var body: some View {
        Group2 {
            GroupRow(title: "Route by tier", sub: "Off: helpers run as Claude Code decides", first: true) {
                Toggle("", isOn: Binding(get: { if case .bool(let b)? = routing?["enabled"] { return b }; return true }, set: { save("enabled", $0) }))
                    .labelsHidden().toggleStyle(.switch)
            }
            ForEach(Array(Self.tiers.enumerated()), id: \.offset) { _, t in
                GroupRow(title: t.1, sub: t.2) {
                    Menu {
                        ForEach(Array(options.enumerated()), id: \.offset) { _, o in Button(o.1) { save(t.0, o.0) } }
                    } label: { Text(label(routing?[t.0]?.string)) }
                    .fixedSize()
                }
            }
        }
        Text("Every session uses these unless its project or task sets its own.").font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.leading, 4)
            .task { await load() }
    }

    private var options: [(String, String)] {
        let claude = store.providers.first { $0.id.contains("claude") || $0.name.contains("Claude") }?.models.filter(\.enabled) ?? []
        return [("haiku", "Haiku"), ("sonnet", "Sonnet"), ("opus", "Opus"), ("inherit", "Same as the session")] + claude.map { ($0.name, $0.label) }
    }

    private func label(_ v: String?) -> String {
        guard let v, !v.isEmpty else { return "Not set" }
        return options.first { $0.0 == v }?.1 ?? v
    }

    private func load() async {
        if let d = try? await store.api.call("/api/defaults"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { routing = v["routing"] }
    }

    private func save(_ key: String, _ value: Any) {
        var r: [String: Any] = [:]
        for (k, v) in routing?.object ?? [:] {
            switch v {
            case .string(let s): r[k] = s
            case .bool(let b): r[k] = b
            default: break
            }
        }
        r[key] = value
        Task { _ = try? await store.api.call("/api/defaults", method: "PUT", body: ["routing": r]); await load() }
    }
}

// MARK: Usage limit

private struct LimitPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var defaults: JSONValue?

    var body: some View {
        let limits = defaults?["limits"]
        let fb = limits?["fallback"]
        let paused = store.sessions.filter { $0.pause != nil }
        if !paused.isEmpty {
            Group2 {
                GroupRow(title: "Claude", first: true) {
                    HStack(spacing: 6) {
                        Circle().fill(theme.colors.warn).frame(width: 7, height: 7)
                        Text("Limit reached · \(paused.count) session\(paused.count == 1 ? "" : "s") paused").foregroundStyle(theme.colors.muted)
                    }
                }
            }
        }
        GroupLabel("When a session hits the limit")
        Group2 {
            GroupRow(title: "Resume it when the limit resets", sub: "Sends \u{201C}Continue where you left off.\u{201D}", first: true) {
                Toggle("", isOn: Binding(get: { if case .bool(let b)? = limits?["autoResume"] { return b }; return true }, set: { on in
                    store.autoResumeDefault = on
                    put(["limits": ["autoResume": on]])
                }))
                .labelsHidden().toggleStyle(.switch)
            }
            GroupRow(title: "Hand the work to", sub: "A new session on another provider picks it up straight away") {
                Menu {
                    Button("Nobody, wait for the limit") { put(["limits": ["fallback": NSNull()]]) }
                    ForEach(store.providers.filter { $0.enabled && $0.ready != false && !$0.name.contains("Claude login") }) { p in
                        Section(p.name) {
                            ForEach(p.models.filter(\.enabled)) { m in
                                Button(m.label) { put(["limits": ["fallback": ["providerId": p.id, "modelId": m.id]]]) }
                            }
                        }
                    }
                } label: { Text(fallbackLabel(fb)) }
                .fixedSize()
            }
        }
        Text("Sessions go back to Claude once the limit resets.").font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.leading, 4)
            .task { await load() }
    }

    private func fallbackLabel(_ fb: JSONValue?) -> String {
        if let mid = fb?["modelId"]?.string, let m = store.providers.flatMap(\.models).first(where: { $0.id == mid }) { return m.label }
        if let pid = fb?["providerId"]?.string { return store.providers.first { $0.id == pid }?.name ?? pid }
        return "Nobody, wait for the limit"
    }

    private func load() async {
        if let d = try? await store.api.call("/api/defaults") { defaults = try? JSONDecoder().decode(JSONValue.self, from: d) }
    }
    private func put(_ body: [String: Any]) { Task { _ = try? await store.api.call("/api/defaults", method: "PUT", body: body); await load() } }
}

// MARK: Devices

private struct DevicesPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var devices: [JSONValue] = []
    @State private var link: String?
    @State private var revoking: JSONValue?

    var body: some View {
        Group2 {
            ForEach(Array(devices.enumerated()), id: \.offset) { i, d in
                let mine = d["id"].string == store.me?.device?.id
                GroupRow(title: d["name"].string ?? "Device", sub: mine ? "This Mac" : d["last_seen_at"].string.map { "Last seen \(Format.ago($0)) ago" } ?? "Never seen", first: i == 0) {
                    if !mine { Button("Remove…") { revoking = d }.buttonStyle(TonalButton(height: 24)) }
                }
            }
            if devices.isEmpty { GroupRow(title: "Loading…", first: true) { EmptyView() } }
        }
        HStack(alignment: .center, spacing: 18) {
            if let link, let img = qr(link) {
                Image(nsImage: img).interpolation(.none).resizable().frame(width: 112, height: 112)
                    .padding(8).background(.white, in: RoundedRectangle(cornerRadius: 12))
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Pair a phone or another computer").font(.system(size: 13))
                Text(link == nil ? "Make a code to scan with Bench on your phone." : "Scan this code with Bench on your phone. It works once, for 10 minutes.")
                    .font(.system(size: 12)).foregroundStyle(theme.colors.muted).fixedSize(horizontal: false, vertical: true)
                if let link {
                    Button("Copy Pairing Link") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(link, forType: .string) }
                        .buttonStyle(TonalButton(height: 26))
                } else {
                    Button("Make a Pairing Code") { pair() }.buttonStyle(TonalButton(height: 26))
                }
            }
        }
        .padding(.top, Space.sm)
        .task { await load() }
        .confirmationDialog("Remove \(revoking?["name"].string ?? "this device")?", isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } })) {
            Button("Remove", role: .destructive) {
                let id = revoking?["id"].string ?? ""
                Task { _ = try? await store.api.call("/api/devices/\(BenchAPI.enc(id))", method: "DELETE"); await load() }
            }
        } message: { Text("It will need a new pairing code to connect again.") }
    }

    private func load() async {
        if let d = try? await store.api.call("/api/devices"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { devices = v.array }
    }

    private func pair() {
        Task {
            if let d = try? await store.api.call("/api/pairing", method: "POST", body: [:]), let v = try? JSONDecoder().decode(JSONValue.self, from: d) {
                link = v["urls"].array.first?.string
            }
        }
    }

    private func qr(_ s: String) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        guard let out = f.outputImage, let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: out.extent.width, height: out.extent.height))
    }
}

// MARK: Notifications

private struct NotificationsPage: View {
    @AppStorage("alert.needs") private var needs = true
    @AppStorage("alert.finished") private var finished = true
    @AppStorage("alert.errors") private var errors = true
    @AppStorage("alert.panel") private var panel = true
    @AppStorage("alert.sound") private var sound = false
    @Environment(\.theme) private var theme

    var body: some View {
        GroupLabel("Tell me when")
        Group2 {
            GroupRow(title: "Claude needs me", first: true) { toggle($needs) }
            GroupRow(title: "A session finishes") { toggle($finished) }
            GroupRow(title: "A check fails or a session errors") { toggle($errors) }
        }
        GroupLabel("Approvals")
        Group2 {
            GroupRow(title: "Show the approval panel over other apps", first: true) { toggle($panel) }
            GroupRow(title: "Play a sound") { toggle($sound) }
        }
        Text("Approvals answered on your phone disappear here too.").font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.leading, 4)
    }

    private func toggle(_ b: Binding<Bool>) -> some View { Toggle("", isOn: b).labelsHidden().toggleStyle(.switch) }
}

// MARK: Appearance

private struct AppearancePage: View {
    @AppStorage("appearance") private var appearance = Appearance.system.rawValue
    @AppStorage("menuBar") private var menuBar = true
    @AppStorage("keepRunning") private var keepRunning = true
    @AppStorage("quickPrompt") private var quickPrompt = true
    @AppStorage("editor") private var editor = Editors.preferred?.id ?? ""
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 14) {
            ForEach(Appearance.allCases) { a in
                Button { appearance = a.rawValue } label: {
                    VStack(spacing: 8) {
                        tile(a)
                            .frame(width: 120, height: 80)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(appearance == a.rawValue ? theme.colors.accent : theme.colors.fill(0.12), lineWidth: appearance == a.rawValue ? 3 : 1))
                        Text(a.label).font(.system(size: 12.5)).foregroundStyle(appearance == a.rawValue ? theme.colors.text : theme.colors.muted)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        Group2 {
            GroupRow(title: "Show Bench in the menu bar", first: true) { Toggle("", isOn: $menuBar).labelsHidden().toggleStyle(.switch) }
            GroupRow(title: "Keep running when the window is closed") { Toggle("", isOn: $keepRunning).labelsHidden().toggleStyle(.switch) }
            GroupRow(title: "Quick prompt with ⌥Space from any app") {
                Toggle("", isOn: Binding(get: { quickPrompt }, set: { on in
                    quickPrompt = on
                    if on { QuickPrompt.shared.registerHotKey() } else { QuickPrompt.shared.unregisterHotKey() }
                })).labelsHidden().toggleStyle(.switch)
            }
            if !Editors.installed.isEmpty {
                GroupRow(title: "Open files in") {
                    Picker("", selection: $editor) {
                        ForEach(Editors.installed) { e in Text(e.name).tag(e.id) }
                    }
                    .labelsHidden().fixedSize()
                }
            }
        }
    }

    @ViewBuilder private func tile(_ a: Appearance) -> some View {
        switch a {
        case .light: preview(macLight)
        case .dark: preview(macDark)
        case .system: HStack(spacing: 0) { preview(macLight); preview(macDark) }
        }
    }

    private func preview(_ t: BenchThemeSpec) -> some View {
        VStack(spacing: 0) {
            Rectangle().fill(t.colors.surface3).frame(height: 14)
            HStack(spacing: 6) {
                RoundedRectangle(cornerRadius: 3).fill(t.colors.surface1).frame(width: 24)
                VStack(alignment: .leading, spacing: 5) {
                    RoundedRectangle(cornerRadius: 2).fill(t.colors.muted.opacity(0.5)).frame(height: 5)
                    RoundedRectangle(cornerRadius: 2).fill(t.colors.muted.opacity(0.3)).frame(width: 30, height: 5)
                    Circle().fill(t.colors.accent).frame(width: 8, height: 8)
                }
            }
            .padding(6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(t.colors.bg)
        }
    }
}

// MARK: Shortcuts

private struct ShortcutsPage: View {
    private let keys: [(String, String)] = [
        ("New session", "⌘N"), ("Add project", "⇧⌘N"), ("Go anywhere or run a command", "⌘K"),
        ("Needs you · Running · All sessions", "⌘1 ⌘2 ⌘3"), ("Show or hide the inspector", "⌘J"),
        ("Show or hide the terminal", "⇧⌘T"), ("Send", "⌘↩"), ("Allow", "⌘↩"), ("Deny", "⌘⌫"),
        ("Stop Claude", "⌘."), ("Approve the request on screen", "⇧⌘A"), ("Find in the conversation", "⌘F"),
        ("Previous · next turn", "⌥⌘↑ ⌥⌘↓"), ("Your nine latest sessions", "⌥⌘1 – ⌥⌘9"),
        ("New tab · close tab", "⌘T ⌘W"), ("Next · previous tab", "⇧⌘] ⇧⌘["), ("Split with the previous tab", "⌘\\"),
        ("Open the session in a new window", "⌥⌘O"), ("Quick prompt, from any app", "⌥Space"),
        ("Merge or ship the task", "⇧⌘M"), ("Dashboard", "⇧⌘D"), ("Settings", "⌘,"),
    ]
    @Environment(\.theme) private var theme

    var body: some View {
        Group2 {
            ForEach(Array(keys.enumerated()), id: \.offset) { i, k in
                GroupRow(title: k.0, first: i == 0) {
                    Text(k.1).font(.system(size: 12)).padding(.horizontal, 7).padding(.vertical, 2)
                        .background(theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 5))
                }
            }
        }
    }
}
