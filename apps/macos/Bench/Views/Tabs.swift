import AppKit
import SwiftUI

// Tabs and the split in the main window. A tab holds whatever the sidebar can show; the
// sidebar changes the tab you're in, ⌘T or "Open in New Tab" adds one.

extension AppModel {
    /// Keeps the tabs in step with the selection: the tab you're in now shows it.
    func follow(_ old: String?, _ new: String?) {
        guard let new else { return }
        if tabs.contains(new) { return }
        if let old, let i = tabs.firstIndex(of: old) { tabs[i] = new } else { tabs.append(new) }
    }

    func openTab(_ id: String? = nil) {
        let target = id ?? Place.all.rawValue
        if tabs.contains(target) { selection = target; return }
        let at = (selection.flatMap { tabs.firstIndex(of: $0) } ?? tabs.count - 1) + 1
        tabs.insert(target, at: min(max(at, 0), tabs.count))
        selection = target
    }

    func closeTab(_ id: String? = nil) {
        guard let id = id ?? selection, let i = tabs.firstIndex(of: id) else { return }
        if tabs.count <= 1 { NSApp.keyWindow?.performClose(nil); return }
        tabs.remove(at: i)
        if selection == id { selection = tabs[min(i, tabs.count - 1)] }
        if split == id { split = nil }
    }

    func cycleTab(_ step: Int) {
        guard tabs.count > 1, let sel = selection, let i = tabs.firstIndex(of: sel) else { return }
        selection = tabs[(i + step + tabs.count) % tabs.count]
    }

    func openToSide(_ id: String) {
        guard id != selection else { return }
        split = id
    }

    /// Whether the key window is the main one, so ⌘W and tab keys act on its tabs only.
    var mainIsKey: Bool {
        guard let id = NSApp.keyWindow?.identifier?.rawValue else { return false }
        return id.hasPrefix("main") || !(id.hasPrefix("session") || id.hasPrefix("dashboard") || id.localizedCaseInsensitiveContains("settings"))
    }
}

/// The tab strip under the window's top edge, shown when there are two or more tabs.
struct TabStrip: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    /// The sidebar is hidden: the traffic lights and the show-sidebar button lead the strip.
    var lights = false

    var body: some View {
        HStack(spacing: 4) {
            if lights {
                TrafficLights().padding(.leading, 6)
                Button { withAnimation(.snappy) { model.showSidebar = true } } label: {
                    Image(systemName: "sidebar.left").font(.system(size: 13)).frame(width: 28, height: 26).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(theme.colors.muted)
                .help("Show Sidebar (⌃⌘S)")
                .padding(.leading, 8).padding(.trailing, 6)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(model.tabs, id: \.self) { id in TabChip(store: store, id: id) }
                }
                .padding(.vertical, 1)
            }
            Button { model.openTab() } label: {
                Image(systemName: "plus").font(.system(size: 11, weight: .medium)).frame(width: 26, height: 26).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(theme.colors.muted)
            .help("New Tab (⌘T)")
        }
        .padding(.leading, lights ? 10 : 12).padding(.trailing, 10)
        // The sidebar card is inset 8pt and its top row is 46pt tall: the same here puts the
        // tabs on the traffic lights' line.
        .frame(height: 46)
        .padding(.top, 8)
        // Without the sidebar, the strip is the window's top edge: drag it to move the window.
        .background(WindowDragArea())
        .background(WindowDragArea())
        .overlay(alignment: .bottom) { Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1).padding(.leading, lights ? 0 : -8) }
    }
}

private struct TabChip: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let store: BenchStore
    let id: String
    @State private var hover = false

    var body: some View {
        let on = model.selection == id
        let (title, place, mark) = describe()
        HStack(spacing: 7) {
            MarkView(mark: mark)
            Text(title).font(.system(size: 12, weight: on ? .medium : .regular)).lineLimit(1)
            if !place.isEmpty {
                Text(place).font(.system(size: 11)).foregroundStyle(theme.colors.muted).lineLimit(1).fixedSize()
            }
            Button { model.closeTab(id) } label: {
                Image(systemName: "xmark").font(.system(size: 8, weight: .bold)).frame(width: 16, height: 16)
                    .background(theme.colors.fill(hover ? 0.1 : 0), in: Circle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(theme.colors.muted)
            .opacity(on || hover ? 1 : 0)
            .help("Close Tab (⌘W)")
            .accessibilityLabel("Close \(title)")
        }
        .padding(.leading, 10).padding(.trailing, 5)
        .frame(maxWidth: 240, minHeight: 28, alignment: .leading)
        .foregroundStyle(on ? theme.colors.text : theme.colors.muted)
        .background(on ? theme.colors.fill(0.09) : (hover ? theme.colors.fill(0.04) : .clear), in: RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
        .onTapGesture { model.selection = id }
        .onHover { hover = $0 }
        .contextMenu {
            if store.session(id) != nil {
                Button("Open to the Side") { model.openToSide(id) }.disabled(model.selection == id)
            }
            Button("Close Tab") { model.closeTab(id) }
            Button("Close Other Tabs") { model.tabs = [id]; model.selection = id }.disabled(model.tabs.count < 2)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
    }

    /// The tab's title, where it is, and its state mark.
    private func describe() -> (String, String, Mark) {
        if let p = Place(rawValue: id) { return (p.title, "", .idle) }
        if id.hasPrefix("project:") { return (store.project(String(id.dropFirst(8)))?.name ?? "Project", "", .idle) }
        if id.hasPrefix("task:") {
            let t = store.task(String(id.dropFirst(5)))
            return (t?.title ?? "Task", store.project(t?.project_id)?.name ?? "", t.map { Mark.of(task: $0, store: store) } ?? .idle)
        }
        let s = store.session(id)
        return (s?.title ?? "Session", store.project(s?.project_id)?.name ?? "Ask Bench", Mark.of(s, store: store))
    }
}

/// The session open beside the main one; its header carries the close button.
struct SplitPane: View {
    @Environment(AppModel.self) private var model
    let store: BenchStore
    let id: String

    var body: some View {
        SessionView(store: store, sessionId: id, showsInspector: false, onClose: { model.split = nil })
            .id("split-\(id)")
    }
}
