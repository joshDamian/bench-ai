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
    else -> "your phone to prepare things in apps"
}

/** Opens the page in Android's settings where a share's access is turned on. */
private fun openAccess(c: android.content.Context, what: String) {
    val i = when (what) {
        "notifications" -> Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "overlay" -> Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
        else -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
    }
    runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun accessLabel(what: String) = when (what) {
    "notifications" -> "notification access"
    "calendar" -> "calendar access"
    "contacts" -> "contacts access"
    "sms" -> "permission to send texts"
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
    // Android's answers come back while Bench is in the background: look again on return.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
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
                "Banking and payment apps are never shared, and screens apps protect stay hidden. Replies and texts that ask first come as a notification: send, edit or don't.",
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
}

@Composable
private fun ShareRow(k: Shareable, s: PhoneShare?, missing: String?, onFix: () -> Unit, onClick: () -> Unit) {
    val c = Bench.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp).background(if (s != null) c.surface3 else c.surface1, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(iconFor(k.kind), null, tint = if (s != null) c.text else c.muted, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(k.name, fontSize = 16.sp, color = if (s != null) c.text else c.muted)
            if (missing != null) Text("Needs ${accessLabel(missing)}. Tap to turn it on.", fontSize = 13.5.sp, lineHeight = 19.sp, color = c.warn, modifier = Modifier.clickable(onClick = onFix))
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
    val k = PhoneShares.catalog.firstOrNull { it.kind == a.kind && (a.kind != "messages" || it.name.equals(a.app, true) || it.id.equals(a.app, true)) }
    var access by remember(a.id) { mutableStateOf(a.access ?: k?.accesses?.firstOrNull()?.first) }
    val from = store.state.collectAsState().value.sessions.firstOrNull { it.id == a.sessionId }?.title
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    ModalBottomSheet(onDismissRequest = onDone, containerColor = c.surface2) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            from?.let { Text(it, fontSize = 13.sp, color = c.muted) }
            Text("Bench asks to use ${phoneAskWhat(a)}", fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
            if (a.why.isNotBlank()) Text(a.why, fontSize = 15.sp, lineHeight = 22.sp, color = c.muted)
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
