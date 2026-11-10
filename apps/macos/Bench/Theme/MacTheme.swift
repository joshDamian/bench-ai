import AppKit
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
    /// The palette with the Mac's accent colour. Everything macOS draws itself (the sidebar's
    /// selection, the text cursor, focus rings, switches) already uses it, so Bench's own
    /// buttons and marks follow too: one accent on screen. With macOS set to Multicolor this
    /// is the app's AccentColor asset, the board's blue.
    func systemAccent() -> BenchThemeSpec {
        let ns = NSColor.controlAccentColor.usingColorSpace(.sRGB) ?? .systemBlue
        let a = Color(nsColor: ns)
        // Text on the accent: whichever of black and white reads better.
        let l = 0.2126 * ns.redComponent + 0.7152 * ns.greenComponent + 0.0722 * ns.blueComponent
        let on: Color = l > 0.55 ? Color(.sRGB, red: 0.04, green: 0.07, blue: 0.13, opacity: 1) : .white
        let c = colors
        return BenchThemeSpec(id: id, name: name, dark: dark, radius: radius, radiusSm: radiusSm, colors: BenchColors(
            bg: c.bg, surfaceSunken: c.surfaceSunken, surface1: c.surface1, surface2: c.surface2, surface3: c.surface3,
            surfaceRail: c.surfaceRail, text: c.text, muted: c.muted, accent: a, onAccent: on,
            line: c.line, lineStrong: c.lineStrong, good: c.good, warn: c.warn, bad: c.bad, info: a,
            dotIdle: c.dotIdle, wellBg: c.wellBg, wellText: c.wellText))
    }
}


/// The project rail's tiles: grey with a hint of Bench's violet (the icon ring's cool end),
/// raised in dark, barely off the paper in light. Colour on the rail otherwise means status.
struct RailPalette {
    let top: Color, bottom: Color, selTop: Color, selBottom: Color
    let hairline: Color, highlight: Color, shadow: Color, selShadow: Color
    let letters: Color, bar: Color, barHover: Color
    /// The project you're in: solid violet, Bench's colour, with white letters.
    let vivid: [Color], vividGlow: Color
    /// The open session's row: a wash of the same violet, stronger at the left, with an edge.
    let rowWash: Color, rowWashEnd: Color, rowEdge: Color

    static func of(dark: Bool) -> RailPalette {
        dark ? RailPalette(
            top: hex(0x46474f), bottom: hex(0x33304a), selTop: hex(0x57586a), selBottom: hex(0x43386e),
            hairline: Color.white.opacity(0.06), highlight: Color.white.opacity(0.14),
            shadow: Color.black.opacity(0.35), selShadow: Color.black.opacity(0.45),
            letters: hex(0xf1f2f4), bar: hex(0xb9a3ff), barHover: hex(0xb9a3ff).opacity(0.5),
            vivid: [hex(0xa58bff), hex(0x6a4ce0), hex(0x4b32b8)], vividGlow: hex(0x6b4ce0).opacity(0.55), rowWash: hex(0xa58bff).opacity(0.22), rowWashEnd: hex(0xa58bff).opacity(0.07), rowEdge: hex(0xb9a3ff).opacity(0.22))
        : RailPalette(
            top: hex(0xfdfdfe), bottom: hex(0xf2f0f7), selTop: hex(0xf8f6fd), selBottom: hex(0xe8e2f7),
            hairline: hex(0x281e50).opacity(0.07), highlight: Color.white.opacity(0.9),
            shadow: hex(0x281e50).opacity(0.07), selShadow: hex(0x463296).opacity(0.13),
            letters: hex(0x44465a), bar: hex(0x6b55d6), barHover: hex(0x6b55d6).opacity(0.35),
            vivid: [hex(0x8f78f0), hex(0x6b52dc), hex(0x5640c4)], vividGlow: hex(0x5b40c4).opacity(0.35), rowWash: hex(0x6b52dc).opacity(0.14), rowWashEnd: hex(0x6b52dc).opacity(0.04), rowEdge: hex(0x6b52dc).opacity(0.16))
    }
}


/// The terminal's colours: muted and even in brightness, built around Bench's greys and
/// violet so a prompt reads as calm teal and lilac, not neon (the canvas's Terminal boards).
struct TerminalPalette {
    let bg: Color, fg: Color, cursor: Color, selection: Color
    /// The 16 ANSI colours: black red green yellow blue magenta cyan white, then bright.
    let ansi: [UInt32]

    static func of(dark: Bool, bg: Color) -> TerminalPalette {
        dark ? TerminalPalette(
            bg: bg, fg: hex(0xd7dae0), cursor: hex(0xb9a3ff), selection: hex(0xb9a3ff).opacity(0.28),
            ansi: [0x3a3d45, 0xec7a7a, 0x7fcf9b, 0xe2c07d, 0x7aa7ff, 0xb9a3ff, 0x6fcfd6, 0xc9ccd3,
                   0x80858f, 0xf59a9a, 0x9be0b2, 0xecd29a, 0x9bbdff, 0xcdbcff, 0x91dde2, 0xf1f2f4])
        : TerminalPalette(
            bg: bg, fg: hex(0x2a2c31), cursor: hex(0x6b52dc), selection: hex(0x6b52dc).opacity(0.18),
            ansi: [0x2a2c31, 0xc4453c, 0x1f8a5b, 0xa06d00, 0x2f6fdb, 0x6b52dc, 0x0f7c86, 0x7a7f88,
                   0x5f636b, 0xd65a50, 0x27a06b, 0xb88200, 0x4a85ea, 0x8068e6, 0x1593a0, 0x1b1c1f])
    }
}
