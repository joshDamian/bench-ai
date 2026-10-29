package ai.bench.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Mono
import ai.bench.app.ui.theme.Space
import ai.bench.app.ui.theme.Type
import com.mikepenz.markdown.m3.Markdown

/** A session's state at a glance: idle, running (pulsing), waiting on you, or broken. */
@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val c = Bench.colors
    val color = when (status) {
        "running" -> c.accent
        "waiting" -> c.warn
        "error" -> c.bad
        else -> c.dotIdle
    }
    val pulse = if (status == "running") {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
        a
    } else 1f
    Box(modifier.size(8.dp).alpha(pulse).background(color, CircleShape))
}

fun statusLabel(status: String) = when (status) {
    "running" -> "Working"
    "waiting" -> "Needs you"
    "error" -> "Error"
    else -> "Idle"
}

/** A small tinted label: "Needs you", "Passed", a branch name. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = Space.sm, vertical = 2.dp),
        style = TextStyle(color = color, fontSize = Type.xs, fontWeight = FontWeight.Medium),
    )
}

/** Monospace text on the sunken well, scrolling sideways rather than wrapping. */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, color: Color = Bench.colors.wellText) {
    Box(
        modifier
            .background(Bench.colors.wellBg, MaterialTheme.shapes.small)
            .horizontalScroll(rememberScrollState())
            .padding(Space.sm),
    ) {
        Text(text, style = TextStyle(fontFamily = Mono, fontSize = Type.sm, color = color))
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    Markdown(content = text, modifier = modifier)
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier = modifier, color = Bench.colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = maxLines)
}

/** The line along the top when the server cannot be reached. */
@Composable
fun ConnectionBanner(connected: Boolean, dropped: Boolean, error: String?) {
    val msg = when {
        error != null && !connected -> error
        dropped && !connected -> "Reconnecting…"
        else -> return
    }
    Row(
        Modifier.background(Bench.colors.warn.copy(alpha = 0.12f)).padding(horizontal = Space.lg, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg, color = Bench.colors.warn, style = MaterialTheme.typography.labelMedium)
    }
}
