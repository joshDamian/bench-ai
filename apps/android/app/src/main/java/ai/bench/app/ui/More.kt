package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.BenchApi
import ai.bench.app.data.BenchError
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Format
import ai.bench.app.data.ProviderInfo
import ai.bench.app.data.arr
import ai.bench.app.data.bool
import ai.bench.app.data.get
import ai.bench.app.data.str
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull

// -- The pieces every page shares ------------------------------------------------------------

/** A pushed page: a back arrow, then a large title and its line, then the content on one edge. */
@Composable
fun Page(title: String, sub: String?, onBack: () -> Unit, actions: @Composable () -> Unit = {}, bottom: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit) {
    val c = Bench.colors
    Scaffold(
        containerColor = c.bg,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Box(Modifier.weight(1f))
                actions()
            }
        },
        bottomBar = bottom,
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item("title") {
                Column(Modifier.padding(start = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
                    sub?.let { Text(it, fontSize = 14.sp, color = c.muted) }
                }
            }
            content()
        }
    }
}

/** A section label: more space above than below. */
fun LazyListScope.section(title: String, trailing: String? = null, color: Color? = null) {
    item("label-$title") {
        val c = Bench.colors
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 10.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color ?: c.muted, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, fontSize = 13.sp, color = color ?: c.muted) }
        }
    }
}

/** Rows on one rounded surface, hairlines between them. */
@Composable
fun Card(content: @Composable CardScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Bench.colors.surface1, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))) {
        CardScope().content()
    }
}

class CardScope {
    private var first = true
    @Composable
    fun divider() {
        if (!first) HorizontalDivider(color = Bench.colors.line.copy(alpha = 0.5f), modifier = Modifier.padding(start = 18.dp))
        first = false
    }
}

@Composable
fun CardScope.NavRow(label: String, value: String? = null, sub: String? = null, valueColor: Color? = null, labelColor: Color? = null, chevron: Boolean = true, onClick: (() -> Unit)? = null) {
    val c = Bench.colors
    divider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.5.sp, color = labelColor ?: c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, fontSize = 12.5.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        value?.let { Text(it, fontSize = 14.sp, color = valueColor ?: c.muted, maxLines = 1, modifier = Modifier.padding(start = 12.dp)) }
        if (chevron && onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

@Composable
fun CardScope.SwitchRow(label: String, sub: String?, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Bench.colors
    divider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(enabled = enabled) { onChange(!on) }.padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.5.sp, color = if (enabled) c.text else c.muted)
            sub?.let { Text(it, fontSize = 12.5.sp, color = c.muted) }
        }
        Switch(on, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.surface3, uncheckedBorderColor = Color.Transparent))
    }
}

@Composable
fun CardScope.Custom(content: @Composable () -> Unit) { divider(); content() }

/** Pick one of a few values in a dialog list, a tick on the current one. */
@Composable
fun PickDialog(title: String, options: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Bench.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface2,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 50.dp).background(if (id == current) c.accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { onPick(id); onDismiss() }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (id == current) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}


