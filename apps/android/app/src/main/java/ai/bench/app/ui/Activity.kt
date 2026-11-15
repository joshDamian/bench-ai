package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.ActivityFeed
import ai.bench.app.data.ActivityItem
import ai.bench.app.data.BenchStore
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Activity (the Activity board): every run, who started it and what it touched, by day, with
 * the day's spend. Opening it counts as looking: Home's "while you were away" line goes.
 */
@Composable
fun ActivityScreen(store: BenchStore, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf<ActivityFeed?>(null) }
    var failed by remember { mutableStateOf(false) }
    var brief by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        runCatching { store.api.activity() }.onSuccess { feed = it }.onFailure { failed = true }
        brief = runCatching { store.api.brief() }.getOrNull()
        runCatching { store.api.seenActivity() }
        store.clearActivityUnseen()
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    Page("Activity", "Every run, who started it and what it touched.", onBack) {
        if (failed) {
            item("old") { Text("Activity needs the Go server.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 24.dp)) }
            return@Page
        }
        val f = feed ?: return@Page
        if (f.items.isEmpty()) item("empty") { Text("Nothing in the last 7 days.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 24.dp)) }
        val days = f.items.groupBy { runCatching { Instant.parse(it.at).atZone(zone).toLocalDate() }.getOrDefault(today) }
        days.forEach { (day, items) ->
            item("d-$day") {
                Column(Modifier.padding(top = 22.dp)) {
                    Row(Modifier.padding(horizontal = 6.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM")) },
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.weight(1f),
                        )
                        val spent = items.sumOf { it.cost }
                        if (spent > 0) Text(ai.bench.app.data.Format.cost(spent), fontSize = 13.sp, color = c.muted)
                    }
                    items.forEach { a ->
                        HorizontalDivider(color = c.line)
                        ActivityRow(a, zone, onUndo = if (a.undo && a.id != null) ({
                            scope.launch {
                                runCatching { store.api.undoActivity(a.id) }
                                feed = runCatching { store.api.activity() }.getOrNull() ?: feed
                                store.refresh()
                            }
                        }) else null) { a.session_id?.let(onOpen) }
                    }
                }
            }
        }
        brief?.let { on ->
            section("Morning brief")
            item("brief") {
                Card {
                    SwitchRow("Morning brief", "One notification at 7:30 when things ran overnight", on) { v ->
                        brief = v
                        scope.launch { runCatching { store.api.setBrief(v) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(a: ActivityItem, zone: ZoneId, onUndo: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Bench.colors
    val time = runCatching { Instant.parse(a.at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = a.session_id != null, onClick = onClick).padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(time, fontSize = 14.sp, color = c.muted, modifier = Modifier.width(44.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(a.title, fontSize = 16.sp, lineHeight = 21.sp)
            a.by?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = c.muted) }
            a.detail?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = c.muted) }
            onUndo?.let { u -> Text("Undo", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = u).padding(vertical = 4.dp)) }
        }
        a.badge?.let { b ->
            val color = when (b) { "Never" -> c.warn; "Ready" -> c.accent; else -> c.muted }
            Text(b, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color,
                modifier = Modifier.background(c.surface2, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}
