package ai.bench.app.ui

import ai.bench.app.ui.theme.Appearance
import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.BenchApp
import ai.bench.app.BuildConfigInfo
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Effort
import ai.bench.app.data.Mode
import ai.bench.app.data.ProviderInfo
import ai.bench.app.data.get
import ai.bench.app.data.str
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Settings tab, as its board draws it: Server, Claude, Alerts and Appearance in grouped rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(store: BenchStore, onOpen: (String) -> Unit = {}) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val settings = BenchApp.instance.settings
    val theme by settings.theme.collectAsState("system")
    val scope = rememberCoroutineScope()
    var about by remember { mutableStateOf<ai.bench.app.data.About?>(null) }
    LaunchedEffect(Unit) { about = runCatching { store.api.about() }.getOrNull() }
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var defaults by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    var page by remember { mutableStateOf<String?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var pairLink by remember { mutableStateOf<String?>(null) }
    val prefs = remember { BenchApp.instance.getSharedPreferences("alerts", 0) }
    var alertNeeds by remember { mutableStateOf(prefs.getBoolean("needs", true)) }
    var alertDone by remember { mutableStateOf(prefs.getBoolean("finished", false)) }

    suspend fun load() {
        providers = runCatching { store.api.providers() }.getOrDefault(providers)
        defaults = runCatching { store.api.defaults() }.getOrNull()
    }
    LaunchedEffect(Unit) { load() }
    fun put(body: kotlinx.serialization.json.JsonObject) = scope.launch { runCatching { store.api.putDefaults(body) }; load() }

    val modelId = defaults["modelId"].str()
    val modelName = providers.flatMap { it.models }.firstOrNull { it.id == modelId }?.label ?: "Provider default"
    val effort = defaults["effort"].str()
    val mode = Mode.of(defaults["mode"].str() ?: "default")

    var serverInfo by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    var deviceCount by remember { mutableStateOf<Int?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    LaunchedEffect(Unit) {
        serverInfo = runCatching { store.api.get("/api/server/status") }.getOrNull()
        deviceCount = runCatching { store.api.get("/api/devices").let { (it as? kotlinx.serialization.json.JsonArray)?.size } }.getOrNull()
        update = runCatching { store.api.get("/api/server/update") }.getOrNull()
    }
    val uptime = (serverInfo["uptimeMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.let { ms ->
        val d = (ms / 86_400_000).toInt(); if (d >= 1) "Up $d day${if (d == 1) "" else "s"}" else "Up ${(ms / 3_600_000).toInt()}h"
    }
    val routing = defaults["routing"]
    val canUpdate = update["canUpdate"].let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "true" } && update["newer"] !is kotlinx.serialization.json.JsonNull && update["newer"] != null

    Scaffold(containerColor = c.bg, topBar = { TabTitle("Settings") }) { pad ->
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            section("You")
            item("about") { Card { NavRow("About me", null, aboutSummary(about)) { onOpen("about") } } }
            section("Server")
            item {
                Card {
                    val saved by settings.servers.collectAsState(emptyList())
                    // Every saved server, the one in use first: switch between Node and Go, say.
                    (listOf(store.api.config.baseUrl) + saved.filter { it != store.api.config.baseUrl }).forEach { url ->
                        val here = url == store.api.config.baseUrl
                        Custom {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 6.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.md),
                            ) {
                                Box(Modifier.size(8.dp).then(if (here) Modifier.background(if (s.connected) c.good else c.warn, CircleShape) else Modifier))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(url.substringAfter("://").substringBefore(".").substringBefore(":"), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (here) listOfNotNull(if (s.connected) "Connected" else "Connecting…", s.version?.let { "Bench $it" }, uptime).joinToString(" · ")
                                        else url.substringAfter("://"),
                                        fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (here) Text("In use", fontSize = 14.sp, color = c.muted)
                                else Text(
                                    "Switch", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).clickable { scope.launch { settings.switchTo(url) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    NavRow("Add a server") { settings.adding.value = true }
                    if (canUpdate) NavRow("Update Bench", "Ready to install") {
                        scope.launch { runCatching { store.api.post("/api/server/update") } }
                    }
                    NavRow("Devices", deviceCount?.toString()) { onOpen("devices") }
                    NavRow("Dashboard", "Memory, CPU, spend") { onOpen("dashboard") }
                    NavRow("Projects folder", defaults["projectsDir"].str()?.replace(Regex("^/(home|Users)/[^/]+"), "~")) { page = "dir" }
                    NavRow("Diagnostics") { onOpen("diagnostics") }
                    NavRow("Restart server", null, "Sessions pick up where they were", chevron = false) { confirmRestart = true }
                }
            }
            section("Claude")
            item {
                Card {
                    NavRow("Default model", modelName) { page = "model" }
                    NavRow("Default effort", effort?.let { Effort.of(it)?.label } ?: "Auto") { page = "effort" }
                    NavRow("Default mode", mode.label) { page = "mode" }
                    NavRow("Models", "${providers.count { it.enabled }} providers") { page = "models" }
                    NavRow("Subagents", if (routing["all"].str() != null) "One model" else "By tier") { onOpen("subagents/default") }
                    NavRow("Schedules", store.schedules.collectAsState().value.count { it.enabled }.takeIf { it > 0 }?.toString() ?: "None") { onOpen("schedules") }
                    NavRow("Usage limit", if (defaults["limits"]["autoResume"].let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "false" }) "Wait" else "Resume") { onOpen("limit") }
                }
            }
            section("This phone")
            item {
                Card {
                    NavRow("Notifications", if (prefs.getBoolean("background", false)) "Always" else "While open") { onOpen("notifications") }
                    Custom {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Appearance", fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Segmented(
                                Appearance.entries.map { it.id to it.label },
                                Appearance.of(theme).id,
                                { id -> scope.launch { settings.setTheme(id) } },
                                Modifier.width(222.dp),
                                height = 40.dp,
                            )
                        }
                    }
                }
            }
            item {
                val saved by settings.servers.collectAsState(emptyList())
                Column(Modifier.padding(top = 28.dp)) {
                    Card {
                        NavRow("Disconnect this phone", null, if (saved.size > 1) "Forgets this server and switches to the next one" else "You'll need to pair again to reconnect", labelColor = c.bad, chevron = false) { confirmUnpair = true }
                    }
                }
            }
            item { Text("Bench for Android ${BuildConfigInfo.versionName}", fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 22.dp)) }
        }
    }
    if (confirmRestart) Confirm("Restart the server?", "Running turns stop and resume once it's back, usually within a few seconds.", "Restart", onDismiss = { confirmRestart = false }) {
        scope.launch { runCatching { store.api.post("/api/server/restart") } }
    }

    if (page == "dir") ai.bench.app.ui.TextDialog("Projects folder", defaults["projectsDir"].str().orEmpty(), "Save", "~/bench-projects", onDismiss = { page = null }) { v ->
        put(buildJsonObject { put("projectsDir", v.trim()) })
    }
    when (page) {
        "model" -> Choose("Default model", listOf("" to "Provider default") + providers.filter { it.enabled && it.ready }.flatMap { p -> p.models.filter { it.enabled }.map { it.id to "${it.label} · ${p.name}" } }, modelId ?: "", { page = null }) { id ->
            put(buildJsonObject {
                if (id.isEmpty()) put("modelId", JsonNull) else {
                    put("modelId", id)
                    providers.firstOrNull { p -> p.models.any { it.id == id } }?.let { put("providerId", it.id) }
                }
            })
        }
        "effort" -> Choose("Default effort", listOf("" to "Model default") + Effort.entries.map { it.wire to it.label }, effort ?: "", { page = null }) { e ->
            put(buildJsonObject { if (e.isEmpty()) put("effort", JsonNull) else put("effort", e) })
        }
        "mode" -> Choose("Default mode", Mode.entries.map { it.wire to it.label }, mode.wire, { page = null }) { m -> put(buildJsonObject { put("mode", m) }) }
        "models" -> ModelsDialog(providers, onDismiss = { page = null },
            onProvider = { id, on -> scope.launch { runCatching { store.api.patch("/api/providers/${ai.bench.app.data.BenchApi.enc(id)}", buildJsonObject { put("enabled", on) }) }; load() } },
        ) { id, on -> scope.launch { runCatching { store.api.setModel(id, on) }; load() } }
    }

    pairLink?.let { link ->
        AlertDialog(
            onDismissRequest = { pairLink = null },
            title = { Text("Pair another device") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    qr(link)?.let { androidx.compose.foundation.Image(it.asImageBitmap(), "Pairing code", Modifier.size(220.dp).background(androidx.compose.ui.graphics.Color.White).padding(12.dp)) }
                    Text("Scan with the Bench app, or open the link on the other device. It works once, for 10 minutes.", fontSize = 13.sp, color = c.muted)
                }
            },
            confirmButton = { TextButton(onClick = { pairLink = null }) { Text("Done") } },
        )
    }

    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text("Disconnect this phone?") },
        text = { Text("You'll need to pair again to reconnect.") },
        confirmButton = { TextButton(onClick = { confirmUnpair = false; scope.launch { settings.forget() } }) { Text("Disconnect", color = c.bad) } },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
    )
}

