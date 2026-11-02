import SwiftUI

/// The Dashboard window (⇧⌘D): what the server machine is doing and what Bench has spent,
/// refreshed every five seconds while it's open.
struct DashboardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var metrics: JSONValue?
    @State private var spend: [JSONValue] = []

    var body: some View {
        ScrollView {
            if let store = model.store {
                content(store)
            } else {
                Text("Not connected to a server").foregroundStyle(theme.colors.muted).padding(40)
            }
        }
        .background(theme.colors.bg)
        .navigationTitle("Dashboard")
        .navigationSubtitle(subtitle)
        .task(id: model.store?.api.config.baseURL) {
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    private var subtitle: String {
        let host = metrics?["host"]
        let up = host?["uptimeMs"]?.number.map { ms -> String in
            let d = Int(ms / 86_400_000)
            return d >= 1 ? "up \(d) day\(d == 1 ? "" : "s")" : "up \(Int(ms / 3_600_000))h"
        }
        return [host?["hostname"]?.string, up].compactMap { $0 }.joined(separator: " · ")
    }

    @ViewBuilder private func content(_ store: BenchStore) -> some View {
        let host = metrics?["host"]
        let memTotal = host?["memTotal"]?.number
        let memFree = host?["memFree"]?.number
        let total = spend.reduce(0.0) { $0 + ($1["cost"].number ?? 0) }
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 16) {
                tile("Spent", Format.cost(total), "all time")
                tile("Running now", "\(store.sessions.filter { $0.status == "running" }.count)", "\(store.sessions.filter { $0.archived_at == nil }.count) open sessions")
                tile("Memory used", memTotal.flatMap { t in memFree.map { bytes(t - $0) } } ?? "—", memTotal.map { "of \(bytes($0))" } ?? "")
                tile("Load", host?["loadavg"]?.array.first?.number.map { String(format: "%.2f", $0) } ?? "—", host?["cpus"]?.number.map { "\(Int($0)) cores" } ?? "")
            }
            HStack(alignment: .top, spacing: 16) {
                card("Spend by model") {
                    let max = spend.map { $0["cost"].number ?? 0 }.max() ?? 1
                    if spend.isEmpty { Text("Nothing spent yet.").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted) }
                    ForEach(Array(spend.enumerated()), id: \.offset) { _, s in
                        bar(label(s["model"].string ?? "", store), Format.cost(s["cost"].number ?? 0),
                            "\(Int(s["sessions"].number ?? 0)) sessions", (s["cost"].number ?? 0) / Swift.max(max, 0.01), theme.colors.accent)
                    }
                }
                card("What Bench is holding") {
                    let procs = metrics?["procs"]?.array ?? []
                    let max = procs.map { $0["rssKb"].number ?? 0 }.max() ?? 1
                    if procs.isEmpty { Text("Reading the machine…").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted) }
                    ForEach(Array(procs.prefix(10).enumerated()), id: \.offset) { _, p in
                        bar(p["label"].string ?? "", "\(bytes((p["rssKb"].number ?? 0) * 1024)) · \(Int(p["pcpu"].number ?? 0))% CPU",
                            p["sub"].string, (p["rssKb"].number ?? 0) / Swift.max(max, 1), p["kind"].string == "claude" ? theme.colors.accent : theme.colors.muted)
                    }
                }
            }
            let storage = metrics?["storage"]?["entries"]?.array ?? []
            if !storage.isEmpty {
                card("Storage") {
                    ForEach(Array(storage.enumerated()), id: \.offset) { _, e in
                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(e["name"].string ?? "").font(.system(size: 13))
                                if let n = e["note"].string { Text(n).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted) }
                            }
                            Spacer()
                            Text(bytes(e["bytes"].number ?? 0)).font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
                        }
                        .padding(.vertical, 3)
                    }
                }
            }
        }
        .padding(24)
    }

    private func tile(_ label: String, _ value: String, _ sub: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            Text(value).font(.system(size: 26, weight: .semibold)).monospacedDigit()
            Text(sub).font(.system(size: 12)).foregroundStyle(theme.colors.muted).lineLimit(1)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: 14))
    }

    private func card<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.system(size: 13, weight: .semibold))
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: 14))
    }

    private func bar(_ title: String, _ value: String, _ sub: String?, _ fraction: Double, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(title).font(.system(size: 12.5)).lineLimit(1)
                if let sub { Text(sub).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1) }
                Spacer()
                Text(value).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(theme.colors.fill(0.07))
                    Capsule().fill(color).frame(width: Swift.max(4, g.size.width * Swift.min(1, fraction)))
                }
            }
            .frame(height: 6)
        }
    }

    private func label(_ model: String, _ store: BenchStore) -> String {
        store.providers.flatMap(\.models).first { $0.id == model || $0.name == model }?.label ?? model
    }

    private func bytes(_ n: Double) -> String {
        n >= 1e9 ? String(format: "%.1f GB", n / 1e9) : n >= 1e6 ? String(format: "%.0f MB", n / 1e6) : String(format: "%.0f KB", n / 1e3)
    }

    private func load() async {
        guard let store = model.store else { return }
        if let d = try? await store.api.call("/api/metrics") { metrics = try? JSONDecoder().decode(JSONValue.self, from: d) }
        if let d = try? await store.api.call("/api/usage/models"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) {
            spend = v.array.sorted { ($0["cost"].number ?? 0) > ($1["cost"].number ?? 0) }
        }
    }
}
