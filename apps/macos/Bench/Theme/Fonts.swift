import AppKit
import CoreText
import SwiftUI

/// Bench's faces, bundled so they are the same on every Mac:
/// - the interface (sidebar, headers, buttons, menus) stays the system's San Francisco,
///   so Bench sits with macOS;
/// - reading (Claude's replies and your messages) is Inter, drawn for long text on screens;
/// - machine values (paths, hashes, commands, code, the terminal) are JetBrains Mono.
/// Both are SIL Open Font License; their licences are beside the files in Fonts/.
enum BenchFont {
    static let reading = "Inter"
    static let mono = "JetBrains Mono"

    /// Registers the bundled .ttf files for this process. Called once at launch.
    static func register() {
        let urls = Bundle.main.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? []
        if !urls.isEmpty { CTFontManagerRegisterFontURLs(urls as CFArray, .process, true, nil) }
    }

    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .custom(mono, size: size).weight(weight)
    }

    static func read(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .custom(reading, size: size).weight(weight)
    }

    /// The terminal's face, for SwiftTerm (an AppKit view).
    static func monoNS(_ size: CGFloat) -> NSFont {
        NSFont(name: "JetBrainsMono-Regular", size: size) ?? .monospacedSystemFont(ofSize: size, weight: .regular)
    }
}
