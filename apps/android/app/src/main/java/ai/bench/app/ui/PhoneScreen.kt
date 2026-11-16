package ai.bench.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ai.bench.app.data.BenchStore
import ai.bench.app.phone.PhoneAsk
import ai.bench.app.phone.PhoneLink
import ai.bench.app.phone.PhoneShare
import ai.bench.app.phone.PhoneShares
import ai.bench.app.phone.Shareable
import ai.bench.app.phone.PhoneHands
import ai.bench.app.phone.ShizukuState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.asImageBitmap
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch

private fun iconFor(kind: String): ImageVector = when (kind) {
    "messages" -> Icons.Outlined.ChatBubbleOutline
    "calendar" -> Icons.Outlined.CalendarMonth
    "contacts" -> Icons.Outlined.Person
    "sms" -> Icons.Outlined.Sms
    else -> Icons.AutoMirrored.Outlined.OpenInNew
}

/** What a share lets Bench do, in a few words: the row's line under its name. */
private fun doesText(k: Shareable, s: PhoneShare?): String = when {
    s == null -> k.sub
    k.kind == "messages" -> if (s.access == "reply") "Read new messages and reply from the notification" else "Read new messages"
    k.kind == "calendar" -> if (s.access == "write") "See and add events" else "See events"
    else -> k.sub
}

private fun ruleText(rule: String) = if (rule == "allow") "Allowed" else "Asks first"

/** "your WhatsApp messages", for an ask's title. */
fun phoneAskWhat(a: PhoneAsk): String = when (a.kind) {
    "messages" -> "your ${a.app ?: "app"} messages"
    "calendar" -> if (a.access == "write") "your calendar, to add events" else "your calendar"
    "contacts" -> "your contacts"
    "sms" -> "your phone to send texts"
    "use" -> "${a.app ?: "an app"} for you, on a hidden screen"
    "notifications" -> "the notifications of the apps it may use"
    else -> "your phone to prepare things in apps"
}

