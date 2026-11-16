package ai.bench.app.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Where Shizuku is, as the This phone screen shows it. */
enum class ShizukuState { NotInstalled, NotRunning, NeedsAllow, Ready }

/** The app open on the hidden screen. */
data class OpenApp(val pkg: String, val name: String, val sessionId: String?, val since: Long)

/**
 * Bench's hands on the phone: through Shizuku it opens a shared app on a hidden screen, looks
 * at it, and taps, types and swipes there, while the person keeps using their own screen. Every
 * action first checks that the hidden screen still shows a shared app, so a tap can never land
 * in something that was not shared (a bank, the settings).
 */
object PhoneHands {
    const val SHIZUKU = "moe.shizuku.privileged.api"
    // The hidden screen: phone-shaped, small enough that a screenshot is cheap to send.
    private const val W = 720
    private const val H = 1560
    private const val DPI = 320

    private var shell: IShell? = null
    private var displayId: Int = -1
    private val _open = MutableStateFlow<OpenApp?>(null)
    val open: StateFlow<OpenApp?> = _open.asStateFlow()
    private var lastAction = 0L

    fun state(c: Context): ShizukuState {
        val installed = runCatching { c.packageManager.getPackageInfo(SHIZUKU, 0); true }.getOrDefault(false)
        return when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> if (installed) ShizukuState.NotRunning else ShizukuState.NotInstalled
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) -> ShizukuState.Ready
            else -> ShizukuState.NeedsAllow
        }
    }

    fun requestAllow() { runCatching { Shizuku.requestPermission(7) } }

    private fun args(c: Context) = Shizuku.UserServiceArgs(ComponentName(c.packageName, ShellService::class.java.name))
        .daemon(false).processNameSuffix("hands").tag("bench-hands").version(1)

    /** The shell service, bound on first use. */
    private fun bound(c: Context): IShell {
        shell?.let { if (it.asBinder().pingBinder()) return it }
        if (state(c) != ShizukuState.Ready) throw PhoneActions.Refused(
            when (state(c)) {
                ShizukuState.NotInstalled -> "Shizuku is not installed on the phone. The person sets it up under This phone."
                ShizukuState.NotRunning -> "Shizuku is not running on the phone (it stops when the phone restarts). The person starts it again in Shizuku."
                else -> "Bench is not allowed in Shizuku yet. The person allows it under This phone."
            },
        )
        val s = runBlocking {
            withTimeout(15_000) {
                suspendCancellableCoroutine { k ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, b: IBinder?) { if (k.isActive) k.resume(b?.let { IShell.Stub.asInterface(it) }) }
                        override fun onServiceDisconnected(name: ComponentName?) { shell = null; displayId = -1 }
                    }
                    Shizuku.bindUserService(args(c), conn)
                }
            }
        } ?: throw PhoneActions.Refused("Shizuku did not start Bench's helper")
        shell = s
        return s
    }

    private fun sh(c: Context, cmd: String) = bound(c).exec(cmd)

    /** Packages shared to use. */
    private fun shared() = PhoneShares.shares.value.filter { it.kind == "use" }.mapNotNull { it.path }.toSet()

    /** The package on top of the hidden screen, or null when nothing is. */
    private fun top(c: Context): String? {
        if (displayId < 0) return null
        val dump = sh(c, "dumpsys activity activities")
        val at = dump.indexOf("Display #$displayId")
        if (at < 0) return null
        val section = dump.substring(at).let { s -> s.indexOf("\nDisplay #", 1).let { e -> if (e > 0) s.substring(0, e) else s } }
        val line = section.lineSequence().firstOrNull { "ResumedActivity" in it || "topResumedActivity" in it } ?: return null
        return Regex("""u\d+ ([\w.]+)/""").find(line)?.groupValues?.get(1)
    }

    // What may show on the hidden screen besides a shared app: the system's own sheets.
    private val system = setOf("android", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.systemui", "com.google.android.gms")

    private fun guard(c: Context) {
        val app = _open.value ?: throw PhoneActions.Refused("No app is open on the hidden screen. Use phone_open first.")
        val now = top(c) ?: throw PhoneActions.Refused("${app.name} closed on the hidden screen. Use phone_open to open it again.")
        if (now != app.pkg && now !in shared() && now !in system) {
            throw PhoneActions.Refused("The hidden screen now shows $now, which is not shared. Use phone_key with back to return to ${app.name}, or phone_done.")
        }
        lastAction = System.currentTimeMillis()
    }

    /** The screen, for a tool result: a JPEG in base64, and its size. */
    fun screen(c: Context): Pair<String, String> {
        Thread.sleep(150)
        val bytes = bound(c).frame(70)
        if (bytes.isEmpty()) throw PhoneActions.Refused("The hidden screen showed nothing yet; try phone_screen again")
        return "Hidden screen, ${W}×$H pixels." to Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** A frame for the person watching (Watch), or null. */
    fun peek(c: Context): ByteArray? = runCatching { shell?.frame(60)?.takeIf { it.isNotEmpty() } }.getOrNull()

    fun open(c: Context, share: PhoneShare, sessionId: String?): Pair<String, String> {
        val pkg = share.path ?: throw PhoneActions.Refused("That share names no app")
        val launch = c.packageManager.getLaunchIntentForPackage(pkg)?.component ?: throw PhoneActions.Refused("${share.name} is not installed on the phone")
        _open.value?.takeIf { it.pkg != pkg }?.let { done(c) }
        displayId = bound(c).open(W, H, DPI)
        val out = sh(c, "am start --display $displayId -n ${launch.flattenToShortString()} -f 0x10200000")
        if ("Error" in out) throw PhoneActions.Refused("${share.name} would not open on the hidden screen: ${out.lineSequence().firstOrNull { "Error" in it }}")
        _open.value = OpenApp(pkg, share.name, sessionId, System.currentTimeMillis())
        lastAction = System.currentTimeMillis()
        ai.bench.app.Notifier.using(c, share.name)
        Thread.sleep(1800)
        return screen(c)
    }

    fun tap(c: Context, x: Int, y: Int): Pair<String, String> {
        guard(c); sh(c, "input -d $displayId tap ${x.coerceIn(0, W - 1)} ${y.coerceIn(0, H - 1)}"); Thread.sleep(900); return screen(c)
    }

    fun swipe(c: Context, x1: Int, y1: Int, x2: Int, y2: Int): Pair<String, String> {
        guard(c); sh(c, "input -d $displayId swipe $x1 $y1 $x2 $y2 350"); Thread.sleep(900); return screen(c)
    }

    /**
     * Types text. `input text` takes only plain ASCII; anything else goes through the clipboard
     * of the hidden screen's app, pasted with the paste key.
     */
    fun type(c: Context, text: String, enter: Boolean): Pair<String, String> {
        guard(c)
        if (text.all { it.code in 32..126 }) {
            val arg = text.replace("\\", "\\\\").replace("'", "'\\''").replace(" ", "%s")
            sh(c, "input -d $displayId text '$arg'")
        } else {
            val cm = c.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Bench", text))
            sh(c, "input -d $displayId keyevent 279") // KEYCODE_PASTE
        }
        if (enter) sh(c, "input -d $displayId keyevent 66")
        Thread.sleep(700)
        return screen(c)
    }

    fun key(c: Context, key: String): Pair<String, String> {
        if (_open.value == null) throw PhoneActions.Refused("No app is open on the hidden screen")
        val code = when (key) { "back" -> 4; "enter" -> 66; "search" -> 84; else -> throw PhoneActions.Refused("Press back, enter or search") }
        if (key != "back") guard(c)
        sh(c, "input -d $displayId keyevent $code")
        Thread.sleep(800)
        return screen(c)
    }

    /** Closes the hidden screen and what is on it. */
    fun done(c: Context) {
        runCatching { shell?.close() }
        displayId = -1
        _open.value = null
        ai.bench.app.Notifier.stopUsing(c)
    }

    /**
     * The person takes over: the app leaves the hidden screen and opens on theirs. What Bench
     * had filled in stays where the app keeps it; most apps restore their last screen.
     */
    fun takeOver(c: Context) {
        val pkg = _open.value?.pkg ?: return
        done(c)
        c.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Closes a hidden screen left idle for ten minutes. */
    fun idle(c: Context) {
        if (_open.value != null && System.currentTimeMillis() - lastAction > 10 * 60_000) done(c)
    }

    /** Notifications through Shizuku, when notification access is off: app, title and text. */
    fun notifications(c: Context, pkgs: Set<String>): List<PhoneMessage> {
        val dump = sh(c, "dumpsys notification --noredact")
        val out = mutableListOf<PhoneMessage>()
        dump.split("NotificationRecord(").drop(1).forEach { rec ->
            val pkg = Regex("""pkg=([\w.]+)""").find(rec)?.groupValues?.get(1) ?: return@forEach
            if (pkg !in pkgs) return@forEach
            fun extra(k: String) = Regex("""$k=(?:String|SpannableString) \((.*)\)""").find(rec)?.groupValues?.get(1)
            val title = extra("android.title") ?: return@forEach
            val text = extra("android.bigText") ?: extra("android.text") ?: ""
            val at = Regex("""when=(\d+)""").find(rec)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            out += PhoneMessage(PhoneShares.shares.value.firstOrNull { it.path == pkg }?.name ?: pkg, title, title, text, at)
        }
        return out.sortedBy { it.at }
    }
}
