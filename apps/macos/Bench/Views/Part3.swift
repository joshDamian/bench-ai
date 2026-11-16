import AppKit
import SwiftUI

// Bench for Mac, part 3 (the "Bench for Mac, part 3" boards): Research in the rail, Coming up
// and Activity in the Inbox, Schedules, the money and rules settings, and the call bar. The
// shapes are the Go server's, read as JSON the way the phone reads them.

/// What these views read beside the store's state: reports, schedules, activity. Kept once,
/// refreshed when a view appears and every half minute while one is on screen.
@MainActor @Observable
final class Extras {
    static let shared = Extras()
    var reports: [JSONValue] = []
    var schedules: [JSONValue] = []
    var activity: [JSONValue] = []
    var activityUnseen = 0
    private var loaded: Date?

    func load(_ api: BenchAPI, force: Bool = false) async {
        if !force, let l = loaded, Date().timeIntervalSince(l) < 5 { return }
        loaded = Date()
        async let r = try? api.call("/api/reports")
        async let s = try? api.call("/api/schedules")
        async let a = try? api.call("/api/activity")
        let (rd, sd, ad) = await (r, s, a)
        if let rd, let v = try? JSONDecoder().decode(JSONValue.self, from: rd) { reports = v.array }
        if let sd, let v = try? JSONDecoder().decode(JSONValue.self, from: sd) { schedules = v.array }
        if let ad, let v = try? JSONDecoder().decode(JSONValue.self, from: ad) { activity = v["items"].array; activityUnseen = Int(v["unseen"].number ?? 0) }
    }
}

/// Keeps Extras fresh while the view is on screen.
struct KeepExtras: ViewModifier {
    let api: BenchAPI
    func body(content: Content) -> some View {
        content.task {
            await Extras.shared.load(api, force: true)
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                await Extras.shared.load(api, force: true)
            }
        }
    }
}

enum When {
    static func date(_ iso: String?) -> Date? {
        guard let iso else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: iso) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: iso)
    }

    /// "Today 9:00", "Tomorrow 9:00", "Sat 8:00", "21 Nov, 8:00".
    static func next(_ iso: String?) -> String {
        guard let d = date(iso) else { return "" }
        let cal = Calendar.current
        let t = d.formatted(.dateTime.hour().minute())
        if cal.isDateInToday(d) { return "Today \(t)" }
        if cal.isDateInTomorrow(d) { return "Tomorrow \(t)" }
        if let days = cal.dateComponents([.day], from: cal.startOfDay(for: Date()), to: cal.startOfDay(for: d)).day, days < 6 {
            return d.formatted(.dateTime.weekday(.abbreviated)) + " \(t)"
        }
        return d.formatted(.dateTime.day().month(.abbreviated)) + ", \(t)"
    }

    /// A row's time: today's clock, else the weekday this week, else the date.
    static func short(_ iso: String?) -> String {
        guard let d = date(iso) else { return "" }
        let cal = Calendar.current
        if cal.isDateInToday(d) { return d.formatted(.dateTime.hour().minute()) }
        if let days = cal.dateComponents([.day], from: cal.startOfDay(for: d), to: cal.startOfDay(for: Date())).day, days < 6 { return d.formatted(.dateTime.weekday(.abbreviated)) }
        return d.formatted(.dateTime.day().month(.abbreviated))
    }

    static func days(_ s: String?) -> String {
        let d = Set((s ?? "").split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) })
        let names = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
        if d.count == 7 { return "Every day" }
        if d == [1, 2, 3, 4, 5] { return "Weekdays" }
        if d == [0, 6] { return "Weekends" }
        if d.count == 1, let x = d.first, x < 7 { return "\(names[x])s" }
        return d.sorted().filter { $0 < 7 }.map { String(names[$0].prefix(3)) }.joined(separator: ", ")
    }
}

/// A row in the column: title, a line, a time; highlighted when it is what the tab shows.
struct ColumnRow: View {
    @Environment(\.theme) private var theme
    let title: String
    var sub: String? = nil
    var subColor: Color? = nil
    var trailing: String? = nil
    var on = false
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 13)).lineLimit(2)
                    if let sub, !sub.isEmpty { Text(sub).font(.system(size: 11.5)).foregroundStyle(subColor ?? theme.colors.muted).lineLimit(1) }
                }
                Spacer(minLength: 4)
                if let trailing { Text(trailing).font(.system(size: 11)).foregroundStyle(theme.colors.muted).fixedSize() }
            }
            .foregroundStyle(theme.colors.text)
            .padding(.horizontal, 8).padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(on ? theme.colors.fill(0.11) : hover ? theme.colors.fill(0.05) : .clear, in: RoundedRectangle(cornerRadius: 9))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

