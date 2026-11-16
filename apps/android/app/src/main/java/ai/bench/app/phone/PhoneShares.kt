package ai.bench.app.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One thing this phone shares with Bench. Kinds: "messages" (an app's messages, read from its
 * notifications; access "read" or "reply"), "calendar" ("read" or "write"), "contacts", "sms",
 * "handover" (prepare something in its app for the person to finish). Rule: "allow" runs at
 * once, "ask" puts each use to the person first.
 */
@Serializable
data class PhoneShare(
    val id: String,
    val kind: String,
    val name: String,
    val path: String? = null,
    val access: String? = null,
    val rule: String = "ask",
)

/** What can be shared, as the This phone screen lists it. */
data class Shareable(val id: String, val kind: String, val name: String, val sub: String, val path: String? = null, val accesses: List<Pair<String, String>> = emptyList())

object PhoneShares {
    val catalog = listOf(
        Shareable("whatsapp", "messages", "WhatsApp", "New messages, and replies from the notification", "com.whatsapp", listOf("read" to "Read only", "reply" to "Read and reply")),
        Shareable("whatsapp-business", "messages", "WhatsApp Business", "New messages, and replies from the notification", "com.whatsapp.w4b", listOf("read" to "Read only", "reply" to "Read and reply")),
        Shareable("telegram", "messages", "Telegram", "New messages, and replies from the notification", "org.telegram.messenger", listOf("read" to "Read only", "reply" to "Read and reply")),
        Shareable("calendar", "calendar", "Calendar", "Your events", accesses = listOf("read" to "Read only", "write" to "Read and add")),
        Shareable("contacts", "contacts", "Contacts", "Look up a name or number"),
        Shareable("sms", "sms", "Texts", "Send an SMS"),
        Shareable("handover", "handover", "Prepare and hand over", "A message, event or route, ready in its app. You finish it."),
        Shareable("notifications", "notifications", "Read notifications", "From the apps Bench may use: a driver arriving, a seller replying"),
    )

    /**
     * Apps that can't be shared to use: money moves in them, or they control the phone. Matched
     * on the package and the name the person sees.
     */
    private val blockedWords = listOf("bank", "pay", "wallet", "money", "finance", "loan", "invest", "crypto", "binance", "bybit", "opay", "palmpay", "moniepoint", "kuda", "gtworld", "zenith", "firstbank", "uba", "fcmb", "sterling", "wema", "fidelity", "stanbic", "ecobank", "paystack", "flutterwave", "chipper", "cashapp", "paypal", "revolut", "wise", "trust", "metamask", "phantom", "coinbase")
    private val blockedApps = setOf("com.android.settings", "com.android.vending", PhoneHands.SHIZUKU, "ai.bench.app", "com.google.android.apps.authenticator2", "com.android.chrome")
    fun blocked(pkg: String, label: String): Boolean {
        if (pkg in blockedApps) return true
        val words = (pkg + " " + label).lowercase()
        return blockedWords.any { w -> Regex("""(^|[^a-z])$w""").containsMatchIn(words) }
    }

    /** An app shared to use: one share per app. */
    fun useShare(pkg: String, name: String, rule: String) = PhoneShare("use:$pkg", "use", name, pkg, null, rule)

    /** The apps whose notifications are read, by package. */
    val messageApps = catalog.filter { it.kind == "messages" }.associate { it.path!! to it.name }

    private val json = Json { ignoreUnknownKeys = true }
    private val _shares = MutableStateFlow<List<PhoneShare>>(emptyList())
    val shares: StateFlow<List<PhoneShare>> = _shares.asStateFlow()
    private val _openNow = MutableStateFlow(false)
    /** Hand-overs open straight away (needs "Display over other apps"), not as a notification. */
    val openNow: StateFlow<Boolean> = _openNow.asStateFlow()
    private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences("phone", 0)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _shares.value = runCatching { json.decodeFromString<List<PhoneShare>>(prefs(c).getString("shares", "[]")!!) }.getOrDefault(emptyList())
        _openNow.value = prefs(c).getBoolean("open_now", false)
    }

    fun deviceId(c: Context): String = prefs(c).getString("id", null) ?: java.util.UUID.randomUUID().toString().also { prefs(c).edit().putString("id", it).apply() }

    fun set(c: Context, share: PhoneShare) = save(c, _shares.value.filter { it.id != share.id } + share)
    fun remove(c: Context, id: String) = save(c, _shares.value.filter { it.id != id })
    fun setOpenNow(c: Context, on: Boolean) { _openNow.value = on; prefs(c).edit().putBoolean("open_now", on).apply() }

    private fun save(c: Context, list: List<PhoneShare>) {
        val ordered = catalog.mapNotNull { k -> list.firstOrNull { it.id == k.id } } + list.filter { it.kind == "use" }.sortedBy { it.name.lowercase() }
        _shares.value = ordered
        prefs(c).edit().putString("shares", json.encodeToString(ordered)).apply()
        PhoneLink.sharesChanged()
    }

    fun find(kind: String, id: String?): PhoneShare? =
        _shares.value.firstOrNull { it.kind == kind && (id.isNullOrBlank() || it.id == id) }

    /** What Android must allow before a share works, or null when it is already allowed. */
    fun missing(c: Context, kind: String): String? = when (kind) {
        "messages" -> if (c.packageName in NotificationManagerCompat.getEnabledListenerPackages(c)) null else "notifications"
        "calendar" -> if (granted(c, Manifest.permission.READ_CALENDAR) && granted(c, Manifest.permission.WRITE_CALENDAR)) null else "calendar"
        "contacts" -> if (granted(c, Manifest.permission.READ_CONTACTS)) null else "contacts"
        "sms" -> if (granted(c, Manifest.permission.SEND_SMS)) null else "sms"
        "use" -> if (PhoneHands.state(c) == ShizukuState.Ready) null else "shizuku"
        "notifications" -> if (c.packageName in NotificationManagerCompat.getEnabledListenerPackages(c) || PhoneHands.state(c) == ShizukuState.Ready) null else "notifications"
        else -> null
    }

    fun permissionsFor(kind: String): Array<String> = when (kind) {
        "calendar" -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
        "sms" -> arrayOf(Manifest.permission.SEND_SMS)
        else -> emptyArray()
    }

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
}
