import SwiftUI
import CoreImage.CIFilterBuiltins

/// ⌘, — Server, Models, Alerts and Theme, as the Settings boards draw them.
struct SettingsView: View {
    var body: some View {
        TabView {
            ServerSettings().tabItem { Label("Server", systemImage: "server.rack") }
            ModelSettings().tabItem { Label("Models", systemImage: "cpu") }
            AlertSettings().tabItem { Label("Alerts", systemImage: "bell") }
            ThemeSettings().tabItem { Label("Theme", systemImage: "paintpalette") }
        }
        .frame(width: 620)
    }
}

// MARK: Server

private struct ServerSettings: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var uptime: String?
    @State private var confirmUnpair = false
    @State private var pairing: Pairing?
    @State private var update: String?
    @State private var canUpdate = false
    @State private var error: String?

    struct Pairing: Decodable { let code: String; let urls: [String]; let expiresIn: Int? }

    var body: some View {
        Form {
            if let store = model.store {
                Section {
                    LabeledContent("Server") {
                        Text(store.api.config.baseURL.replacingOccurrences(of: #"^https?://"#, with: "", options: .regularExpression))
                            .font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
                    }
                    LabeledContent("Status") {
                        HStack(spacing: 6) {
                            Circle().fill(store.connected ? theme.colors.good : theme.colors.warn).frame(width: 6, height: 6)
                            Text(store.connected ? "Connected" + (store.version.map { " · Bench \($0)" } ?? "") : "Connecting…")
                        }
                    }
                    LabeledContent("This Mac", value: store.me?.device?.name.map { "Paired as \($0)" } ?? (store.api.config.isLoopback ? "On the server itself" : "Paired"))
                }
                Section {
                    if let uptime { LabeledContent("Uptime", value: uptime) }
                    LabeledContent("Bench on the server") {
                        Button(canUpdate ? "Update…" : "Check for Updates") { canUpdate ? runUpdate() : checkUpdate() }
                    }
                    if let update { Text(update).font(.system(size: 12)).foregroundStyle(theme.colors.muted) }
                }
                Section {
                    LabeledContent("Another phone or computer") { Button("Pair…") { pair() } }
                    LabeledContent("Disconnect this Mac") { Button("Disconnect…") { confirmUnpair = true } }
                }
                if let error { Text(error).foregroundStyle(theme.colors.bad) }
            } else {
                Text("Not connected to a server").foregroundStyle(theme.colors.muted)
            }
        }
        .formStyle(.grouped)
        .task { await loadStatus() }
        .sheet(item: Binding(get: { pairing.map { IdentifiedPairing(p: $0) } }, set: { if $0 == nil { pairing = nil } })) { item in
            PairSheet(pairing: item.p) { pairing = nil }
        }
        .confirmationDialog("Disconnect this Mac?", isPresented: $confirmUnpair) {
            Button("Disconnect", role: .destructive) { model.unpair() }
        } message: {
            Text("You'll need to pair again to reconnect.")
        }
    }

    struct IdentifiedPairing: Identifiable { let p: Pairing; var id: String { p.code } }

    private func loadStatus() async {
        guard let store = model.store, let d = try? await store.api.call("/api/server/status"),
              let v = try? JSONDecoder().decode(JSONValue.self, from: d), let ms = v["uptimeMs"].number else { return }
        let days = Int(ms / 86_400_000), hours = Int(ms / 3_600_000)
        uptime = days >= 1 ? "\(days) day\(days == 1 ? "" : "s")" : hours >= 1 ? "\(hours) hour\(hours == 1 ? "" : "s")" : "Under an hour"
    }

    private func checkUpdate() {
        guard let store = model.store else { return }
        Task {
            do {
                let v = try JSONDecoder().decode(JSONValue.self, from: try await store.api.call("/api/server/update"))
                canUpdate = v["canUpdate"].bool && !(v["newer"]?.isNull ?? true)
                update = canUpdate ? "A newer Bench is ready to install." : v["reason"].string ?? "Bench is up to date."
            } catch { self.error = error.localizedDescription }
        }
    }

    private func runUpdate() {
        guard let store = model.store else { return }
        Task {
            do {
                _ = try await store.api.call("/api/server/update", method: "POST", body: [:])
                update = "Updating. Bench restarts when it's done."
                canUpdate = false
            } catch { self.error = error.localizedDescription }
        }
    }

    private func pair() {
        guard let store = model.store else { return }
        Task {
            do { pairing = try JSONDecoder().decode(Pairing.self, from: try await store.api.call("/api/pairing", method: "POST", body: [:])) }
            catch { self.error = error.localizedDescription }
        }
    }
}

/// A pairing link and its QR code, for a phone or another computer.
private struct PairSheet: View {
    @Environment(\.theme) private var theme
    let pairing: ServerSettings.Pairing
    let done: () -> Void

    var body: some View {
        let link = pairing.urls.first ?? ""
        VStack(spacing: Space.lg) {
            Text("Pair a Phone or Computer").font(.system(size: 15, weight: .semibold))
            Text("Scan this with the Bench app, or open the link on the other device. It works once, for 10 minutes.")
                .font(.system(size: 13)).foregroundStyle(theme.colors.muted).multilineTextAlignment(.center)
            if let img = qr(link) {
                Image(nsImage: img).interpolation(.none).resizable().frame(width: 200, height: 200)
                    .padding(Space.md).background(.white, in: RoundedRectangle(cornerRadius: 12))
            }
            HStack {
                Text(link).font(.system(size: 11.5, design: .monospaced)).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                Button("Copy") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(link, forType: .string) }
            }
            Button("Done", action: done).buttonStyle(CapsuleButton(kind: .primary)).keyboardShortcut(.defaultAction)
        }
        .padding(Space.xl)
        .frame(width: 420)
    }

    private func qr(_ s: String) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        guard let out = f.outputImage, let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: out.extent.width, height: out.extent.height))
    }
}