struct ColumnLabel: View {
    @Environment(\.theme) private var theme
    let text: String
    var trailing: String? = nil
    var action: (() -> Void)? = nil
    var body: some View {
        HStack {
            Text(text).font(.system(size: 11.5, weight: .semibold)).foregroundStyle(theme.colors.muted)
            Spacer()
            if let trailing {
                Button(trailing) { action?() }.buttonStyle(.plain).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
            }
        }
        .padding(.horizontal, 8).padding(.top, 14).padding(.bottom, 4)
    }
}

// MARK: Research

/// The research project, made on first use (POST /api/research).
@MainActor
func researchProject(_ api: BenchAPI) async -> String? {
    guard let d = try? await api.call("/api/research", method: "POST", body: [:]), let v = try? JSONDecoder().decode(JSONValue.self, from: d) else { return nil }
    return v["id"].string
}

/// The Research column: ask, what is being researched, what is new to read, what schedules keep
/// up to date, and the rest, one row per question.
struct ResearchList: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var ask = ""
    @State private var starting = false

    var body: some View {
        let x = Extras.shared
        let researchId = store.projects.first { $0.name == "Research" }?.id
        let live = store.sessions.filter { $0.archived_at == nil }
        let readable = x.reports.filter { $0["kind"].string != "file" && !($0["title"].string ?? "").hasPrefix("Bench presents") }
        let fresh = readable.filter { r in r["kind"].string == "drafts" ? (r["unsent"].number ?? 0) > 0 : !r["seen"].bool }
        let researchScheds = x.schedules.filter { $0["project_id"].string == researchId || $0["keep_board"].bool || $0["watch"]?.object.isEmpty == false }
        let running = live.filter { s in s.status == "running" && (s.project_id == researchId || researchScheds.contains { $0["id"].string == s.schedule_id }) }
        let earlier = Self.earlier(store: store, readable: readable, fresh: fresh, researchId: researchId)

        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Research").font(.system(size: 15, weight: .semibold))
                Text("Everything Claude found out").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            }
            .padding(.horizontal, 8).padding(.bottom, 10)
            HStack(spacing: 6) {
                TextField("", text: $ask, prompt: Text("What should Claude find out?"))
                    .textFieldStyle(.plain).font(.system(size: 12.5))
                    .onSubmit { start() }
                if starting { ProgressView().controlSize(.small) }
            }
            .padding(.horizontal, 10).frame(height: 32)
            .background(theme.colors.fill(0.07), in: RoundedRectangle(cornerRadius: 9))
            .padding(.horizontal, 2)

            if !running.isEmpty {
                ColumnLabel(text: "Researching")
                ForEach(running) { s in
                    ColumnRow(title: s.title, sub: "Working", subColor: theme.colors.accent, trailing: Format.ago(s.updated_at), on: model.selection == s.id) { model.go(s.id) }
                }
            }
            if !fresh.isEmpty {
                ColumnLabel(text: "New for you", trailing: "\(fresh.count)")
                ForEach(Array(fresh.enumerated()), id: \.offset) { _, r in
                    let sid = r["session_id"].string ?? ""
                    ColumnRow(title: r["title"].string ?? "Report", sub: r["gist"].string ?? store.session(sid)?.title, trailing: When.short(r["modified"].string), on: model.selection == "research:\(sid)") { model.go("research:\(sid)") }
                }
            }
            if !researchScheds.isEmpty {
                ColumnLabel(text: "Kept up to date", trailing: "All") { model.go(Place.schedules.rawValue) }
                ForEach(Array(researchScheds.enumerated()), id: \.offset) { _, s in
                    let watch = s["watch"]?.object.isEmpty == false
                    ColumnRow(title: s["title"].string ?? "Schedule", sub: s["last_run_at"].string.map { "Updated \(Format.ago($0)) ago" } ?? "Not run yet", trailing: watch ? "Watch" : When.days(s["days"].string)) { model.go(Place.schedules.rawValue) }
                }
            }
            if !earlier.isEmpty {
                ColumnLabel(text: "Earlier")
                ForEach(earlier) { e in
                    let sub = e.made.isEmpty ? "Answered in the conversation" : (e.made.first?["title"].string ?? "") + (e.made.count > 1 ? ", and \(e.made.count - 1) more" : "")
                    ColumnRow(title: question(e.title), sub: sub, trailing: When.short(e.at), on: model.selection == (e.made.isEmpty ? e.id : "research:\(e.id)")) {
                        model.go(e.made.isEmpty ? e.id : "research:\(e.id)")
                    }
                }
            }
            if running.isEmpty && fresh.isEmpty && earlier.isEmpty {
                Text("Claude searches, compares and writes a report. Reports stay here, and the ones on a schedule stay up to date.")
                    .font(.system(size: 12)).foregroundStyle(theme.colors.muted).padding(.horizontal, 8).padding(.top, 14)
            }
        }
        .modifier(KeepExtras(api: store.api))
    }

    struct Earlier: Identifiable { let id: String; let title: String; let at: String; let made: [JSONValue] }

    /// One row per research: sessions with something already read, and research conversations
    /// that made nothing; newest first.
    static func earlier(store: BenchStore, readable: [JSONValue], fresh: [JSONValue], researchId: String?) -> [Earlier] {
        let freshIds = Set(fresh.compactMap { $0["path"].string })
        let made = Set(Extras.shared.reports.compactMap { $0["session_id"].string })
        var out: [Earlier] = []
        for (sid, list) in Dictionary(grouping: readable, by: { $0["session_id"].string ?? "" }) {
            guard list.contains(where: { !freshIds.contains($0["path"].string ?? "") }) else { continue }
            let sorted = list.sorted { ($0["modified"].string ?? "") > ($1["modified"].string ?? "") }
            out.append(Earlier(id: sid, title: store.session(sid)?.title ?? "Session", at: sorted.first?["modified"].string ?? "", made: sorted))
        }
        for s in store.sessions where s.archived_at == nil && s.project_id != nil && s.project_id == researchId && s.status != "running" && !made.contains(s.id) {
            out.append(Earlier(id: s.id, title: s.title, at: s.updated_at ?? "", made: []))
        }
        return out.sorted { $0.at > $1.at }
    }

    private func start() {
        let q = ask.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, !starting else { return }
        starting = true
        Task {
            defer { starting = false }
            guard let pid = await researchProject(store.api), let s = try? await store.api.createSession(project: pid, task: nil, mode: .default) else { return }
            try? await store.api.send(s.id, text: q)
            ask = ""
            await store.refresh()
            model.go(s.id)
        }
    }
}

