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
import androidx.compose.runtime.setValue
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
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column

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

/** Claude's prose: body at 15sp, headings a step or two above it, never display-sized. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val c = Bench.colors
    val body = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, color = c.text)
    val head = body.copy(fontWeight = FontWeight.SemiBold)
    Markdown(
        content = text,
        modifier = modifier,
        colors = markdownColor(
            text = c.text,
            codeText = c.wellText,
            inlineCodeText = c.text,
            linkText = c.accent,
            codeBackground = c.wellBg,
            inlineCodeBackground = c.text.copy(alpha = 0.07f),
            dividerColor = c.line,
            tableText = c.text,
            tableBackground = c.surface1,
        ),
        typography = markdownTypography(
            h1 = head.copy(fontSize = 20.sp, lineHeight = 28.sp),
            h2 = head.copy(fontSize = 18.sp, lineHeight = 26.sp),
            h3 = head.copy(fontSize = 16.sp, lineHeight = 24.sp),
            h4 = head, h5 = head, h6 = head.copy(color = c.muted),
            text = body, paragraph = body, ordered = body, bullet = body, list = body,
            code = body.copy(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = c.wellText),
            inlineCode = body.copy(fontFamily = Mono, fontSize = 13.5.sp),
            quote = body.copy(color = c.muted),
            link = body.copy(color = c.accent),
            table = body.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
        ),
        components = com.mikepenz.markdown.compose.components.markdownComponents(
            codeFence = { m -> com.mikepenz.markdown.compose.elements.MarkdownCodeFence(m.content, m.node) { code, lang, _ -> CodeCard(code, lang) } },
            codeBlock = { m -> com.mikepenz.markdown.compose.elements.MarkdownCodeBlock(m.content, m.node) { code, lang, _ -> CodeCard(code, lang) } },
        ),
    )
}

/** A code block as a card: the language and Copy on top, highlighted code that scrolls sideways. */
@Composable
fun CodeCard(code: String, language: String?) {
    val c = Bench.colors
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by androidx.compose.runtime.remember(code) { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    val dark = c.wellBg.luminance() < 0.5f
    val styled = androidx.compose.runtime.remember(code, language, dark) {
        val lang = language?.let { dev.snipme.highlights.model.SyntaxLanguage.getByName(it) }
        val h = dev.snipme.highlights.Highlights.Builder(theme = dev.snipme.highlights.model.SyntaxThemes.default(darkMode = dark))
            .code(code).let { if (lang != null) it.language(lang) else it }.build()
        androidx.compose.ui.text.buildAnnotatedString {
            append(h.getCode())
            h.getHighlights().filterIsInstance<dev.snipme.highlights.model.ColorHighlight>().forEach {
                addStyle(androidx.compose.ui.text.SpanStyle(color = Color(it.rgb).copy(alpha = 1f)), it.location.start, it.location.end)
            }
        }
    }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(c.wellBg, RoundedCornerShape(14.dp))
            .border(1.dp, c.line, RoundedCornerShape(14.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(language?.ifBlank { null } ?: "code", fontSize = 12.sp, color = c.muted, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(code)); copied = true }) {
                androidx.compose.material3.Icon(
                    if (copied) androidx.compose.material.icons.Icons.Default.Check else androidx.compose.material.icons.Icons.Default.ContentCopy,
                    null, tint = c.muted, modifier = Modifier.size(14.dp),
                )
                Text(if (copied) "Copied" else "Copy", fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
            Text(styled, style = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = c.wellText), softWrap = false)
        }
    }
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
