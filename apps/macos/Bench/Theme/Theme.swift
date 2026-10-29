import SwiftUI

// Bench's tokens, as SwiftUI reads them. Views take colours from `theme.colors` and sizes
// from Space and TypeScale, never from a literal - CLAUDE.md's rule for the web components.

private struct ThemeKey: EnvironmentKey {
    static let defaultValue: BenchThemeSpec = benchThemes[0]
}

extension EnvironmentValues {
    var theme: BenchThemeSpec {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

func themeById(_ id: String) -> BenchThemeSpec { benchThemes.first { $0.id == id } ?? benchThemes[0] }

/// The spacing scale (web/app.css --space-*).
enum Space {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
}

/// The type scale (web/app.css --text-*).
enum TypeScale {
    static let xs: CGFloat = 11
    static let sm: CGFloat = 12.5
    static let md: CGFloat = 14
    static let lg: CGFloat = 16
    static let xl: CGFloat = 20
}

struct Themed: ViewModifier {
    @AppStorage("theme") private var themeId = "classic"
    @AppStorage("theme.matchSystem") private var matchSystem = false
    @AppStorage("theme.dark") private var darkId = "classic"
    @AppStorage("theme.light") private var lightId = "quiet-temple"
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        // Matching macOS follows the system's appearance; otherwise the theme sets it.
        let t = themeById(matchSystem ? (scheme == .dark ? darkId : lightId) : themeId)
        content
            .environment(\.theme, t)
            .preferredColorScheme(matchSystem ? nil : (t.dark ? .dark : .light))
            .tint(t.colors.accent)
    }
}

extension View {
    func themed() -> some View { modifier(Themed()) }
}

extension BenchColors {
    func status(_ s: String) -> Color {
        switch s {
        case "running": accent
        case "waiting": warn
        case "error": bad
        default: dotIdle
        }
    }
}

func statusLabel(_ s: String) -> String {
    switch s {
    case "running": "Working"
    case "waiting": "Needs you"
    case "error": "Error"
    default: "Idle"
    }
}
