import SwiftUI

/// Can reach (the Mac "Can reach" board): everything Bench on the server can use, and where.
/// Your devices first (this Mac opens Shared with Bench; a phone opens what it shares), with
/// requests waiting on them; then the server's browser, accounts, and what the server has.
struct CanReachPage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let openShared: () -> Void
    @State private var reach: JSONValue?
    @State private var linked: [JSONValue] = []
    @State private var device: String?
    @State private var failed = false

    /// This Mac, as the server knows it: the id MacLink links with.
    private var thisMac: String? { UserDefaults.standard.string(forKey: "deviceID") }

    var body: some View {
        Group {
            if let id = device, let d = linked.first(where: { $0["id"].string == id }) {
                DevicePage(store: store, device: d, back: { device = nil }) { await load() }
            } else {
                overview
            }
        }
        .task { await load() }
    }

    @ViewBuilder private var overview: some View {
        Text("Everything Bench on your server can use, and where.")
            .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
        if failed {
            Text("Can reach needs the Go server. Update your server to see it.").font(.system(size: 13)).foregroundStyle(theme.colors.muted)
        }
        let others = linked.filter { $0["id"].string != thisMac }
        let asks = others.flatMap { d in d["asks"].array.map { (d, $0) } }
        VStack(alignment: .leading, spacing: 6) {
            GroupLabel("Your devices")
            Group2 {
                ReachRow(icon: "laptopcomputer", title: "This Mac", sub: macLine, trailing: "Shared with Bench", first: true, action: openShared)
                ForEach(Array(others.enumerated()), id: \.offset) { _, d in
                    ReachRow(icon: d["kind"].string == "phone" ? "iphone" : "laptopcomputer", title: d["name"].string ?? "Device", sub: deviceLine(d)) { device = d["id"].string }
                }
                ForEach(Array(asks.enumerated()), id: \.offset) { _, x in
                    HStack(spacing: 12) {
                        Circle().fill(theme.colors.warn).frame(width: 8, height: 8).frame(width: 30)
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Waiting on \(x.0["name"].string ?? "the device"): \(askWhat(x.1))").font(.system(size: 13))
                            Text("Asked \(x.1["created_at"].string.map { Format.ago($0) + " ago" } ?? "") · you decide on the device").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                        }
                        Spacer()
                        Button("Cancel") {
                            Task { _ = try? await store.api.call("/api/linked-devices/asks/\(BenchAPI.enc(x.1["id"].string ?? ""))", method: "DELETE"); await load() }
                        }
                        .buttonStyle(TonalButton(height: 26))
                    }
                    .padding(.horizontal, 12).padding(.vertical, 9)
                    .overlay(alignment: .top) { Divider().padding(.leading, 54) }
                }
            }
        }
        if let r = reach {
            let b = r["browser"]
            VStack(alignment: .leading, spacing: 6) {
                GroupLabel("Browser")
                Group2 {
                    let sites = b["sites"].array.compactMap(\.string)
                    ReachRow(icon: "globe", title: "Bench’s browser",
                             sub: !b["installed"].bool ? "Not installed on the server" : sites.isEmpty ? "No sign-ins yet" : "Signed in to " + sites.prefix(4).joined(separator: ", ") + (sites.count > 4 ? " and \(sites.count - 4) more" : ""),
                             trailing: b["installed"].bool ? "Open" : nil, first: true) { if b["installed"].bool { BrowserWindowController.shared.show(store: store) } }
                }
            }
            items("Accounts", r["accounts"].array, icon: "person.crop.circle")
            items("On the server", r["server"].array, icon: "server.rack")
        }
    }

    @ViewBuilder private func items(_ title: String, _ list: [JSONValue], icon: String) -> some View {
        if !list.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                GroupLabel(title)
                Group2 {
                    ForEach(Array(list.enumerated()), id: \.offset) { i, x in
                        ReachRow(icon: icon, title: x["name"].string ?? "", sub: x["detail"].string, first: i == 0, action: nil)
                    }
                }
            }
        }
    }

    private var macLine: String {
        let n = store.link.shares.count
        return n == 0 ? "Shares nothing yet" : "Shares \(n) thing\(n == 1 ? "" : "s")"
    }

    private func deviceLine(_ d: JSONValue) -> String {
        let shares = d["shares"].array.compactMap { $0["name"].string }
        let state = d["online"].bool ? "Online" : "Offline"
        if shares.isEmpty { return "\(state) · shares nothing" }
        let named = shares.prefix(3).joined(separator: ", ") + (shares.count > 3 ? " and \(shares.count - 3) more" : "")
        return "\(state) · shares \(shares.count) thing\(shares.count == 1 ? "" : "s") · \(named)"
    }

    private func load() async {
        if let d = try? await store.api.call("/api/reach"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { reach = v; failed = false } else { failed = true }
        if let d = try? await store.api.call("/api/linked-devices"), let v = try? JSONDecoder().decode(JSONValue.self, from: d) { linked = v.array }
    }
}

