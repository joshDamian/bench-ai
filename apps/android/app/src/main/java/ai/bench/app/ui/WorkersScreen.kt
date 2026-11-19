package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.BenchApi
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Format
import ai.bench.app.data.arr
import ai.bench.app.data.bool
import ai.bench.app.data.get
import ai.bench.app.data.str
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Machines page: the workers that run this server's jobs, and any service one holds. */
@Composable
fun WorkersScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var workers by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var services by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var revoking by remember { mutableStateOf<JsonElement?>(null) }
    var stopping by remember { mutableStateOf<JsonElement?>(null) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        workers = runCatching { store.api.get("/api/workers").arr().orEmpty().toList() }.getOrDefault(workers)
        services = runCatching { store.api.get("/api/services").arr().orEmpty().toList() }.getOrDefault(services)
    }
    // A link can open or close while the page is up, so it reads the pool again every few seconds.
    LaunchedEffect(Unit) {
        while (true) {
            load()
            delay(5000)
        }
    }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

    fun enrol() {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { store.api.post("/api/workers", buildJsonObject { put("name", trimmed) }) }
                .onSuccess { command = it["command"].str(); name = ""; load() }
                .onFailure { error = "The server did not enrol the machine. Try again." }
            busy = false
        }
    }

    Page("Workers", "Machines that run checks, renders and simulators", onBack) {
        section("Machines", workers.size.takeIf { it > 0 }?.toString())
        item("machines") {
            Card {
                if (workers.isEmpty()) {
                    NavRow("No other machines yet", sub = "Enrol one to run checks, renders and simulators there.", chevron = false)
                }
                workers.forEach { w ->
                    val online = w["online"].bool()
                    Custom {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).background(if (online) c.good else c.muted, CircleShape))
                            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(w["name"].str() ?: "Worker", fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(workerHint(w), fontSize = 12.5.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                "Revoke",
                                fontSize = 14.sp,
                                color = c.bad,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { revoking = w }.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        if (services.isNotEmpty()) {
            section("Services")
            item("services") {
                Card {
                    services.forEach { s ->
                        NavRow(
                            s["detail"]["device"].str() ?: s["id"].str() ?: "Service",
                            "Stop",
                            serviceHint(s),
                            valueColor = c.accent,
                            chevron = false,
                        ) { stopping = s }
                    }
                }
            }
        }
        section("Enrol a worker")
        item("enrol") {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Get bench-worker on the machine, then run this command there. It joins this server and takes jobs while it is online, then reconnects by itself.",
                    fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(horizontal = 6.dp),
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    placeholder = { Text("Name this machine") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                BigButton(if (busy) "Enrolling…" else "Enrol", enabled = !busy && name.isNotBlank()) { enrol() }
                command?.let { cmd ->
                    Column(
                        Modifier.fillMaxWidth().background(c.surface2, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SelectionContainer { Text(cmd, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace) }
                        TextButton(onClick = { clip.setText(AnnotatedString(cmd)); copied = true }) {
                            Text(if (copied) "Copied" else "Copy", fontSize = 14.sp)
                        }
                    }
                    Text(
                        "The token in it is shown once. If it is lost, revoke the worker and enrol another.",
                        fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                error?.let { Text(it, fontSize = 13.sp, color = c.bad, modifier = Modifier.padding(horizontal = 6.dp)) }
            }
        }
        item("note") {
            Text(
                "A worker holds no durable state. If it goes away mid-job, the server runs the job itself.",
                fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp),
            )
        }
    }

    revoking?.let { w ->
        Confirm("Revoke ${w["name"].str() ?: "this worker"}?", "Its token stops working at once. Enrol the machine again to bring it back.", "Revoke", onDismiss = { revoking = null }) {
            scope.launch { runCatching { store.api.delete("/api/workers/${BenchApi.enc(w["id"].str().orEmpty())}") }; load() }
        }
    }
    stopping?.let { s ->
        val what = s["detail"]["device"].str() ?: s["kind"].str() ?: "this service"
        Confirm("Stop $what?", "The worker shuts it down. A later job can start it again.", "Stop", danger = false, onDismiss = { stopping = null }) {
            scope.launch { runCatching { store.api.post("/api/services/${BenchApi.enc(s["id"].str().orEmpty())}/stop") }; load() }
        }
    }
}

/** What the worker offers, or when it was last here. */
private fun workerHint(w: JsonElement): String {
    if (!w["online"].bool()) {
        val ago = w["last_seen"].str()?.let { Format.ago(it) }
        return if (ago.isNullOrEmpty()) "Never linked" else "Last seen $ago ago"
    }
    val platform = w["platform"].str() ?: ""
    val machine = if (platform.isEmpty()) "Machine" else "$platform machine"
    fun number(key: String): Int = ((w[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0).toInt()
    val cores = ((w["resources"]["cpus"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0).toInt()
    val slots = number("slots")
    val kinds = w["kinds"].arr().orEmpty().mapNotNull { it.str() }.joinToString(", ")
    val parts = mutableListOf(if (cores > 0) "$machine with $cores cores" else machine)
    if (slots > 0) parts += "taking $slots job${if (slots == 1) "" else "s"} at a time"
    if (kinds.isNotEmpty()) parts += "runs $kinds"
    return parts.joinToString(", ") + "."
}

/** A held service: its kind, and how long it has been up. */
private fun serviceHint(s: JsonElement): String {
    val secs = ((s["held_s"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0).toInt()
    val held = when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m"
        else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
    }
    return "Held for $held, started by a ${s["kind"].str() ?: "service"} job."
}
