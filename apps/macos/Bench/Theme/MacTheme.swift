import SwiftUI

// The Mac app's own palette, from the Mac v2 boards: neutral greys that sit with macOS's
// materials, one blue accent, amber for "needs you", green for "ready". The web's themes
// stay the web's; the Mac follows the system's light and dark.

private func hex(_ v: UInt32) -> Color {
    Color(.sRGB, red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255, opacity: 1)
}

let macDark = BenchThemeSpec(
    id: "mac-dark", name: "Dark", dark: true, radius: 12, radiusSm: 8,
    colors: BenchColors(
        bg: hex(0x1b1c1f),
        surfaceSunken: hex(0x17181b),
        surface1: hex(0x232428),
        surface2: hex(0x26272b),
        surface3: hex(0x303136),
        surfaceRail: hex(0x202125),
        text: hex(0xf1f2f4),
        muted: hex(0xa3a7b0),
        accent: hex(0x6ea8fe),
        onAccent: hex(0x0b1220),
        line: hex(0x2c2d31),
        lineStrong: hex(0x3a3b40),
        good: hex(0x5fd38d),
        warn: hex(0xf2c14e),
        bad: hex(0xff7b72),
        info: hex(0x6ea8fe),
        dotIdle: hex(0x5f636b),
        wellBg: hex(0x17181b),
        wellText: hex(0xe4e6ea)
    )
)

let macLight = BenchThemeSpec(
    id: "mac-light", name: "Light", dark: false, radius: 12, radiusSm: 8,
    colors: BenchColors(
        bg: hex(0xffffff),
        surfaceSunken: hex(0xf4f3f0),
        surface1: hex(0xf6f5f3),
        surface2: hex(0xfbfaf8),
        surface3: hex(0xeeede9),
        surfaceRail: hex(0xf6f5f3),
        text: hex(0x1b1c1f),
        muted: hex(0x5f636b),
        accent: hex(0x2f6fdb),
        onAccent: hex(0xffffff),
        line: hex(0xe6e4e0),
        lineStrong: hex(0xd4d2cd),
        good: hex(0x1f8a4c),
        warn: hex(0x9a6a00),
        bad: hex(0xc93c33),
        info: hex(0x2f6fdb),
        dotIdle: hex(0xa3a7b0),
        wellBg: hex(0xf4f3f0),
        wellText: hex(0x2a2c31)
    )
)

/// Settings › Appearance: follow macOS, or keep one.
enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String {
        switch self {
        case .system: "Match macOS"
        case .light: "Light"
        case .dark: "Dark"
        }
    }
}

extension BenchColors {
    /// A fill on any surface: text at low opacity, so it works in light and dark.
    func fill(_ strength: Double = 0.06) -> Color { text.opacity(strength) }
}


extension BenchThemeSpec {
    /// The same palette with macOS's accent colour (System Settings › Appearance) in place of the blue.
    func systemAccent() -> BenchThemeSpec {
        let a = Color(nsColor: .controlAccentColor)
        let c = colors
        return BenchThemeSpec(id: id, name: name, dark: dark, radius: radius, radiusSm: radiusSm, colors: BenchColors(
            bg: c.bg, surfaceSunken: c.surfaceSunken, surface1: c.surface1, surface2: c.surface2, surface3: c.surface3,
            surfaceRail: c.surfaceRail, text: c.text, muted: c.muted, accent: a, onAccent: .white,
            line: c.line, lineStrong: c.lineStrong, good: c.good, warn: c.warn, bad: c.bad, info: a,
            dotIdle: c.dotIdle, wellBg: c.wellBg, wellText: c.wellText))
    }
}
