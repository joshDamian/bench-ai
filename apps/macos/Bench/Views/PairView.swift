import SwiftUI

/// The first screen. A server on this Mac needs no pairing - loopback is let in - so the
/// default is to find it; a server elsewhere takes a `bench pair` link.
struct PairView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var link = ""
    @State private var name = Host.current().localizedName ?? "Mac"
    @State private var busy = false
    @State private var error: String?
    @State private var localFound = false
    @State private var localURL = "http://localhost:4317"

    @State private var localVersion: String?

    var body: some View {
        VStack(spacing: Space.xl) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 64, height: 64)
            VStack(spacing: Space.sm) {
                Text("Connect to Bench").font(.system(size: 20, weight: .semibold))
                (Text("Paste the link from ") + Text("bench pair").font(.system(size: 13, design: .monospaced)) + Text(", or Settings › Pair a device in Bench."))
                    .font(.system(size: 13))
                    .foregroundStyle(theme.colors.muted)
                    .multilineTextAlignment(.center)
            }
            VStack(alignment: .leading, spacing: 10) {
                Text("Pairing link").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                TextField("", text: $link, prompt: Text("https://your-machine.ts.net/#/pair/…"))
                    .textFieldStyle(.plain)
                    .font(.system(size: 13))
                    .padding(.horizontal, Space.md).frame(height: 36)
                    .background(theme.colors.surface1, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(theme.colors.lineStrong))
                    .onSubmit(pair)
                Button { pair() } label: {
                    Text(busy ? "Connecting…" : "Connect").frame(maxWidth: .infinity)
                }
                .buttonStyle(CapsuleButton(kind: .primary))
                .disabled(busy || link.isEmpty)
                if let error { Text(error).font(.system(size: 12)).foregroundStyle(theme.colors.bad).textSelection(.enabled) }
            }
            if localFound {
                let old = localVersion.map { Self.older($0) } ?? false
                HStack(spacing: 10) {
                    Circle().fill(old ? theme.colors.warn : theme.colors.good).frame(width: 6, height: 6)
                    Text(old ? "This Mac runs an older Bench (\(localVersion ?? "")). Some features won't work."
                             : "Bench is running on this Mac" + (localVersion.map { " (\($0))" } ?? "") + ".")
                    Spacer()
                    Button(old ? "Use Anyway" : "Use This Mac") { connectLocal() }
                        .buttonStyle(.plain).foregroundStyle(theme.colors.text.opacity(0.8))
                }
                .font(.system(size: 12.5))
                .foregroundStyle(theme.colors.muted)
                .padding(.top, Space.lg)
                .overlay(alignment: .top) { Rectangle().fill(theme.colors.line).frame(height: 1) }
            }
        }
        .frame(width: 400)
        .padding(32)
        .background(theme.colors.surface2, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(theme.colors.line))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bg)
        .task {
            // A server on this Mac is offered too, with a warning when it's an old one.
            let (reachable, _) = await BenchAPI.ping(ServerConfig(baseURL: localURL, token: nil))
            localFound = reachable
            if reachable, let d = try? await BenchAPI(config: ServerConfig(baseURL: localURL, token: nil)).call("/api/server/status"),
               let v = try? JSONDecoder().decode(JSONValue.self, from: d) { localVersion = v["version"].string }
        }
        .onOpenURL { url in
            // bench://pair?link=… from a browser hands the link straight here.
            if let l = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "link" })?.value {
                link = l
                pair()
            }
        }
    }

    /// Older than the 2.0 server these clients are written for.
    static func older(_ v: String) -> Bool { (Int(v.split(separator: ".").first ?? "") ?? 0) < 2 }

    private func connectLocal() {
        guard let base = Pairing.normalize(localURL) else { error = "That isn't an address."; return }
        busy = true
        error = nil
        Task {
            let c = ServerConfig(baseURL: base, token: nil)
            let (reachable, allowed) = await BenchAPI.ping(c)
            busy = false
            if !reachable { error = "Nothing answered at \(base). Is `bench serve` running?" }
            else if !allowed { error = "\(base) isn't this Mac's loopback, so it needs pairing. Use the pairing link below." }
            else { model.pair(c) }
        }
    }

    private func pair() {
        guard let parsed = Pairing.parse(link) else {
            error = "That isn't a pairing link. It looks like https://your-machine.ts.net/#/pair/…"
            return
        }
        busy = true
        error = nil
        Task {
            do { model.pair(try await BenchAPI.pair(parsed, name: name.isEmpty ? "Mac" : name)) }
            catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}