/** Opens the page in Android's settings where a share's access is turned on. */
private fun openAccess(c: android.content.Context, what: String) {
    val i = when (what) {
        "notifications" -> Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "overlay" -> Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
        "shizuku" -> c.packageManager.getLaunchIntentForPackage(PhoneHands.SHIZUKU) ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${PhoneHands.SHIZUKU}"))
        "shizuku-get" -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${PhoneHands.SHIZUKU}"))
        else -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
    }
    runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun accessLabel(what: String) = when (what) {
    "notifications" -> "notification access"
    "calendar" -> "calendar access"
    "contacts" -> "contacts access"
    "sms" -> "permission to send texts"
    "shizuku" -> "Shizuku running"
    else -> "access"
}

/**
 * This phone (the "What this phone shares" board): what Bench may use here, each with its own
 * rule, set and changed on the phone. The first share links the phone to the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { PhoneShares.load(context) }
    val shares by PhoneShares.shares.collectAsState()
    val openNow by PhoneShares.openNow.collectAsState()
    val online by PhoneLink.online.collectAsState()
    var editing by remember { mutableStateOf<Shareable?>(null) }
    var picking by remember { mutableStateOf(false) }
    // Android's answers come back while Bench is in the background: look again on return.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    // Shizuku's answers (allowed, started) also come while Bench is open.
    DisposableEffect(Unit) {
        val allowed = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> tick++ }
        val started = rikka.shizuku.Shizuku.OnBinderReceivedListener { tick++ }
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(allowed); rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(started) }
        onDispose { runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(allowed); rikka.shizuku.Shizuku.removeBinderReceivedListener(started) } }
    }
    fun needs(kind: String): String? { tick; return PhoneShares.missing(context, kind) }
    fun turnOn(kind: String) {
        val p = PhoneShares.permissionsFor(kind)
        if (p.isNotEmpty()) ask.launch(p) else needs(kind)?.let { openAccess(context, it) }
    }
    val linked = PhoneLink.linked(context)

    Page("This phone", "Bench can use only what is shared here. Change it or stop it any time.", onBack) {
        item("status") {
            Row(Modifier.padding(start = 6.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(if (online) c.good else c.muted, CircleShape))
                Text(
                    when { online -> "Linked to your server"; linked -> "Linking…"; else -> "Not linked yet. Share something to link this phone." },
                    fontSize = 14.sp, color = c.muted,
                )
            }
        }
        fun group(title: String, ids: List<String>, extra: (@Composable () -> Unit)? = null) {
            item("g-$title") {
                Column {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 30.dp, bottom = 8.dp))
                    PhoneShares.catalog.filter { it.id in ids }.forEachIndexed { i, k ->
                        if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 62.dp))
                        val s = shares.firstOrNull { it.id == k.id }
                        ShareRow(k, s, if (s != null) needs(k.kind) else null, onFix = { turnOn(k.kind) }) { editing = k }
                    }
                    extra?.invoke()
                }
            }
        }
        // -- Apps Bench may use, on a hidden screen, through Shizuku.
        item("use") {
            val st = remember(tick) { PhoneHands.state(context) }
            val apps = shares.filter { it.kind == "use" }
            Column {
                Text("Use apps for you", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 30.dp, bottom = 10.dp))
                ShizukuCard(st, onGet = { openAccess(context, "shizuku-get") }, onStart = { openAccess(context, "shizuku") }, onAllow = { PhoneHands.requestAllow() })
                apps.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 62.dp))
                    val k = Shareable(a.id, "use", a.name, "Bench opens it on a hidden screen and uses it for you", a.path)
                    ShareRow(k, a, if (st == ShizukuState.Ready) null else "shizuku", onFix = {}) { editing = k }
                }
                if (apps.isNotEmpty()) HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 62.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp)).clickable { picking = true }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = c.accent, modifier = Modifier.size(22.dp)) }
                    Text("Add an app", fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.accent)
                }
            }
        }
        group("Notifications", listOf("notifications"))
        group("Messages", listOf("whatsapp", "whatsapp-business", "telegram"))
        group("Calendar and people", listOf("calendar", "contacts"))
        group("Texts", listOf("sms"))
        group("Open apps for you", listOf("handover")) {
            HorizontalDivider(color = c.line, modifier = Modifier.padding(start = 6.dp))
            val overlay = android.provider.Settings.canDrawOverlays(context).also { tick }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                    PhoneShares.setOpenNow(context, !openNow)
                    if (!openNow && !overlay) openAccess(context, "overlay")
                }.padding(horizontal = 6.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Open straight away", fontSize = 16.sp)
                    Text(
                        when {
                            !openNow -> "Off: each one comes as a notification you tap."
                            overlay -> "Opens in its app as soon as it is ready."
                            else -> "Needs “Display over other apps”. Tap to allow it."
                        },
                        fontSize = 13.5.sp, lineHeight = 19.sp, color = if (openNow && !overlay) c.warn else c.muted,
                    )
                }
                Switch(openNow, null, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
            }
        }
        item("note") {
            Text(
                "Bench always asks before it pays, books, sends or deletes in an app. Banking and payment apps can't be added, and screens apps protect stay hidden. Replies and texts that ask first come as a notification: send, edit or don't.",
                fontSize = 13.5.sp, lineHeight = 20.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 28.dp),
            )
        }
        if (linked) item("unlink") {
            Text(
                "Unlink this phone", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.bad,
                modifier = Modifier.padding(top = 24.dp).clip(RoundedCornerShape(12.dp)).clickable {
                    scope.launch { PhoneLink.unlink(); shares.forEach { PhoneShares.remove(context, it.id) } }
                }.padding(horizontal = 6.dp, vertical = 12.dp),
            )
        }
    }

    editing?.let { k ->
        ShareSheet(k, shares.firstOrNull { it.id == k.id }, onDismiss = { editing = null }, onSave = { s ->
            PhoneShares.set(context, s)
            editing = null
            if (PhoneShares.missing(context, s.kind) != null) turnOn(s.kind)
        }, onStop = { PhoneShares.remove(context, k.id); editing = null })
    }
    if (picking) AppPicker(shares.filter { it.kind == "use" }.mapNotNull { it.path }.toSet(), onDismiss = { picking = false }) { pkg, label ->
        picking = false
        editing = Shareable("use:$pkg", "use", label, "Bench opens it on a hidden screen and uses it for you", pkg)
    }
}

/** Shizuku: what is needed, one step at a time, or that it is running. */
@Composable
private fun ShizukuCard(st: ShizukuState, onGet: () -> Unit, onStart: () -> Unit, onAllow: () -> Unit) {
    val c = Bench.colors
    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).background(c.surface1, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (st == ShizukuState.Ready) c.good else c.warn, CircleShape))
            Text(
                when (st) {
                    ShizukuState.Ready -> "Shizuku is running"
                    ShizukuState.NotInstalled -> "Needs Shizuku"
                    ShizukuState.NotRunning -> "Shizuku is not running"
                    ShizukuState.NeedsAllow -> "Shizuku is running"
                },
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
        }
        Text(
            when (st) {
                ShizukuState.Ready -> "Bench opens these apps on a hidden screen, so you keep using your phone. After a restart, start Shizuku again."
                ShizukuState.NotInstalled -> "A free app that lets apps you choose act on your phone. Bench uses it to work in apps on a hidden screen while you keep using yours."
                ShizukuState.NotRunning -> "In Shizuku, start it with Wireless debugging. It stops when the phone restarts."
                ShizukuState.NeedsAllow -> "Allow Bench in Shizuku. It asks once."
            },
            fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted,
        )
        when (st) {
            ShizukuState.NotInstalled -> BigButton("Get Shizuku", height = 44.dp, size = 14.5.sp, fill = false, modifier = Modifier.padding(top = 6.dp), onClick = onGet)
            ShizukuState.NotRunning -> BigButton("Open Shizuku", height = 44.dp, size = 14.5.sp, fill = false, modifier = Modifier.padding(top = 6.dp), onClick = onStart)
            ShizukuState.NeedsAllow -> BigButton("Allow Bench", primary = true, height = 44.dp, size = 14.5.sp, fill = false, modifier = Modifier.padding(top = 6.dp), onClick = onAllow)
            ShizukuState.Ready -> {}
        }
    }
}