private fun qr(text: String): android.graphics.Bitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
    android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.RGB_565).apply {
        for (x in 0 until 512) for (y in 0 until 512) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}.getOrNull()

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Bench.colors.accent, modifier = Modifier.padding(start = 4.dp))
        Column(Modifier.fillMaxWidth().background(Bench.colors.surface1, RoundedCornerShape(20.dp))) { content() }
    }
}

@Composable
private fun Item(label: String, value: String, danger: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = Space.lg, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(label, fontSize = 15.sp, color = if (danger) Bench.colors.bad else Bench.colors.text)
        Text(value, fontSize = 12.5.sp, color = Bench.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Toggle(label: String, value: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Bench.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onChange(!on) }.padding(horizontal = Space.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp)
            Text(value, fontSize = 12.5.sp, color = c.muted)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
    }
}

@Composable
private fun Choose(title: String, options: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(id); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(id == current, { onPick(id); onDismiss() })
                        Text(label, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ModelsDialog(providers: List<ProviderInfo>, onDismiss: () -> Unit, onProvider: (String, Boolean) -> Unit, onToggle: (String, Boolean) -> Unit) {
    val c = Bench.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Models") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                providers.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(top = Space.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when { !p.enabled -> "Off"; !p.ready -> p.reason ?: "Not set up"; else -> "${p.models.count { it.enabled }} of ${p.models.size} models on" },
                                fontSize = 12.5.sp, color = if (p.enabled && !p.ready) c.warn else c.muted,
                            )
                        }
                        Switch(p.enabled, { onProvider(p.id, it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
                    }
                    if (p.enabled) p.models.forEach { m ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.label, fontSize = 15.sp, color = c.text.copy(alpha = 0.85f), modifier = Modifier.weight(1f).padding(start = Space.md))
                            Switch(m.enabled, { onToggle(m.id, it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
                        }
                    }
                    HorizontalDivider(color = c.line)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