// MARK: Models

private struct ModelSettings: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var defaults: JSONValue?
    @State private var testing: String?
    @State private var tested: [String: String] = [:]

    var body: some View {
        Form {
            if let store = model.store {
                Section("New sessions start with") {
                    Picker("Model", selection: binding("modelId")) {
                        Text("Provider default").tag("")
                        ForEach(store.providers.filter { $0.enabled && $0.ready != false }) { p in
                            Section(p.name) {
                                ForEach(p.models.filter(\.enabled)) { m in Text("\(m.label) · \(p.name)").tag(m.id) }
                            }
                        }
                    }
                    Picker("Effort", selection: binding("effort")) {
                        Text("Model default").tag("")
                        ForEach(Effort.allCases) { Text($0.label).tag($0.rawValue) }
                    }
                    Picker("Permission mode", selection: binding("mode")) {
                        ForEach(Mode.allCases) { Text($0.label).tag($0.rawValue) }
                    }
                }
                ForEach(store.providers) { p in
                    Section {
                        ForEach(p.models) { m in
                            Toggle(isOn: Binding(get: { m.enabled }, set: { on in setModel(m.id, on) })) {
                                Text(m.label)
                            }
                            .disabled(!p.enabled)
                        }
                    } header: {
                        HStack(spacing: Space.sm) {
                            Circle().fill(p.ready == false ? theme.colors.warn : theme.colors.good).frame(width: 6, height: 6)
                            Text(p.name)
                            Spacer()
                            Text(tested[p.id] ?? (p.ready == false ? (p.reason ?? "Not set up") : "Ready"))
                                .font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(2).textCase(nil)
                            if p.ready != false {
                                Button(testing == p.id ? "Testing…" : "Test") { test(p) }.controlSize(.small).disabled(testing != nil)
                            }
                            Toggle("", isOn: Binding(get: { p.enabled }, set: { on in setProvider(p.id, on) })).labelsHidden().toggleStyle(.switch).controlSize(.mini)
                        }
                    }
                }
                Section("When the usage limit stops a session") {
                    Toggle("Resume it when the limit resets", isOn: Binding(
                        get: { defaults?["limits"]?["autoResume"].bool ?? true },
                        set: { on in put(["limits": ["autoResume": on]]) }
                    ))
                }
            }
        }
        .formStyle(.grouped)
        .task { await load() }
    }

    private func binding(_ key: String) -> Binding<String> {
        Binding(get: { defaults?[key]?.string ?? "" }, set: { v in
            var body: [String: Any] = [key: v.isEmpty ? NSNull() : v]
            if key == "modelId", let p = model.store?.providers.first(where: { $0.models.contains { $0.id == v } }) { body["providerId"] = p.id }
            put(body)
        })
    }

    private func load() async {
        guard let store = model.store else { return }
        if let d = try? await store.api.call("/api/defaults") { defaults = try? JSONDecoder().decode(JSONValue.self, from: d) }
        if let p = try? await store.api.providers() { store.providers = p }
    }

    private func put(_ body: [String: Any]) {
        guard let store = model.store else { return }
        Task { _ = try? await store.api.call("/api/defaults", method: "PUT", body: body); await load() }
    }

    private func setModel(_ id: String, _ on: Bool) {
        guard let store = model.store else { return }
        Task { _ = try? await store.api.call("/api/models/\(BenchAPI.enc(id))", method: "PATCH", body: ["enabled": on]); await load() }
    }

    private func setProvider(_ id: String, _ on: Bool) {
        guard let store = model.store else { return }
        Task { _ = try? await store.api.call("/api/providers/\(BenchAPI.enc(id))", method: "PATCH", body: ["enabled": on]); await load() }
    }

    private func test(_ p: ProviderInfo) {
        guard let store = model.store else { return }
        testing = p.id
        Task {
            do {
                _ = try await store.api.call("/api/providers/\(BenchAPI.enc(p.id))/test", method: "POST", body: ["modelId": p.models.first(where: \.enabled)?.id ?? NSNull()])
                tested[p.id] = "Works"
            } catch { tested[p.id] = error.localizedDescription }
            testing = nil
        }
    }
}

