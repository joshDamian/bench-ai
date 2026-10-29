package ai.bench.app.ui

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
import ai.bench.app.ui.theme.BenchThemes
import ai.bench.app.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Settings tab, as its board draws it: Server, Claude, Alerts and Appearance in grouped rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(store: BenchStore) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val settings = BenchApp.instance.settings
    val theme by settings.theme.collectAsState("classic")
    val scope = rememberCoroutineScope()
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

    Scaffold(containerColor = c.bg, topBar = { TabTitle("Settings") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(start = Space.lg, end = Space.lg, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Group("Server") {
                Item("Connected to", listOfNotNull(store.api.config.baseUrl.substringAfter("://").substringBefore("."), s.version?.let { "Bench $it" }).joinToString(" · "))
                Item("Pair another device", "Show a code to scan") {
                    scope.launch { pairLink = runCatching { store.api.pairing()["urls"].let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull().str() } }.getOrNull() }
                }
                Item("Disconnect", "Pair again to reconnect", danger = true) { confirmUnpair = true }
            }
            Group("Claude") {
                Item("Default model", modelName) { page = "model" }
                Item("Default effort", effort?.let { Effort.of(it)?.label } ?: "Model default") { page = "effort" }
                Item("Default mode", mode.label) { page = "mode" }
                Item("Models", "${providers.sumOf { p -> p.models.count { it.enabled } }} turned on") { page = "models" }
            }
            Group("Alerts") {
                Toggle("When a session needs you", "Approvals and questions", alertNeeds) { alertNeeds = it; prefs.edit().putBoolean("needs", it).apply() }
                Toggle("When a session finishes", "Only if the app is closed", alertDone) { alertDone = it; prefs.edit().putBoolean("finished", it).apply() }
            }
            Group("Appearance") {
                Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Text("Theme", fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        BenchThemes.forEach { t ->
                            Column(
                                Modifier.width(72.dp).clickable { scope.launch { settings.setTheme(t.id) } },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).background(t.colors.bg, RoundedCornerShape(12.dp))
                                        .border(if (t.id == theme) 2.dp else 1.dp, if (t.id == theme) c.accent else c.line, RoundedCornerShape(12.dp))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(Modifier.weight(1f).size(14.dp).background(t.colors.surface1, RoundedCornerShape(4.dp)))
                                    Box(Modifier.size(14.dp).background(t.colors.accent, CircleShape))
                                }
                                Text(t.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            MutedText("Bench for Android ${BuildConfigInfo.versionName}")
        }
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
        "models" -> ModelsDialog(providers, onDismiss = { page = null }) { id, on -> scope.launch { runCatching { store.api.setModel(id, on) }; load() } }
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
private fun ModelsDialog(providers: List<ProviderInfo>, onDismiss: () -> Unit, onToggle: (String, Boolean) -> Unit) {
    val c = Bench.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Models") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                providers.forEach { p ->
                    Text(p.name + if (!p.ready) " · ${p.reason ?: "not set up"}" else "", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (p.ready) c.accent else c.warn, modifier = Modifier.padding(top = Space.sm))
                    p.models.forEach { m ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(m.enabled, { onToggle(m.id, it) }, enabled = p.enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
                        }
                    }
                    HorizontalDivider(color = c.line)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
