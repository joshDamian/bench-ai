package ai.bench.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance

// Bench's tokens, as Compose reads them. A screen takes colours from Bench.colors and
// sizes from Space and Type below, never from a literal - the same rule CLAUDE.md sets
// for the web components.

val LocalBench = staticCompositionLocalOf { BenchThemes.first() }

object Bench {
    val colors: BenchColors @Composable get() = LocalBench.current.colors
    val spec: BenchThemeSpec @Composable get() = LocalBench.current
}

/** The spacing scale (web/app.css --space-*). */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** The type scale (web/app.css --text-*), in sp so it follows the system font size. */
object Type {
    val xs = 11.sp
    val sm = 12.5.sp
    val md = 14.sp
    val lg = 16.sp
    val xl = 20.sp
    val xxl = 28.sp
}

val Mono = FontFamily.Monospace

fun themeById(id: String): BenchThemeSpec = BenchThemes.firstOrNull { it.id == id } ?: BenchThemes.first()

private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
    val l1 = a.luminance() + 0.05f
    val l2 = b.luminance() + 0.05f
    return if (l1 > l2) l1 / l2 else l2 / l1
}

/** Moves `fg` toward `toward` until it reads at `min`:1 on `bg` (WCAG). */
private fun readable(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, toward: androidx.compose.ui.graphics.Color, min: Float): androidx.compose.ui.graphics.Color {
    var t = 0f
    var c = fg
    while (contrast(c, bg) < min && t < 1f) { t += 0.05f; c = androidx.compose.ui.graphics.lerp(fg, toward, t) }
    return c
}

/**
 * The stylesheet's colours with contrast guaranteed: text on the accent picks black or white
 * by what reads, and muted text is lifted until it reaches 4.5:1 on the surfaces it sits on.
 */
private fun legible(c: BenchColors): BenchColors {
    val onAccent = if (contrast(androidx.compose.ui.graphics.Color.Black, c.accent) >= contrast(androidx.compose.ui.graphics.Color.White, c.accent))
        androidx.compose.ui.graphics.Color(0xFF0B0D10) else androidx.compose.ui.graphics.Color.White
    return c.copy(
        onAccent = onAccent,
        muted = readable(c.muted, c.surface2, c.text, 4.5f),
        text = readable(c.text, c.surface2, if (c.bg.luminance() < 0.5f) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black, 7f),
    )
}

@Composable
fun BenchTheme(themeId: String, content: @Composable () -> Unit) {
    val raw = themeById(themeId)
    val spec = raw.copy(colors = legible(raw.colors))
    val c = spec.colors
    val scheme = if (spec.dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            secondary = c.accent, onSecondary = c.onAccent,
            background = c.bg, onBackground = c.text,
            surface = c.surface1, onSurface = c.text,
            surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
            surfaceContainer = c.surface1, surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3,
            surfaceContainerLow = c.surfaceRail, surfaceContainerLowest = c.surfaceSunken,
            outline = c.lineStrong, outlineVariant = c.line,
            error = c.bad, onError = c.bg,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            secondary = c.accent, onSecondary = c.onAccent,
            background = c.bg, onBackground = c.text,
            surface = c.surface1, onSurface = c.text,
            surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
            surfaceContainer = c.surface1, surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3,
            surfaceContainerLow = c.surfaceRail, surfaceContainerLowest = c.surfaceSunken,
            outline = c.lineStrong, outlineVariant = c.line,
            error = c.bad, onError = c.surface1,
        )
    }
    // No colour in the type styles: text takes its colour from what it sits on (LocalContentColor),
    // so a label on the accent button is the button's content colour, not the page's text colour.
    val base = TextStyle()
    val typography = Typography(
        titleLarge = base.copy(fontSize = Type.xl, fontWeight = FontWeight.SemiBold),
        titleMedium = base.copy(fontSize = Type.lg, fontWeight = FontWeight.SemiBold),
        titleSmall = base.copy(fontSize = Type.md, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.copy(fontSize = Type.lg, lineHeight = 24.sp),
        bodyMedium = base.copy(fontSize = Type.md, lineHeight = 21.sp),
        bodySmall = base.copy(fontSize = Type.sm, lineHeight = 18.sp),
        labelLarge = base.copy(fontSize = Type.md, fontWeight = FontWeight.Medium),
        labelMedium = base.copy(fontSize = Type.sm, fontWeight = FontWeight.Medium),
        labelSmall = base.copy(fontSize = Type.xs, fontWeight = FontWeight.Medium),
    )
    val shapes = Shapes(
        small = RoundedCornerShape(spec.radiusSm.dp),
        medium = RoundedCornerShape(spec.radius.dp),
        large = RoundedCornerShape(spec.radius.dp),
    )
    // Status and navigation bar icons follow the theme, not the phone's dark mode: dark icons
    // on a light theme, light icons on a dark one.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !spec.dark
                isAppearanceLightNavigationBars = !spec.dark
            }
        }
    }
    CompositionLocalProvider(LocalBench provides spec) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