// MARK: Alerts

private struct AlertSettings: View {
    @AppStorage("alert.needs") private var needs = true
    @AppStorage("alert.finished") private var finished = false
    @AppStorage("alert.sound") private var sound = true

    var body: some View {
        Form {
            Section {
                Toggle("When a session needs you", isOn: $needs)
                Toggle("When a session finishes", isOn: $finished)
                Toggle("Play a sound", isOn: $sound)
            } footer: {
                Text("Alerts come from this Mac. Your phone and browser keep their own.")
            }
        }
        .formStyle(.grouped)
    }
}

// MARK: Theme

private struct ThemeSettings: View {
    @Environment(\.theme) private var theme
    @AppStorage("theme") private var themeId = "classic"
    @AppStorage("theme.matchSystem") private var matchSystem = false
    @AppStorage("theme.dark") private var darkId = "classic"
    @AppStorage("theme.light") private var lightId = "quiet-temple"

    var body: some View {
        Form {
            Section {
                Toggle("Match macOS appearance", isOn: $matchSystem)
                if matchSystem {
                    Picker("In Dark Mode", selection: $darkId) { ForEach(benchThemes) { Text($0.name).tag($0.id) } }
                    Picker("In Light Mode", selection: $lightId) { ForEach(benchThemes) { Text($0.name).tag($0.id) } }
                }
            }
            Section {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Space.md), count: 4), spacing: Space.lg) {
                    ForEach(benchThemes) { t in
                        Button { choose(t) } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                HStack(alignment: .bottom, spacing: 6) {
                                    RoundedRectangle(cornerRadius: 4).fill(t.colors.surface1).frame(height: 18)
                                    Circle().fill(t.colors.accent).frame(width: 18, height: 18)
                                }
                                .padding(Space.sm)
                                .frame(maxWidth: .infinity, minHeight: 64, alignment: .bottom)
                                .background(t.colors.bg, in: RoundedRectangle(cornerRadius: 8))
                                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(ring(t) ?? theme.colors.line, lineWidth: ring(t) == nil ? 1 : 2))
                                Text(t.name).font(.system(size: 12))
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, Space.xs)
            } footer: {
                Text("Set for this Mac only. Your phone and browser keep their own.")
            }
        }
        .formStyle(.grouped)
    }

    private func ring(_ t: BenchThemeSpec) -> Color? {
        if matchSystem {
            if t.id == darkId { return theme.colors.accent }
            if t.id == lightId { return theme.colors.muted }
            return nil
        }
        return t.id == themeId ? theme.colors.accent : nil
    }

    private func choose(_ t: BenchThemeSpec) {
        if matchSystem { if t.dark { darkId = t.id } else { lightId = t.id } } else { themeId = t.id }
    }
}
