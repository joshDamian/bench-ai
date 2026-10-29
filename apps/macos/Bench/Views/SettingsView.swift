import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @AppStorage("theme") private var themeId = "classic"
    @State private var confirmUnpair = false

    var body: some View {
        Form {
            Section("Server") {
                if let store = model.store {
                    LabeledContent("Address", value: store.api.config.baseURL)
                    LabeledContent("Status", value: store.connected ? "Connected" : "Not connected")
                    if let v = store.version { LabeledContent("Bench", value: v) }
                    if let d = store.me?.device?.name { LabeledContent("Paired as", value: d) }
                    else if store.api.config.isLoopback { LabeledContent("Access", value: "This Mac (no pairing needed)") }
                    Button(store.api.config.isLoopback ? "Disconnect" : "Unpair This Mac…", role: .destructive) { confirmUnpair = true }
                } else {
                    Text("Not connected").foregroundStyle(theme.colors.muted)
                }
            }
            Section("Theme") {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 84))], spacing: Space.md) {
                    ForEach(benchThemes) { t in
                        Button { themeId = t.id } label: {
                            VStack(spacing: Space.xs) {
                                ZStack {
                                    Circle().fill(t.colors.bg)
                                    Circle().fill(t.colors.accent).frame(width: 14, height: 14)
                                }
                                .frame(width: 40, height: 40)
                                .overlay(Circle().strokeBorder(themeId == t.id ? theme.colors.accent : theme.colors.line, lineWidth: themeId == t.id ? 3 : 1))
                                Text(t.name).font(.system(size: TypeScale.xs))
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, Space.xs)
            }
            Section {
                Text("Bench for Mac \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")")
                    .foregroundStyle(theme.colors.muted)
            }
        }
        .formStyle(.grouped)
        .confirmationDialog("Forget this server?", isPresented: $confirmUnpair) {
            Button("Forget", role: .destructive) { model.unpair() }
        } message: {
            Text("To revoke this Mac on the server as well, use Settings → Devices in Bench, or `bench revoke`.")
        }
    }
}
