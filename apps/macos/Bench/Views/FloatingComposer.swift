import SwiftUI

/// The composer in full view: a small window over the page, dragged anywhere by its handle.
/// It collapses to a pill (⌄) or hides altogether (✕); ⌘L always brings it back, and it
/// opens where and how it was last left.
struct FloatingComposer<Content: View>: View {
    @Environment(\.theme) private var theme
    let files: SessionFiles
    let lastReply: String?
    @ViewBuilder let content: Content
    let onOpen: () -> Void

    /// Where it sits, as an offset from the bottom-right corner, kept between uses.
    @AppStorage("fullComposerX") private var x: Double = -32
    @AppStorage("fullComposerY") private var y: Double = -32
    @State private var drag: CGSize = .zero
    @State private var pillHover = false

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottomTrailing) {
                Color.clear
                switch files.composer {
                case "open": window.offset(position(in: geo.size)).transition(.scale(scale: 0.96, anchor: .bottomTrailing).combined(with: .opacity))
                case "pill": pill.offset(position(in: geo.size)).transition(.opacity)
                default: EmptyView()
                }
            }
        }
        .animation(.snappy(duration: 0.2), value: files.composer)
        .background {
            Button("") { toggle() }.keyboardShortcut("l", modifiers: .command).hidden()
        }
    }

    /// Kept on screen: a window resized smaller never loses the composer off its edge.
    private func position(in size: CGSize) -> CGSize {
        let w = (x + drag.width), h = (y + drag.height)
        return CGSize(width: min(max(w, -(size.width - 120)), 0), height: min(max(h, -(size.height - 60)), 0))
    }

    private func toggle() {
        if files.composer == "open" { files.composer = "pill" } else { files.composer = "open"; onOpen() }
    }

    private var dragging: some Gesture {
        DragGesture(coordinateSpace: .global)
            .onChanged { drag = $0.translation }
            .onEnded { v in x += v.translation.width; y += v.translation.height; drag = .zero }
    }

    private var window: some View {
        VStack(spacing: 0) {
            ZStack {
                Capsule().fill(theme.colors.fill(0.25)).frame(width: 36, height: 4)
                HStack(spacing: 2) {
                    Spacer()
                    small("chevron.down", "Collapse (⌘L)") { files.composer = "pill" }
                    small("xmark", "Hide") { files.composer = "hidden" }
                }
                .padding(.trailing, 6)
            }
            .frame(height: 24)
            .contentShape(Rectangle())
            .gesture(dragging)
            .onHover { if $0 { NSCursor.openHand.push() } else { NSCursor.pop() } }
            if let lastReply {
                HStack(spacing: 6) {
                    Circle().fill(theme.colors.good).frame(width: 6, height: 6)
                    Text("Claude: \(lastReply)").lineLimit(1).truncationMode(.tail)
                }
                .font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                .padding(.horizontal, 14).padding(.bottom, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            content
        }
        .frame(width: 460)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .background(theme.colors.surface2.opacity(0.7), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(theme.colors.fill(0.14)))
        .shadow(color: .black.opacity(0.35), radius: 24, y: 12)
    }

    private var pill: some View {
        HStack(spacing: 8) {
            Circle().fill(theme.colors.accent).frame(width: 8, height: 8)
            Text("Reply to Claude").font(.system(size: 13))
            Text("⌘L").font(BenchFont.mono(11)).foregroundStyle(theme.colors.muted)
            if pillHover {
                Button { files.composer = "hidden" } label: {
                    Image(systemName: "xmark").font(.system(size: 8, weight: .bold)).frame(width: 16, height: 16)
                        .background(theme.colors.fill(0.12), in: Circle())
                }
                .buttonStyle(.plain).foregroundStyle(theme.colors.muted).help("Hide")
            }
        }
        .padding(.leading, 12).padding(.trailing, pillHover ? 8 : 14).frame(height: 38)
        .background(.regularMaterial, in: Capsule())
        .background(theme.colors.surface2.opacity(0.7), in: Capsule())
        .overlay(Capsule().strokeBorder(theme.colors.fill(0.14)))
        .shadow(color: .black.opacity(0.3), radius: 14, y: 6)
        .contentShape(Capsule())
        .onTapGesture { files.composer = "open"; onOpen() }
        .gesture(dragging)
        .onHover { h in withAnimation(.snappy(duration: 0.15)) { pillHover = h } }
        .help("Reply to Claude (⌘L) · drag to move")
    }

    private func small(_ name: String, _ help: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.system(size: 10, weight: .semibold)).frame(width: 22, height: 22).contentShape(Rectangle())
        }
        .buttonStyle(.plain).foregroundStyle(theme.colors.muted).help(help).accessibilityLabel(help)
    }
}

/// In the viewer's bar while the full-view composer is hidden: the way back to it.
struct ReplyButton: View {
    let files: SessionFiles
    var body: some View {
        Button { files.composer = "open" } label: {
            HStack(spacing: 6) { Text("Reply"); Text("⌘L").font(BenchFont.mono(10.5)).opacity(0.8) }
        }
        .buttonStyle(PrimaryButton(height: 26))
        .help("Show the composer (⌘L)")
    }
}