/// What an ask is for, in a few words: "use WhatsApp", "the calendar".
func askWhat(_ a: JSONValue?) -> String {
    let path = a["path"].string ?? ""
    switch a["kind"].string {
    case "use": return "use \(path)"
    case "messages": return "\(path) messages"
    case "calendar": return a["access"].string == "write" ? "add to the calendar" : "see the calendar"
    case "contacts": return "contacts"
    case "sms": return "send texts"
    case "handover": return "prepare things in apps"
    case "notifications": return "read notifications"
    case "screen": return "see the screen"
    case "folder": return path
    default: return path.isEmpty ? "something more" : path
    }
}

/// One row of Can reach: an icon, a title and its line, an optional value, and a chevron when it opens.
struct ReachRow: View {
    @Environment(\.theme) private var theme
    let icon: String
    let title: String
    var sub: String? = nil
    var trailing: String? = nil
    var first = false
    var action: (() -> Void)?

    var body: some View {
        Button { action?() } label: {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 13)).frame(width: 30, height: 30)
                    .background(theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 7))
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 13))
                    if let sub, !sub.isEmpty { Text(sub).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1) }
                }
                Spacer(minLength: 12)
                if let trailing { Text(trailing).font(.system(size: 12.5)).foregroundStyle(theme.colors.muted) }
                if action != nil { Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.colors.muted.opacity(0.7)) }
            }
            .foregroundStyle(theme.colors.text)
            .padding(.horizontal, 12).padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(action == nil)
        .overlay(alignment: .top) { if !first { Divider().padding(.leading, 54) } }
    }
}

/// A linked device from Can reach (the "Your phone, from Can reach" board): what it shares and
/// each rule, set on the device itself; requests waiting on it; and forgetting it.
struct DevicePage: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let device: JSONValue
    let back: () -> Void
    let reload: () async -> Void
    @State private var forgetting = false

    var body: some View {
        let phone = device["kind"].string == "phone"
        Button(action: back) {
            HStack(spacing: 6) { Image(systemName: "chevron.left").font(.system(size: 11, weight: .semibold)); Text("Can reach") }
                .font(.system(size: 13)).foregroundStyle(theme.colors.muted)
        }
        .buttonStyle(.plain)
        VStack(alignment: .leading, spacing: 6) {
            Text(device["name"].string ?? "Device").font(.system(size: 20, weight: .semibold))
            HStack(spacing: 7) {
                Circle().fill(device["online"].bool ? theme.colors.good : theme.colors.muted).frame(width: 7, height: 7)
                Text([device["online"].bool ? "Online" : "Offline", phone ? "Bench for Android" : "Bench for Mac", device["version"].string].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
            }
        }
        let shares = device["shares"].array
        VStack(alignment: .leading, spacing: 6) {
            GroupLabel("What it shares · changed on the \(phone ? "phone" : "device")")
            Group2 {
                if shares.isEmpty { GroupRow(title: "Nothing yet", first: true, muted: true) { EmptyView() } }
                ForEach(Array(shares.enumerated()), id: \.offset) { i, s in
                    GroupRow(title: s["name"].string ?? "", sub: shareLine(s), first: i == 0) {
                        Text(s["rule"].string == "allow" ? "Allowed" : "Asks first").font(.system(size: 12.5)).foregroundStyle(theme.colors.muted)
                    }
                }
            }
        }
        let asks = device["asks"].array
        if !asks.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                GroupLabel("Waiting on it")
                Group2 {
                    ForEach(Array(asks.enumerated()), id: \.offset) { i, a in
                        GroupRow(title: askWhat(a), sub: a["why"].string, first: i == 0) {
                            Button("Cancel") {
                                Task { _ = try? await store.api.call("/api/linked-devices/asks/\(BenchAPI.enc(a["id"].string ?? ""))", method: "DELETE"); await reload() }
                            }
                            .buttonStyle(TonalButton(height: 26))
                        }
                    }
                }
            }
        }
        Text(phone ? "Bench asks the phone before it pays, books, sends or deletes. To share something else, ask from a session or change it on the phone." : "To share something else, ask from a session or change it on that Mac.")
            .font(.system(size: 12)).foregroundStyle(theme.colors.muted).fixedSize(horizontal: false, vertical: true)
        Button(phone ? "Forget This Phone…" : "Forget This Device…") { forgetting = true }
            .buttonStyle(TonalButton(danger: true, height: 28))
            .confirmationDialog("Forget \(device["name"].string ?? "this device")?", isPresented: $forgetting) {
                Button("Forget", role: .destructive) {
                    Task { _ = try? await store.api.call("/api/linked-devices/\(BenchAPI.enc(device["id"].string ?? ""))", method: "DELETE"); back(); await reload() }
                }
            } message: { Text("Bench stops using what it shares. It can link again from the device.") }
    }

    private func shareLine(_ s: JSONValue) -> String {
        switch s["kind"].string {
        case "use": return "Uses it for you, on a hidden screen"
        case "messages": return s["access"].string == "reply" ? "Read new messages and reply from the notification" : "Read new messages"
        case "calendar": return s["access"].string == "write" ? "See and add events" : "See events"
        case "contacts": return "Look up a name or number"
        case "sms": return "Send an SMS"
        case "handover": return "Prepare things in apps for you to finish"
        case "notifications": return "Read notifications of the apps it may use"
        case "folder": return s["access"].string == "write" ? "Read and write" : "Read only"
        case "screen": return "Look only: screenshots"
        case "app": return "Open it and use it"
        default: return "Runs \(s["path"].string ?? "")"
        }
    }
}
