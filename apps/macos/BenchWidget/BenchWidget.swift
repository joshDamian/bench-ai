import SwiftUI
import WidgetKit

/// The snapshot the app writes (Bench/System/SystemReach.swift); kept in step by hand.
struct GlanceSnapshot: Codable {
    struct Item: Codable { let id: String; let title: String; let project: String; let since: Date? }
    let needs: Int
    let needsTitles: [String]
    let working: [Item]
    let updated: Date

    static let empty = GlanceSnapshot(needs: 0, needsTitles: [], working: [], updated: .distantPast)

    static func read() -> GlanceSnapshot {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.ai.bench.app")?.appendingPathComponent("glance.json"),
              let d = try? Data(contentsOf: url), let s = try? JSONDecoder().decode(GlanceSnapshot.self, from: d) else { return .empty }
        return s
    }
}

struct GlanceEntry: TimelineEntry {
    let date: Date
    let snap: GlanceSnapshot
}

struct GlanceProvider: TimelineProvider {
    func placeholder(in context: Context) -> GlanceEntry {
        GlanceEntry(date: .now, snap: GlanceSnapshot(needs: 1, needsTitles: ["Fix the login redirect"], working: [.init(id: "1", title: "Add CSV export", project: "web", since: .now.addingTimeInterval(-180))], updated: .now))
    }
    func getSnapshot(in context: Context, completion: @escaping (GlanceEntry) -> Void) { completion(GlanceEntry(date: .now, snap: GlanceSnapshot.read())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<GlanceEntry>) -> Void) {
        // The app reloads the widget when anything changes; this is only the fallback.
        completion(Timeline(entries: [GlanceEntry(date: .now, snap: GlanceSnapshot.read())], policy: .after(.now.addingTimeInterval(15 * 60))))
    }
}

struct GlanceView: View {
    @Environment(\.widgetFamily) private var family
    let entry: GlanceEntry

    var body: some View {
        let s = entry.snap
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Bench").font(.system(size: 13, weight: .semibold))
                Spacer()
                if s.needs > 0 {
                    Text("\(s.needs)").font(.system(size: 12, weight: .bold)).foregroundStyle(.black)
                        .padding(.horizontal, 7).padding(.vertical, 1).background(.orange, in: Capsule())
                }
            }
            if s.needs > 0 {
                Text(s.needs == 1 ? "Needs you" : "\(s.needs) need you").font(.system(size: 12, weight: .medium)).foregroundStyle(.orange)
                ForEach(s.needsTitles.prefix(family == .systemSmall ? 1 : 2), id: \.self) { Text($0).font(.system(size: 12)).lineLimit(1) }
            }
            if s.working.isEmpty {
                if s.needs == 0 { Spacer(); Text("Nothing running").font(.system(size: 12)).foregroundStyle(.secondary) }
            } else {
                Text("Working").font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary).padding(.top, 2)
                ForEach(s.working.prefix(family == .systemSmall ? 2 : 4), id: \.id) { w in
                    HStack(spacing: 6) {
                        Circle().fill(.tint).frame(width: 6, height: 6)
                        Text(w.title).font(.system(size: 12)).lineLimit(1)
                        Spacer(minLength: 4)
                        if let since = w.since, family != .systemSmall {
                            Text(since, style: .timer).font(.system(size: 11).monospacedDigit()).foregroundStyle(.secondary).multilineTextAlignment(.trailing).frame(width: 52, alignment: .trailing)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .widgetURL(URL(string: "bench://open"))
        .containerBackground(.fill.tertiary, for: .widget)
    }
}

struct BenchGlanceWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "BenchGlance", provider: GlanceProvider()) { GlanceView(entry: $0) }
            .configurationDisplayName("Bench")
            .description("What needs you and what Claude is working on.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct BenchWidgets: WidgetBundle {
    var body: some Widget { BenchGlanceWidget() }
}