/** The apps on the phone that can be shared to use (not money apps, not the phone's own controls). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(already: Set<String>, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val c = Bench.colors
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { (pkg, label) -> pkg !in already && !PhoneShares.blocked(pkg, label) }
            .sortedBy { it.second.lowercase() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface2) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 20.dp)) {
            Text("Add an app", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Banking and payment apps can't be added.", fontSize = 14.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            androidx.compose.foundation.text.BasicTextField(
                query, { query = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search apps", fontSize = 16.sp, color = c.muted); inner() } },
            )
            androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(top = 10.dp).heightIn(max = 460.dp)) {
                items(apps.filter { query.isBlank() || it.second.contains(query, true) }.size) { i ->
                    val (pkg, label) = apps.filter { query.isBlank() || it.second.contains(query, true) }[i]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp)).clickable { onPick(pkg, label) }.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AppIcon(pkg)
                        Text(label, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/** An app's own icon, 40dp, rounded like the other rows. */
@Composable
fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val img = remember(pkg) { runCatching { context.packageManager.getApplicationIcon(pkg).let { d ->
        android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b)) }
    }.asImageBitmap() }.getOrNull() }
    if (img != null) androidx.compose.foundation.Image(img, null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
    else Box(Modifier.size(40.dp).background(Bench.colors.surface3, RoundedCornerShape(12.dp)))
}