/// A research's title is the question as it was asked; only its first letter is raised.
func question(_ t: String) -> String { t.prefix(1).uppercased() + t.dropFirst() }

/// One research (the "Research in the rail" board): the question, what it made, and the way
/// back to its conversation.
struct ResearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    let sessionId: String
    @State private var reading: JSONValue?

    var body: some View {
        let s = store.session(sessionId)
        let made = Extras.shared.reports.filter { $0["session_id"].string == sessionId && $0["kind"].string != "file" && !($0["title"].string ?? "").hasPrefix("Bench presents") }
            .sorted { ($0["modified"].string ?? "") > ($1["modified"].string ?? "") }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Research · \(When.short(s?.created_at))").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
                Text(question(s?.title ?? "Research")).font(.system(size: 24, weight: .semibold)).padding(.top, 6).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Button("Open Conversation") { model.go(sessionId) }.buttonStyle(TonalButton(height: 30))
                    if s?.schedule_id == nil, let s, let pid = s.project_id {
                        Button("Keep It Updated") { keep(s.title, pid) }.buttonStyle(TonalButton(height: 30))
                    }
                }
                .padding(.top, 16)
                Text("What it made · \(made.count)").font(.system(size: 12.5, weight: .semibold)).foregroundStyle(theme.colors.muted).padding(.top, 26).padding(.bottom, 10)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 280), spacing: 12)], alignment: .leading, spacing: 12) {
                    ForEach(Array(made.enumerated()), id: \.offset) { _, r in
                        Button { reading = r } label: { MadeCard(report: r) }.buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 48).padding(.top, 40).padding(.bottom, 40)
            .frame(maxWidth: 980, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .modifier(KeepExtras(api: store.api))
        .sheet(item: Binding(get: { reading.map { ReportRef(v: $0) } }, set: { reading = $0?.v })) { ref in
            ReportSheet(store: store, report: ref.v) { reading = nil }
        }
    }

    /// Keep it updated: a schedule for the same question, every Monday at 8, made at once (it
    /// can be changed in Schedules).
    private func keep(_ title: String, _ pid: String) {
        Task {
            _ = try? await store.api.call("/api/schedules", method: "POST", body: ["title": title, "projectId": pid, "prompt": title, "days": "1", "time": "08:00", "tz": TimeZone.current.identifier, "keepBoard": true])
            await Extras.shared.load(store.api, force: true)
            model.go(Place.schedules.rawValue)
        }
    }
}

