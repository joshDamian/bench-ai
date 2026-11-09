import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Images and files from the clipboard or a drop, as files the composer can upload.
enum PastedImage {
    /// Writes image data to a temporary PNG named for when it was pasted.
    static func write(_ data: Data) -> URL? {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd 'at' HH.mm.ss"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Pasted \(f.string(from: Date())).png")
        let png: Data? = NSBitmapImageRep(data: data)?.representation(using: .png, properties: [:]) ?? data
        do { try png?.write(to: url); return url } catch { return nil }
    }

    /// Copied files first; else an image, but only when there's no text (so pasting text
    /// copied from a web page with pictures still pastes the text).
    static func take(from pb: NSPasteboard) -> [URL] {
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty { return urls }
        guard pb.string(forType: .string) == nil else { return [] }
        for t in [NSPasteboard.PasteboardType.png, .tiff] {
            if let d = pb.data(forType: t), let url = write(d) { return [url] }
        }
        return []
    }
}

/// A placeholder bar that breathes while the real content loads.
struct SkeletonBar: View {
    @Environment(\.theme) private var theme
    var width: CGFloat? = nil
    var height: CGFloat = 12
    @State private var on = false

    var body: some View {
        RoundedRectangle(cornerRadius: height / 2)
            .fill(theme.colors.fill(on ? 0.11 : 0.05))
            .frame(width: width, height: height)
            .frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
            .onAppear { withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { on = true } }
            .accessibilityHidden(true)
    }
}

/// The shape of a conversation before it arrives.
struct TranscriptSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack { Spacer(); SkeletonBar(width: 280, height: 38) }
            SkeletonBar(width: 180, height: 22)
            SkeletonBar()
            SkeletonBar()
            SkeletonBar(width: 360)
        }
        .padding(.vertical, Space.xl)
        .accessibilityElement()
        .accessibilityLabel("Loading conversation")
    }
}

/// Rows of a list before it arrives.
struct ListSkeleton: View {
    var rows = 5
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(0..<rows, id: \.self) { i in
                HStack(spacing: 10) {
                    SkeletonBar(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 6) {
                        SkeletonBar(width: i % 2 == 0 ? 220 : 170)
                        SkeletonBar(width: 110, height: 9)
                    }
                }
            }
        }
        .padding(Space.lg)
        .accessibilityElement()
        .accessibilityLabel("Loading")
    }
}
