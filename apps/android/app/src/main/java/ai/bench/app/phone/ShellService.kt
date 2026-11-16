package ai.bench.app.phone

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs in Shizuku's process as the shell user (the access a USB debugging connection has), not in
 * the app. It makes the hidden screen, a virtual display apps can run on while the person uses
 * their own, the way scrcpy's "new display" does, and runs the commands that work on it
 * (am start --display, input -d). Shizuku constructs it, with a Context on Shizuku 13.
 */
class ShellService(private val context: Context?) : IShell.Stub() {
    constructor() : this(null)

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var last: ByteArray = ByteArray(0)
    private var used = 0

    init {
        // Shell may call hidden APIs, but this process is still held to the app list; lift it.
        if (Build.VERSION.SDK_INT >= 28) runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("") }
    }

    override fun destroy() {
        close()
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        t.start()
        if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroy()
        t.join(1000)
        return out.toString().trim()
    }

    /** A Context that says it is the shell, which DisplayManager checks against the caller. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getApplicationContext(): Context = this
        @SuppressLint("NewApi")
        override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
    }

    override fun open(width: Int, height: Int, dpi: Int): Int {
        display?.let { return it.display.displayId }
        val ctx = context ?: throw IllegalStateException("Shizuku is too old: update it to version 13 or later")
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(ShellContext(ctx))
        // Public, its own content only, touchable, gone with what is on it when removed; on newer
        // Android also trusted, its own focus and unlocked, so apps run while the phone is locked
        // and typing there never lands in what the person is using.
        val base = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL
        // Own focus keeps typing there out of the person's apps; not stealing the top focus keeps
        // a tap there from taking the person's keyboard away (Android 15+).
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= 35) add(base or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or STEAL_TOP_FOCUS_DISABLED)
            if (Build.VERSION.SDK_INT >= 34) add(base or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS)
            if (Build.VERSION.SDK_INT >= 33) add(base or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED)
            add(base or TRUSTED)
            add(base)
        }
        var made: VirtualDisplay? = null
        var why: Throwable? = null
        for (flags in tries) {
            made = runCatching { dm.createVirtualDisplay("Bench", width, height, dpi, r.surface, flags) }.onFailure { why = it }.getOrNull()
            if (made != null) { used = flags; break }
        }
        if (made == null) { r.close(); throw IllegalStateException("The phone would not make a hidden screen: ${why?.message}") }
        display = made
        reader = r
        noKeyboard(made.display.displayId)
        return made.display.displayId
    }

    /**
     * No keyboard for the hidden screen. Left alone, Android shows the keyboard for a field there
     * on the person's own screen, over what they are doing. Bench types with key events, which
     * need no keyboard. Android 12+: the display's keyboard policy, hide (what scrcpy's
     * --display-ime-policy=hide sets); Android 10 and 11: "don't show the keyboard" for it.
     */
    private fun noKeyboard(id: Int) {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window")
            val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            if (Build.VERSION.SDK_INT >= 31) wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id, IME_HIDE)
            else wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(wm, id, false)
        }
    }

    override fun frame(quality: Int): ByteArray {
        val r = reader ?: return ByteArray(0)
        val img = r.acquireLatestImage() ?: return last
        img.use {
            val plane = it.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * it.width
            val bmp = Bitmap.createBitmap(it.width + rowPadding / pixelStride, it.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val crop = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, it.width, it.height)
            last = ByteArrayOutputStream().also { o -> crop.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), o) }.toByteArray()
        }
        return last
    }

    /** Whether the hidden screen keeps its own focus (1), and leaves the person's alone when touched (2). */
    override fun focus(): Int = (if ((used and OWN_FOCUS) != 0) 1 else 0) or (if ((used and STEAL_TOP_FOCUS_DISABLED) != 0) 2 else 0)

    override fun close() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        display = null
        reader = null
        last = ByteArray(0)
    }

    companion object {
        // DisplayManager's virtual display flags (some hidden).
        const val PUBLIC = 1
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
        // WindowManager.DISPLAY_IME_POLICY_HIDE
        const val IME_HIDE = 2
    }
}