struct ReportRef: Identifiable { let v: JSONValue; var id: String { v["path"].string ?? "" } }

/// What a session made, as a card: kind and when, title, its first lines.
struct MadeCard: View {
    @Environment(\.theme) private var theme
    let report: JSONValue

    static func head(_ r: JSONValue) -> String {
        let what: String
        switch r["kind"].string {
        case "drafts": what = "\(Int(r["unsent"].number ?? 0)) drafts to send"
        case "page": what = "Page"
        case "board": what = "Board"
        default: what = "Report"
        }
        return what + " · " + When.short(r["modified"].string)
    }
    var body: some View {
        let kind = report["kind"].string
        VStack(alignment: .leading, spacing: 4) {
            Text(Self.head(report))
                .font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            Text(report["title"].string ?? "").font(.system(size: 15, weight: .semibold)).lineLimit(2).fixedSize(horizontal: false, vertical: true)
            if let g = report["gist"].string ?? report["preview"].array.first?["title"].string {
                Text(g).font(.system(size: 13)).foregroundStyle(theme.colors.muted).lineLimit(3).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: 14))
        .contentShape(Rectangle())
    }
}

/// A report read in place: Markdown here; a page opens in your browser (its script runs there);
/// drafts list who each is to, each opening in Mail.
struct ReportSheet: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let report: JSONValue
    let close: () -> Void
    @State private var text: String?
    @State private var data: Data?

    var body: some View {
        let kind = report["kind"].string
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(report["title"].string ?? "Report").font(.system(size: 15, weight: .semibold)).lineLimit(1)
                Spacer()
                if kind == "page", data != nil { Button("Open in Browser") { openPage() }.buttonStyle(TonalButton(height: 26)) }
                Button("Done", action: close).buttonStyle(TonalButton(height: 26)).keyboardShortcut(.cancelAction)
            }
            .padding(16)
            Divider()
            ScrollView {
                Group {
                    if kind == "drafts", let text, let v = try? JSONDecoder().decode(JSONValue.self, from: Data(text.utf8)) {
                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(Array(v["drafts"].array.enumerated()), id: \.offset) { _, d in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("To \(d["to"].string ?? "")").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                                    Text(d["subject"].string ?? "").font(.system(size: 14, weight: .semibold))
                                    Text(d["body"].string ?? "").font(.system(size: 13)).fixedSize(horizontal: false, vertical: true)
                                    Button(d["sent_at"].string == nil ? "Open in Mail" : "Sent · Open Again") { mail(d) }.buttonStyle(TonalButton(height: 26)).padding(.top, 4)
                                }
                                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                                .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: 12))
                            }
                        }
                    } else if kind == "page" {
                        Text(data == nil ? "Loading…" : "This report is a page. Open it in your browser to read it.").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                    } else if let text {
                        BenchMarkdown(text: text).frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        ProgressView().controlSize(.small)
                    }
                }
                .padding(20)
            }
        }
        .frame(width: 720, height: 620)
        .background(theme.colors.bg)
        .task { await load() }
    }

    private func load() async {
        let sid = report["session_id"].string ?? ""
        let path = report["path"].string ?? ""
        guard let d = try? await store.api.call("/api/sessions/\(BenchAPI.enc(sid))/files?path=\(BenchAPI.enc(path))") else { text = "This report couldn’t be read."; return }
        data = d
        text = String(data: d, encoding: .utf8)
        _ = try? await store.api.call("/api/sessions/\(BenchAPI.enc(sid))/seen", method: "POST", body: [:])
        await Extras.shared.load(store.api, force: true)
        if report["kind"].string == "page" { openPage() }
    }

    private func openPage() {
        guard let data else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bench-\(abs((report["path"].string ?? "").hashValue)).html")
        try? data.write(to: url)
        NSWorkspace.shared.open(url)
    }

    private func mail(_ d: JSONValue?) {
        var c = URLComponents()
        c.scheme = "mailto"
        c.path = d["to"].string ?? ""
        c.queryItems = [URLQueryItem(name: "subject", value: d["subject"].string ?? ""), URLQueryItem(name: "body", value: d["body"].string ?? "")]
        if let u = c.url { NSWorkspace.shared.open(u) }
    }
}

// MARK: Inbox: Coming up and Activity

