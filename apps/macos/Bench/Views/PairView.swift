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

    var body: some View {
        VStack(spacing: Space.xl) {
            VStack(spacing: Space.sm) {
                Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 72, height: 72)
                Text("Connect to Bench").font(.title2.weight(.semibold))
            }

            GroupBox {
                VStack(alignment: .leading, spacing: Space.md) {
                    Label("Bench on this Mac", systemImage: "desktopcomputer").font(.headline)
                    Text(localFound ? "Found a server at \(localURL)." : "Run `bench serve` (or install the service) and it will be found here.")
                        .foregroundStyle(theme.colors.muted)
                    HStack {
                        TextField("Address", text: $localURL).textFieldStyle(.roundedBorder)
                        Button("Connect") { connectLocal() }
                            .buttonStyle(.borderedProminent)
                            .disabled(busy)
                    }
                }
                .padding(Space.sm)
            }

            GroupBox {
                VStack(alignment: .leading, spacing: Space.md) {
                    Label("Bench on another machine", systemImage: "network").font(.headline)
                    Text("On that machine, run `bench pair` or open Settings → Pair a device, and paste the link here.")
                        .foregroundStyle(theme.colors.muted)
                    TextField("https://machine.tailnet.ts.net/#/pair/…", text: $link).textFieldStyle(.roundedBorder)
                    HStack {
                        TextField("This Mac's name", text: $name).textFieldStyle(.roundedBorder)
                        Button("Pair") { pair() }
                            .disabled(busy || link.isEmpty)
                    }
                }
                .padding(Space.sm)
            }

            if busy { ProgressView().controlSize(.small) }
            if let error { Text(error).foregroundStyle(theme.colors.bad).multilineTextAlignment(.center) }
        }
        .frame(maxWidth: 480)
        .padding(Space.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bg)
        .task {
            // The default port; a server that answers is offered at once.
            let (reachable, _) = await BenchAPI.ping(ServerConfig(baseURL: localURL, token: nil))
            localFound = reachable
        }
        .onOpenURL { url in
            // bench://pair?link=… from a browser hands the link straight here.
            if let l = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "link" })?.value {
                link = l
                pair()
            }
        }
    }

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