@Composable
fun Confirm(title: String, text: String, action: String, danger: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Bench.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface2,
        title = { Text(title) },
        text = { Text(text, color = c.muted) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action, color = if (danger) c.bad else c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// -- Devices ---------------------------------------------------------------------------------

@Composable
fun DevicesScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var revoking by remember { mutableStateOf<JsonElement?>(null) }
    var pairLink by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val me = store.state.value.me?.device?.id
    suspend fun load() { devices = runCatching { store.api.get("/api/devices").arr().orEmpty().toList() }.getOrDefault(devices) }
    LaunchedEffect(Unit) { load() }
    Page("Devices", "Phones and computers paired with this server", onBack) {
        section("Paired", "${devices.size}")
        item {
            Card {
                devices.forEach { d ->
                    val id = d["id"].str()
                    val seen = d["last_seen_at"].str()?.let { "Last seen ${Format.ago(it)} ago" } ?: "Never seen"
                    if (id == me) NavRow(d["name"].str() ?: "Device", "This phone", seen, valueColor = c.accent)
                    else NavRow(d["name"].str() ?: "Device", "Unpair", seen, valueColor = c.bad, chevron = false) { revoking = d }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Pair another device", height = 52.dp) {
                    scope.launch { pairLink = runCatching { store.api.pairing()["urls"].arr()?.firstOrNull().str() }.getOrElse { error = it.message; null } }
                }
                Text("Unpairing signs that device out. It needs a new code to connect again.", fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(horizontal = 6.dp))
                error?.let { Text(it, fontSize = 13.sp, color = c.bad, modifier = Modifier.padding(horizontal = 6.dp)) }
            }
        }
    }
    revoking?.let { d ->
        Confirm("Unpair ${d["name"].str()}?", "It will need a new pairing code to connect again.", "Unpair", onDismiss = { revoking = null }) {
            scope.launch { runCatching { store.api.delete("/api/devices/${BenchApi.enc(d["id"].str().orEmpty())}") }; load() }
        }
    }
    pairLink?.let { PairDialog(it) { pairLink = null } }
}

@Composable
fun PairDialog(link: String, onDismiss: () -> Unit) {
    val c = Bench.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface2,
        title = { Text("Pair another device") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                qrBitmap(link)?.let { androidx.compose.foundation.Image(it.asImageBitmapCompat(), "Pairing code", Modifier.size(220.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp)) }
                Text("Scan it with the Bench app, or open the link on the other device. It works once, for 10 minutes.", fontSize = 13.5.sp, color = c.muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

fun android.graphics.Bitmap.asImageBitmapCompat() = androidx.compose.ui.graphics.asImageBitmap(this)

fun qrBitmap(text: String): android.graphics.Bitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
    android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.RGB_565).apply {
        for (x in 0 until 512) for (y in 0 until 512) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}.getOrNull()

// -- Dashboard -------------------------------------------------------------------------------

private fun bytes(b: Double?): String = when {
    b == null -> "—"
    b >= 1e9 -> "%.1f GB".format(b / 1e9)
    b >= 1e6 -> "%.0f MB".format(b / 1e6)
    else -> "%.0f KB".format(b / 1e3)
}

@Composable
fun DashboardScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    var m by remember { mutableStateOf<JsonElement?>(null) }
    var spend by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    LaunchedEffect(Unit) {
        spend = runCatching { store.api.get("/api/usage/models").arr().orEmpty().toList() }.getOrDefault(emptyList())
        while (true) {
            m = runCatching { store.api.get("/api/metrics") }.getOrDefault(m)
            delay(5000)
        }
    }
    val host = m["host"]
    val up = host["uptimeMs"].num()?.let { ms -> val d = (ms / 86_400_000).toInt(); if (d >= 1) "up $d day${if (d == 1) "" else "s"}" else "up ${(ms / 3_600_000).toInt()}h" }
    Page("Dashboard", listOfNotNull(host["hostname"].str(), up).joinToString(" · ").ifEmpty { "Reading the machine…" }, onBack) {
        item("tiles") {
            val total = host["memTotal"].num(); val free = host["memFree"].num()
            val load = host["loadavg"].arr()?.firstOrNull().num()
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("Memory", if (total != null && free != null) "%.1f".format((total - free) / 1e9) else "—", total?.let { "of %.0f GB".format(it / 1e9) }, Modifier.weight(1f))
                    Tile("Load", load?.let { "%.2f".format(it) } ?: "—", host["cpus"].num()?.let { "${it.toInt()} cores" }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile("Disk free", host["diskFree"].num()?.let { "%.0f".format(it / 1e9) } ?: "—", "GB", Modifier.weight(1f))
                    Tile("Spend", spend.sumOf { it["cost"].num() ?: 0.0 }.let { Format.cost(it) }, "all time", Modifier.weight(1f))
                }
            }
        }
        val procs = m["procs"].arr().orEmpty().toList()
        if (procs.isNotEmpty()) {
            val max = procs.maxOf { it["rssKb"].num() ?: 0.0 }.coerceAtLeast(1.0)
            section("What Bench is holding", bytes(m["bench"]["memCurrent"].num()))
            item("procs") {
                Card { procs.take(12).forEach { p -> Custom { Bar(p["label"].str().orEmpty(), "${bytes((p["rssKb"].num() ?: 0.0) * 1024)} · ${"%.0f".format(p["pcpu"].num() ?: 0.0)}% CPU", p["sub"].str(), (p["rssKb"].num() ?: 0.0) / max, if (p["kind"].str() == "claude") c.accent else c.muted) } } }
            }
        }
        val storage = m["storage"]["entries"].arr().orEmpty().toList()
        if (storage.isNotEmpty()) {
            section("Storage")
            item("storage") { Card { storage.forEach { e -> NavRow(e["name"].str().orEmpty(), bytes(e["bytes"].num()), e["note"].str(), chevron = false) } } }
        }
        if (spend.isNotEmpty()) {
            val max = spend.maxOf { it["cost"].num() ?: 0.0 }.coerceAtLeast(0.01)
            section("Spend by model")
            item("spend") { Card { spend.forEach { s -> Custom { Bar(s["model"].str().orEmpty(), Format.cost(s["cost"].num() ?: 0.0), "${s["sessions"].num()?.toInt() ?: 0} sessions", (s["cost"].num() ?: 0.0) / max, c.accent) } } } }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String?, modifier: Modifier) {
    val c = Bench.colors
    Column(modifier.background(c.surface1, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.5.sp, color = c.muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            unit?.let { Text(it, fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}

@Composable
private fun Bar(label: String, value: String, sub: String?, fraction: Double, color: Color) {
    val c = Bench.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                sub?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(value, fontSize = 13.5.sp, color = c.muted, modifier = Modifier.padding(start = 12.dp))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(c.surface3, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0.02f, 1f)).height(6.dp).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

// -- Subagents -------------------------------------------------------------------------------

val TIERS = listOf(
    Triple("architect", "Architect", "Unclear problems, design, hard debugging"),
    Triple("implementer", "Implementer", "Well-specified edits, tests, running the check"),
    Triple("explorer", "Explorer", "Read-only lookups: finding, reading, summarising"),
)

fun routingOptions(providers: List<ProviderInfo>): List<Pair<String, String>> {
    val claude = providers.firstOrNull { it.id.contains("claude") || it.name.contains("Claude") }?.models.orEmpty().filter { it.enabled }
    return listOf("haiku" to "Haiku", "sonnet" to "Sonnet", "opus" to "Opus", "inherit" to "Session's model") + claude.map { it.name to it.label }
}

fun routingLabel(v: String?, providers: List<ProviderInfo>) = when (v) {
    null, "" -> "Not set"
    else -> routingOptions(providers).firstOrNull { it.first == v }?.second ?: v
}

/**
 * Which models Claude's helpers run on, by tier or one for all. `scope` is where it's saved:
 * the defaults, or a project or task (whose fields can follow the level above).
 */
@Composable
fun SubagentsScreen(store: BenchStore, scope: String, onBack: () -> Unit) {
    val c = Bench.colors
    val co = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var routing by remember { mutableStateOf<JsonElement?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    val (kind, id) = scope.split(":").let { it[0] to it.getOrNull(1) }
    val inherits = kind != "default"
    suspend fun load() {
        providers = runCatching { store.api.providers() }.getOrDefault(providers)
        routing = when (kind) {
            "project" -> store.api.get("/api/projects").arr()?.firstOrNull { it["id"].str() == id }?.get("routing")
            "task" -> runCatching { store.api.get("/api/tasks/${BenchApi.enc(id!!)}")["task"]["routing"] }.getOrNull()
            else -> store.api.defaults()["routing"]
        }
    }
    LaunchedEffect(scope) { load() }
    fun save(key: String, value: Any?) = co.launch {
        val body = buildJsonObject {
            putJsonObject("routing") {
                routing.let { r -> (r as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) -> put(k, v) } }
                when (value) { null -> put(key, JsonNull); is Boolean -> put(key, value); else -> put(key, value.toString()) }
            }
        }
        runCatching {
            when (kind) {
                "project" -> store.api.patch("/api/projects/${BenchApi.enc(id!!)}", body)
                "task" -> store.api.patch("/api/tasks/${BenchApi.enc(id!!)}", body)
                else -> store.api.putDefaults(body)
            }
        }
        load()
    }
    val all = routing["all"].str()
    val enabled = (routing["enabled"] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.bool() } ?: true
    Page("Subagents", when (kind) { "project" -> "For this project. Fields left on Default follow Settings."; "task" -> "For this task. Fields left on Default follow the project."; else -> "Which models Claude's helpers run on" }, onBack) {
        item("mode") {
            Box(Modifier.padding(top = 16.dp)) {
                Segmented(listOf("tier" to "By tier", "one" to "One model"), if (all != null) "one" else "tier", { m -> if (m == "tier") save("all", null) else picking = "all" })
            }
        }
        if (all != null) {
            section("All subagents")
            item { Card { NavRow("Model", routingLabel(all, providers), "Every helper runs on this") { picking = "all" } } }
        } else {
            section("By tier")
            item {
                Card {
                    SwitchRow("Route by tier", "Off: helpers run as Claude Code decides", enabled) { on -> save("enabled", on) }
                    TIERS.forEach { (key, label, hint) ->
                        NavRow(label, routingLabel(routing[key].str(), providers).let { if (inherits && routing[key].str() == null) "Default" else it }, hint) { picking = key }
                    }
                }
            }
        }
        item { Text(if (kind == "default") "A project or task can choose its own from its menu." else "Changes apply to new turns.", fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 12.dp)) }
    }
    picking?.let { key ->
        val opts = (if (inherits) listOf("__default" to "Default") else emptyList()) + routingOptions(providers)
        PickDialog(TIERS.firstOrNull { it.first == key }?.second ?: "All subagents", opts, routing[key].str() ?: "__default", onDismiss = { picking = null }) { v ->
            save(key, if (v == "__default") null else v)
        }
    }
}

// -- Usage limit -----------------------------------------------------------------------------

@Composable
fun UsageLimitScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val co = rememberCoroutineScope()
    var defaults by remember { mutableStateOf<JsonElement?>(null) }
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    suspend fun load() { defaults = store.api.defaults(); providers = runCatching { store.api.providers() }.getOrDefault(providers) }
    LaunchedEffect(Unit) { load() }
    val limits = defaults["limits"]
    val fb = limits["fallback"]
    fun put(body: kotlinx.serialization.json.JsonObject) = co.launch { runCatching { store.api.putDefaults(body) }; load() }
    val fbLabel = fb["modelId"].str()?.let { mid -> providers.flatMap { it.models }.firstOrNull { it.id == mid }?.label }
        ?: fb["providerId"].str()?.let { pid -> providers.firstOrNull { it.id == pid }?.name } ?: "Nobody, wait for the limit"
    Page("Usage limit", "When the Claude login's limit stops a session", onBack) {
        section("Waiting")
        item {
            Card {
                SwitchRow("Resume automatically", "Sends “Continue where you left off.” when the limit lifts", limits["autoResume"].bool()) { on ->
                    put(buildJsonObject { putJsonObject("limits") { put("autoResume", on) } })
                }
            }
        }
        section("Instead")
        item { Card { NavRow("Hand the work to", fbLabel, "A new session on another provider picks it up straight away") { picking = true } } }
    }
    if (picking) {
        val opts = listOf("" to "Nobody, wait for the limit") + providers.filter { it.enabled && it.ready && !it.name.contains("Claude login") }.flatMap { p -> p.models.filter { it.enabled }.map { "${p.id}|${it.id}" to "${it.label} · ${p.name}" } }
        PickDialog("Hand the work to", opts, fb["providerId"].str()?.let { "$it|${fb["modelId"].str().orEmpty()}" } ?: "", onDismiss = { picking = false }) { v ->
            put(buildJsonObject {
                putJsonObject("limits") {
                    if (v.isEmpty()) put("fallback", JsonNull) else putJsonObject("fallback") { put("providerId", v.substringBefore('|')); put("modelId", v.substringAfter('|')) }
                }
            })
        }
    }
}