/// Under the Inbox's sessions: the next runs and page watches, and what ran while you were away.
struct InboxExtras: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore

    var body: some View {
        let x = Extras.shared
        let timed = x.schedules.filter { $0["enabled"].bool && $0["next_run_at"].string != nil }.sorted { ($0["next_run_at"].string ?? "") < ($1["next_run_at"].string ?? "") }
        let watches = x.schedules.filter { $0["enabled"].bool && $0["watch"]?.object.isEmpty == false }
        let next = Array((timed + watches).prefix(3))
        VStack(alignment: .leading, spacing: 0) {
            ColumnLabel(text: "Coming up", trailing: "All") { model.go(Place.schedules.rawValue) }
            if next.isEmpty {
                ColumnRow(title: "Nothing scheduled", sub: "Schedules start sessions on their own") { model.go(Place.schedules.rawValue) }
            }
            ForEach(Array(next.enumerated()), id: \.offset) { _, s in
                let watch = s["watch"]?.object.isEmpty == false
                ColumnRow(title: s["title"].string ?? "Schedule", sub: watch ? "Watching \(page(s["watch"]?["url"].string))" : When.days(s["days"].string),
                          trailing: watch ? (s["checked_at"].string.map { "Checked \(Format.ago($0)) ago" } ?? "") : When.next(s["next_run_at"].string),
                          on: model.selection == Place.schedules.rawValue) { model.go(Place.schedules.rawValue) }
            }
            ColumnLabel(text: "While you were away")
            ColumnRow(title: "Activity", sub: x.activityUnseen > 0 ? "\(x.activityUnseen) run\(x.activityUnseen == 1 ? "" : "s") since you looked" : "What Bench did, newest first",
                      on: model.selection == Place.activity.rawValue) { model.go(Place.activity.rawValue) }
        }
        .modifier(KeepExtras(api: store.api))
    }
}

func page(_ url: String?) -> String {
    (url ?? "").replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "www.", with: "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
}

/// Schedules (the "Inbox: Coming up, Activity; Schedules" board): at a time, and when a page
/// changes, each on or off; opening one opens its latest run.
struct SchedulesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore

    var body: some View {
        let all = Extras.shared.schedules
        let timed = all.filter { $0["watch"]?.object.isEmpty != false }
        let watches = all.filter { $0["watch"]?.object.isEmpty == false }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Schedules").font(.system(size: 24, weight: .semibold))
                        Text("Sessions that start on their own").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
                    }
                    Spacer()
                    Button("New Schedule") { model.showNewSession = true }.buttonStyle(CapsuleButton(kind: .primary)).help("Ask for it in a new session: “every weekday at 9, …”")
                }
                if all.isEmpty {
                    Text("Nothing scheduled. Ask in any session for something to happen every morning, or when a page changes.")
                        .font(.system(size: 13)).foregroundStyle(theme.colors.muted).padding(.top, 24)
                }
                section("At a time", timed)
                section("When a page changes", watches)
            }
            .padding(.horizontal, 48).padding(.top, 40).padding(.bottom, 40)
            .frame(maxWidth: 860, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .modifier(KeepExtras(api: store.api))
    }

    static func line(_ s: JSONValue, watch: Bool) -> String {
        if watch {
            let every: Double = s["watch"]?["every_hours"].number ?? 6
            return page(s["watch"]?["url"].string) + " · every \(Int(every)) hours"
        }
        var parts: [String] = [When.days(s["days"].string)]
        if let t = s["time"].string { parts.append(t) }
        if s["keep_board"].bool { parts.append("keeps one board") }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder private func section(_ title: String, _ list: [JSONValue]) -> some View {
        if !list.isEmpty {
            Text(title).font(.system(size: 12.5, weight: .semibold)).foregroundStyle(theme.colors.muted).padding(.top, 26).padding(.bottom, 8)
            Group2 {
                ForEach(Array(list.enumerated()), id: \.offset) { i, s in
                    let watch = s["watch"]?.object.isEmpty == false
                    let sub = Self.line(s, watch: watch)
                    GroupRow(title: s["title"].string ?? "Schedule", sub: sub, first: i == 0) {
                        Text(watch ? (s["checked_at"].string.map { "Checked \(Format.ago($0)) ago" } ?? "First look soon") : When.next(s["next_run_at"].string))
                            .font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                        Toggle("", isOn: Binding(get: { s["enabled"].bool }, set: { on in
                            Task { _ = try? await store.api.call("/api/schedules/\(BenchAPI.enc(s["id"].string ?? ""))", method: "PATCH", body: ["enabled": on]); await Extras.shared.load(store.api, force: true) }
                        }))
                        .labelsHidden().toggleStyle(.switch).controlSize(.small)
                    }
                }
            }
        }
    }
}