@Composable
private fun ShareRow(k: Shareable, s: PhoneShare?, missing: String?, onFix: () -> Unit, onClick: () -> Unit) {
    val c = Bench.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (k.kind == "use" && k.path != null) AppIcon(k.path)
        else Box(Modifier.size(40.dp).background(if (s != null) c.surface3 else c.surface1, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(iconFor(k.kind), null, tint = if (s != null) c.text else c.muted, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(k.name, fontSize = 16.sp, color = if (s != null) c.text else c.muted)
            if (missing == "shizuku") Text("Needs Shizuku running.", fontSize = 13.5.sp, lineHeight = 19.sp, color = c.warn)
            else if (missing != null) Text("Needs ${accessLabel(missing)}. Tap to turn it on.", fontSize = 13.5.sp, lineHeight = 19.sp, color = c.warn, modifier = Modifier.clickable(onClick = onFix))
            else Text(doesText(k, s), fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted)
        }
        Text(
            s?.let { ruleText(it.rule) } ?: "Off", fontSize = 13.5.sp, fontWeight = if (s != null) FontWeight.Medium else FontWeight.Normal,
            color = if (s != null) c.text else c.muted,
            modifier = Modifier.background(if (s != null) c.surface3 else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** Sharing one thing: what it may do, and whether each use asks first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(k: Shareable, current: PhoneShare?, onDismiss: () -> Unit, onSave: (PhoneShare) -> Unit, onStop: () -> Unit) {
    val c = Bench.colors
    var access by remember { mutableStateOf(current?.access ?: k.accesses.firstOrNull()?.first) }
    // Anything that acts for you asks first until you say otherwise.
    var rule by remember { mutableStateOf(current?.rule ?: "ask") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface2) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(k.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(k.sub, fontSize = 14.5.sp, lineHeight = 21.sp, color = c.muted)
            if (k.accesses.isNotEmpty()) {
                Text("What Bench can do", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
                Segmented(k.accesses, access ?: k.accesses.first().first, { access = it }, height = 48.dp)
            }
            Text("Each time", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
            Segmented(listOf("ask" to "Asks first", "allow" to "Allowed"), rule, { rule = it }, height = 48.dp)
            Text(
                if (rule == "ask") "You approve each use from a notification." else "Bench uses it without asking. You can still see what it did in Activity.",
                fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(if (current == null) "Share" else "Save", Modifier.weight(1f), primary = true) {
                    onSave(PhoneShare(k.id, k.kind, k.name, k.path, access, rule))
                }
                if (current != null) BigButton("Stop sharing", danger = true, fill = false, onClick = onStop)
            }
        }
    }
}

/**
 * A session asked this phone for more (the "A run asks the phone for more" board): what and
 * why, the access it wants, and Allow or Not now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneAskSheet(store: BenchStore, a: PhoneAsk, onDone: () -> Unit) {
    val c = Bench.colors
    val context = LocalContext.current
    val k = if (a.kind == "use") remember(a.id) {
        PhoneLink.findApp(context, a.app.orEmpty())?.takeIf { !PhoneShares.blocked(it.first, it.second) }?.let { Shareable("use:${it.first}", "use", it.second, "", it.first) }
    } else PhoneShares.catalog.firstOrNull { it.kind == a.kind && (a.kind != "messages" || it.name.equals(a.app, true) || it.id.equals(a.app, true)) }
    var access by remember(a.id) { mutableStateOf(a.access ?: k?.accesses?.firstOrNull()?.first) }
    val from = store.state.collectAsState().value.sessions.firstOrNull { it.id == a.sessionId }?.title
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    ModalBottomSheet(onDismissRequest = onDone, containerColor = c.surface2) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            from?.let { Text(it, fontSize = 13.sp, color = c.muted) }
            Text("Bench asks to use ${phoneAskWhat(a)}", fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
            if (a.why.isNotBlank()) Text(a.why, fontSize = 15.sp, lineHeight = 22.sp, color = c.muted)
            if (k == null) Text("That app isn't on this phone, or it can't be shared.", fontSize = 14.sp, lineHeight = 20.sp, color = c.warn)
            if (k != null && k.accesses.isNotEmpty()) {
                Box(Modifier.padding(top = 14.dp)) { Segmented(k.accesses, access ?: k.accesses.first().first, { access = it }, height = 48.dp) }
            }
            val missing = k?.let { PhoneShares.missing(context, it.kind) }
            Text(
                listOfNotNull(
                    "Each use asks you first; you can change that under This phone.",
                    missing?.let { "Android will then ask you for ${accessLabel(it)}." },
                ).joinToString(" "),
                fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Allow", Modifier.weight(1f), primary = true, enabled = k != null) {
                    PhoneLink.answer(context, a, true, access)
                    if (k != null && missing != null) {
                        val p = PhoneShares.permissionsFor(k.kind)
                        if (p.isEmpty()) openAccess(context, missing) else permit.launch(p)
                    }
                    onDone()
                }
                BigButton("Not now", fill = false) { PhoneLink.answer(context, a, false); onDone() }
            }
        }
    }
}

/**
 * Watching the hidden screen (the "Watching it work" board): the app as Bench sees it, live,
 * with Take over (the app moves to your screen) and Stop.
 */
@Composable
fun PhoneWatch(store: BenchStore, onClose: () -> Unit) {
    val c = Bench.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val open by PhoneHands.open.collectAsState()
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    androidx.compose.runtime.LaunchedEffect(open?.pkg) {
        while (open != null) {
            val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PhoneHands.peek(context) }
            b?.let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { frame = it.asImageBitmap() } }
            kotlinx.coroutines.delay(700)
        }
    }
    val mins = open?.let { ((System.currentTimeMillis() - it.since) / 60_000).toInt() }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.text) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = c.text) }
                Column(Modifier.weight(1f)) {
                    Text(open?.name ?: "Hidden screen", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (open == null) "Nothing open" else "Bench is using it · ${if (mins == 0) "just now" else "$mins min"}", fontSize = 13.sp, color = c.muted)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                val f = frame
                if (open == null) Text("Bench is not using an app now.", fontSize = 15.sp, color = c.muted)
                else if (f == null) Text("Waiting for the screen…", fontSize = 15.sp, color = c.muted)
                else androidx.compose.foundation.Image(
                    f, "The hidden screen",
                    Modifier.clip(RoundedCornerShape(24.dp)).border(6.dp, c.surface3, RoundedCornerShape(24.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
            if (open != null) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Take over", Modifier.weight(1f)) {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { PhoneHands.takeOver(context) }
                    onClose()
                }
                BigButton("Stop", fill = false) {
                    val sid = open?.sessionId
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        PhoneHands.done(context)
                        if (sid != null) runCatching { store.api.interrupt(sid) }
                    }
                    onClose()
                }
            }
        }
    }
}
