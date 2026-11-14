package ai.bench.app.ui.theme

import androidx.compose.ui.graphics.Color

// The phone's two palettes, the same as the Mac app's (apps/macos/Bench/Theme/MacTheme.swift):
// neutral greys, one blue accent, amber for "needs you", green for "done". The web keeps its
// themes (Tokens.kt, generated from web/themes.css); the apps follow the system's light and dark.

val PhoneDark = BenchThemeSpec(
    "dark", "Dark", true, 12f, 8f,
    BenchColors(
        bg = Color(0xFF1B1C1F),
        surfaceSunken = Color(0xFF17181B),
        surface1 = Color(0xFF232428),
        surface2 = Color(0xFF26272B),
        surface3 = Color(0xFF303136),
        surfaceRail = Color(0xFF202125),
        text = Color(0xFFF1F2F4),
        muted = Color(0xFFA3A7B0),
        accent = Color(0xFF6EA8FE),
        onAccent = Color(0xFF0B1220),
        line = Color(0xFF2C2D31),
        lineStrong = Color(0xFF3A3B40),
        good = Color(0xFF5FD38D),
        warn = Color(0xFFF2C14E),
        bad = Color(0xFFFF7B72),
        info = Color(0xFF6EA8FE),
        dotIdle = Color(0xFF5F636B),
        wellBg = Color(0xFF17181B),
        wellText = Color(0xFFE4E6EA),
    ),
)

val PhoneLight = BenchThemeSpec(
    "light", "Light", false, 12f, 8f,
    BenchColors(
        bg = Color(0xFFFFFFFF),
        surfaceSunken = Color(0xFFF4F3F0),
        surface1 = Color(0xFFF6F5F3),
        surface2 = Color(0xFFFBFAF8),
        surface3 = Color(0xFFEEEDE9),
        surfaceRail = Color(0xFFF6F5F3),
        text = Color(0xFF1B1C1F),
        muted = Color(0xFF5F636B),
        accent = Color(0xFF2F6FDB),
        onAccent = Color(0xFFFFFFFF),
        line = Color(0xFFE6E4E0),
        lineStrong = Color(0xFFD4D2CD),
        good = Color(0xFF1F8A4C),
        warn = Color(0xFF9A6A00),
        bad = Color(0xFFC93C33),
        info = Color(0xFF2F6FDB),
        dotIdle = Color(0xFFA3A7B0),
        wellBg = Color(0xFFF4F3F0),
        wellText = Color(0xFF2A2C31),
    ),
)

/** Settings › Appearance. */
enum class Appearance(val id: String, val label: String) {
    System("system", "System"), Light("light", "Light"), Dark("dark", "Dark");

    companion object {
        /** Reads a saved choice. Ids from the old theme picker map to the side they were on. */
        fun of(id: String?): Appearance = when (id) {
            null, "", "system", DYNAMIC -> System
            "light" -> Light
            "dark" -> Dark
            else -> BenchThemes.firstOrNull { it.id == id }?.let { if (it.dark) Dark else Light } ?: System
        }
    }
}