/// Activity: what Bench did, newest first, by day; a run opens its session.
struct ActivityView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore

    var body: some View {
        let items = Extras.shared.activity
        let days = Dictionary(grouping: items, by: { When.date($0["at"].string).map { Calendar.current.startOfDay(for: $0) } ?? .distantPast })
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Activity").font(.system(size: 24, weight: .semibold))
                Text("What Bench did on its own and for you, newest first").font(.system(size: 13)).foregroundStyle(theme.colors.muted).padding(.top, 4)
                if items.isEmpty { Text("Nothing yet.").font(.system(size: 13)).foregroundStyle(theme.colors.muted).padding(.top, 24) }
                ForEach(days.keys.sorted(by: >), id: \.self) { day in
                    Text(Calendar.current.isDateInToday(day) ? "Today" : Calendar.current.isDateInYesterday(day) ? "Yesterday" : day.formatted(.dateTime.weekday(.wide).day().month(.wide)))
                        .font(.system(size: 12.5, weight: .semibold)).foregroundStyle(theme.colors.muted).padding(.top, 24).padding(.bottom, 8)
                    Group2 {
                        ForEach(Array((days[day] ?? []).enumerated()), id: \.offset) { i, a in
                            let sid = a["session_id"].string
                            Button { if let sid { model.go(sid) } } label: {
                                GroupRow(title: a["title"].string ?? "", sub: [a["detail"].string, a["by"].string].compactMap { $0 }.joined(separator: " · "), first: i == 0) {
                                    if let b = a["badge"].string { Text(b).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted) }
                                    if let c = a["cost"].number, c > 0 { Text(String(format: "$%.2f", c)).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted) }
                                    Text(When.date(a["at"].string)?.formatted(.dateTime.hour().minute()) ?? "").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(sid == nil)
                        }
                    }
                }
            }
            .padding(.horizontal, 48).padding(.top, 40).padding(.bottom, 40)
            .frame(maxWidth: 860, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .modifier(KeepExtras(api: store.api))
        .task { _ = try? await store.api.call("/api/activity/seen", method: "POST", body: [:]) }
    }
}

// MARK: The call bar

/// Above a session that is in a call (the "A session in a call" board): whether Bench hears it,
/// one-on-one, how freely it speaks, its voice, and Stop.
struct CallBar: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let sessionId: String
    @State private var m: JSONValue?

    private static let speech = [("ask", "Asks before each line"), ("some", "Small talk on its own"), ("allow", "Speaks freely")]

    var body: some View {
        Group {
            if let m, m["session_id"].string == sessionId {
                let trusted = m["trusted"].bool
                let voices = m["voices"].array
                HStack(spacing: 10) {
                    Circle().fill(m["audio_problem"].string == nil ? theme.colors.good : theme.colors.bad).frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(Self.state(m))
                            .font(.system(size: 13, weight: .semibold))
                        Text(Self.line(m))
                            .font(.system(size: 11.5)).foregroundStyle(m["audio_problem"].string == nil ? theme.colors.muted : theme.colors.bad).lineLimit(1)
                    }
                    Spacer(minLength: 12)
                    Toggle("One-on-one", isOn: Binding(get: { trusted }, set: { set(["trusted": $0]) })).toggleStyle(.button).controlSize(.small)
                    if !trusted {
                        Menu(Self.speechLabel(m)) {
                            ForEach(Self.speech, id: \.0) { k, v in Button(v) { set(["speech": k]) } }
                        }
                        .menuStyle(.borderlessButton).fixedSize()
                    }
                    if m["ready"]?["voice"].string == "kokoro", !voices.isEmpty {
                        let current = Self.voiceName(m, voices)
                        Menu("Voice · \(current)") {
                            ForEach(Array(voices.enumerated()), id: \.offset) { _, v in
                                Button("\(v["name"].string ?? "") · \(v["detail"].string ?? "")") { set(["voice": Int(v["id"].number ?? 3)]) }
                            }
                        }
                        .menuStyle(.borderlessButton).fixedSize()
                    }
                    Button("Stop") {
                        Task { if let d = try? await store.api.call("/api/meeting/stop", method: "POST", body: [:]) { m = try? JSONDecoder().decode(JSONValue.self, from: d) } }
                    }
                    .buttonStyle(TonalButton(height: 26))
                }
                .padding(.horizontal, 20).padding(.vertical, 10)
                .background(theme.colors.accent.opacity(0.08))
                .overlay(alignment: .bottom) { Rectangle().fill(theme.colors.accent.opacity(0.2)).frame(height: 1) }
            }
        }
        .task(id: sessionId) {
            while !Task.isCancelled {
                if let d = try? await store.api.call("/api/meeting"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { m = v }
                try? await Task.sleep(for: .seconds(m?["session_id"].string == sessionId ? 3 : 15))
            }
        }
    }

    private static func state(_ m: JSONValue) -> String {
        let trusted = m["trusted"].bool
        if m["presenting"].bool { return trusted ? "One-on-one · presenting" : "In a call · presenting" }
        if trusted { return "One-on-one call" }
        let listening: Bool = m["ready"]?["listen"].bool ?? false
        return listening ? "In a call · listening" : "In a call · can’t hear it"
    }

    private static func line(_ m: JSONValue) -> String {
        if let p = m["audio_problem"].string { return "Its voice isn’t reaching the call: " + p }
        if let since = m["since"].string { return "For " + Format.ago(since) }
        return ""
    }

    private static func voiceName(_ m: JSONValue, _ voices: [JSONValue]) -> String {
        let id: Double = m["voice"].number ?? -1
        for v in voices where v["id"].number == id { return v["name"].string ?? "Voice" }
        return "Voice"
    }

    private static func speechLabel(_ m: JSONValue) -> String {
        let current: String = m["speech"].string ?? "ask"
        for (k, v) in speech where k == current { return v }
        return "Speech"
    }

    private func set(_ body: [String: Any]) {
        Task { if let d = try? await store.api.call("/api/meeting", method: "PUT", body: body) { m = try? JSONDecoder().decode(JSONValue.self, from: d) } }
    }
}

