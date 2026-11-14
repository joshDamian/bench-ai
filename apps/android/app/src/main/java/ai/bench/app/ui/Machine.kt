package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import ai.bench.app.data.BenchStore
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch

/** "$2", "$7.50": a dollar amount as the caps show it. */
fun dollars(v: Double): String = if (v % 1.0 == 0.0) "$${v.toLong()}" else "$" + "%.2f".format(v)

/**
 * Spending: caps for what runs while the person is not watching, and one switch that pauses it
 * all (the Spending board). Runs the person starts are not capped.
 */
@Composable
fun SpendingScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val sp by store.spending.collectAsState()
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { store.loadExtras() }
    fun save(daily: Double? = null, run: Double? = null, paused: Boolean? = null) = scope.launch {
        runCatching { store.api.setSpending(daily, run, paused) }.onSuccess { store.setSpending(it); error = null }.onFailure { error = it.message }
    }
    Page("Spending", "Caps for what runs while you are not watching. Runs you start yourself are not capped.", onBack) {
        val s = sp
        if (s == null) return@Page
        item("today") {
            Column(
                Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Unattended today", fontSize = 15.sp, color = c.muted, modifier = Modifier.weight(1f))
                    Text(dollars(s.today), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(" of ${dollars(s.daily_cap)}", fontSize = 15.sp, color = c.muted)
                }
                val share = (s.today / s.daily_cap).coerceIn(0.0, 1.0).toFloat()
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surface3)) {
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (share >= 1f) c.warn else c.accent))
                }
                Text(if (s.runs_today == 0) "Nothing has run on its own today" else "${s.runs_today} run${if (s.runs_today == 1) "" else "s"} on their own today", fontSize = 13.sp, color = c.muted)
            }
        }
        item("caps") {
            Column(Modifier.padding(top = 10.dp)) {
                Card {
                    NavRow("Daily cap", dollars(s.daily_cap), "Unattended runs stop for the day at this") { picking = "daily" }
                    NavRow("Cap per run", dollars(s.run_cap), "A run that reaches it stops and asks you") { picking = "run" }
                }
            }
        }
        section("Pause")
        item("pause") { Card { SwitchRow("Pause unattended runs", "Schedules wait. Runs you start still work.", s.paused) { save(paused = it) } } }
        error?.let { e -> item("error") { Text(e, fontSize = 14.sp, color = c.bad, modifier = Modifier.padding(start = 6.dp, top = 10.dp)) } }
    }
    val s = sp ?: return
    when (picking) {
        "daily" -> PickDialog("Daily cap", listOf(2.0, 5.0, 10.0, 20.0, 50.0).map { it.toString() to dollars(it) }, s.daily_cap.toString(), onDismiss = { picking = null }) { picking = null; save(daily = it.toDouble()) }
        "run" -> PickDialog("Cap per run", listOf(0.5, 1.0, 2.0, 5.0, 10.0).map { it.toString() to dollars(it) }, s.run_cap.toString(), onDismiss = { picking = null }) { picking = null; save(run = it.toDouble()) }
    }
}