// MARK: Settings: Spending, Rules, About you, Housekeeping

struct SpendingPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var s: JSONValue?
    @State private var day = ""
    @State private var run = ""

    var body: some View {
        Text("Caps for runs nobody is watching: schedules, page watches, Then steps.").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
        if let s {
            VStack(alignment: .leading, spacing: 4) {
                Text("Today").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
                Text(String(format: "$%.2f", s["today"].number ?? 0)).font(.system(size: 26, weight: .semibold))
                Text(String(format: "of $%.2f a day · %d run%@", s["daily_cap"].number ?? 0, Int(s["runs_today"].number ?? 0), Int(s["runs_today"].number ?? 0) == 1 ? "" : "s")).font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            }
            .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: theme.radius))
            VStack(alignment: .leading, spacing: 6) {
                GroupLabel("Caps")
                Group2 {
                    GroupRow(title: "A day", sub: "Unattended runs stop for the day at this", first: true) { money($day) { save(["daily_cap": $0]) } }
                    GroupRow(title: "A run", sub: "One run stops here and waits for you") { money($run) { save(["run_cap": $0]) } }
                    GroupRow(title: "Pause unattended runs", sub: "Nothing starts on its own until you resume") {
                        Toggle("", isOn: Binding(get: { s["paused"].bool }, set: { save(["paused": $0]) })).labelsHidden().toggleStyle(.switch).controlSize(.small)
                    }
                }
            }
        }
        Color.clear.frame(height: 0).task { await load() }
    }

    private func money(_ text: Binding<String>, _ commit: @escaping (Double) -> Void) -> some View {
        HStack(spacing: 2) {
            Text("$").foregroundStyle(theme.colors.muted)
            TextField("", text: text).textFieldStyle(.plain).multilineTextAlignment(.trailing).frame(width: 60)
                .onSubmit { if let v = Double(text.wrappedValue), v >= 0 { commit(v) } }
        }
        .font(.system(size: 13))
        .padding(.horizontal, 8).frame(height: 26)
        .background(theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 7))
    }

    private func load() async {
        if let d = try? await store.api.call("/api/spending"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { take(v) }
    }

    private func take(_ v: JSONValue) {
        s = v
        day = String(format: "%.2f", v["daily_cap"].number ?? 0)
        run = String(format: "%.2f", v["run_cap"].number ?? 0)
    }

    private func save(_ body: [String: Any]) {
        Task { if let d = try? await store.api.call("/api/spending", method: "PUT", body: body), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { take(v) } }
    }
}

struct RulesPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var rules: [JSONValue] = []

    var body: some View {
        Text("What Bench may do without asking, what it asks about first, and what it never does.").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
        Group2 {
            ForEach(Array(rules.enumerated()), id: \.offset) { i, r in
                GroupRow(title: r["label"].string ?? r["key"].string ?? "", first: i == 0) {
                    Picker("", selection: Binding(get: { r["value"].string ?? "ask" }, set: { v in save(r["key"].string ?? "", v) })) {
                        Text("Allowed").tag("allow")
                        Text("Asks first").tag("ask")
                        Text("Never").tag("never")
                    }
                    .labelsHidden().fixedSize()
                }
            }
            if rules.isEmpty { GroupRow(title: "Loading…", first: true, muted: true) { EmptyView() } }
        }
        Color.clear.frame(height: 0).task { await load() }
    }

    private func load() async {
        if let d = try? await store.api.call("/api/rules"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { rules = v.array }
    }

    private func save(_ key: String, _ value: String) {
        Task {
            if let d = try? await store.api.call("/api/rules", method: "PUT", body: ["key": key, "value": value]), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { rules = v.array }
        }
    }
}

struct AboutPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var text = ""
    @State private var include = false
    @State private var saved = ""
    @State private var loaded = false

    var body: some View {
        Text("Who you are and how you work, read by every session. Write it the way you’d brief someone new.").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
        TextEditor(text: $text)
            .font(.system(size: 13))
            .scrollContentBackground(.hidden)
            .padding(10)
            .frame(minHeight: 280)
            .background(theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: theme.radius))
        HStack {
            Toggle("Every session reads it", isOn: Binding(get: { include }, set: { include = $0; save(["include": $0]) })).toggleStyle(.switch).controlSize(.small)
            Spacer()
            Button("Save") { save(["text": text]) }.buttonStyle(CapsuleButton(kind: .primary)).disabled(!loaded || text == saved)
        }
        Color.clear.frame(height: 0).task { await load() }
    }

    private func take(_ v: JSONValue) {
        text = v["text"].string ?? ""
        saved = text
        include = v["include"].bool
        loaded = true
    }

    private func load() async {
        if let d = try? await store.api.call("/api/about"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { take(v) }
    }

    private func save(_ body: [String: Any]) {
        Task { if let d = try? await store.api.call("/api/about", method: "PUT", body: body), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { take(v) } }
    }
}

struct HousekeepingPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var h: JSONValue?
    @State private var archive: JSONValue?

    var body: some View {
        Text("Bench does these on its own and says what it did in Activity. What it archives can come back.").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
        if let h {
            Group2 {
                row("Archive conversations you have not opened in 30 days", "Pinned ones stay", "archive_idle", h, first: true)
                row("Remove branches once merged", "Their work stays in main", "prune_branches", h)
                row("Free disk from old work folders", "Folders of tasks finished a week ago", "free_disk", h)
                row("Keep one board per schedule", "Instead of a new report every run", "keep_board", h)
            }
        }
        if let archive {
            let items = archive["sessions"].array.map { ("session", $0) } + archive["schedules"].array.map { ("schedule", $0) }
            VStack(alignment: .leading, spacing: 6) {
                GroupLabel("Archived · \(items.count)")
                Group2 {
                    if items.isEmpty { GroupRow(title: "Nothing archived", first: true, muted: true) { EmptyView() } }
                    ForEach(Array(items.prefix(50).enumerated()), id: \.offset) { i, x in
                        GroupRow(title: x.1["title"].string ?? "", sub: (x.0 == "schedule" ? "Schedule" : "Conversation") + (x.1["archived_at"].string.map { " · archived \(Format.ago($0)) ago" } ?? ""), first: i == 0) {
                            Button("Restore") {
                                Task { _ = try? await store.api.call("/api/archive/restore", method: "POST", body: ["kind": x.0, "id": x.1["id"].string ?? ""]); await load(); await store.refresh() }
                            }
                            .buttonStyle(TonalButton(height: 24))
                        }
                    }
                }
            }
        }
        Color.clear.frame(height: 0).task { await load() }
    }

    private func row(_ title: String, _ sub: String, _ key: String, _ h: JSONValue, first: Bool = false) -> some View {
        GroupRow(title: title, sub: sub, first: first) {
            Toggle("", isOn: Binding(get: { h[key].bool }, set: { on in
                Task { if let d = try? await store.api.call("/api/housekeeping", method: "PUT", body: [key: on]), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { self.h = v } }
            }))
            .labelsHidden().toggleStyle(.switch).controlSize(.small)
        }
    }

    private func load() async {
        if let d = try? await store.api.call("/api/housekeeping"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { h = v }
        if let d = try? await store.api.call("/api/archive"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { archive = v }
    }
}
