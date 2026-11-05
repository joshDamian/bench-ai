package ai.bench.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import ai.bench.app.data.BenchState

/** What the widget and the status tile show, worked out once from the store's state. */
data class Glance(val needs: Int, val working: Int, val latest: String?) {
    val needsLine get() = if (needs == 0) "Nothing needs you" else "$needs need${if (needs == 1) "s" else ""} you"
    val workingLine get() = when (working) {
        0 -> "Idle"
        1 -> "Working · ${latest ?: "1 session"}"
        else -> "$working sessions working"
    }

    companion object {
        fun of(st: BenchState): Glance {
            val running = st.sessions.filter { it.status == "running" && it.archived_at == null }.sortedByDescending { it.updated_at }
            return Glance(st.approvals.size, running.size, running.firstOrNull()?.title)
        }
    }
}

object SystemReach {
    const val EXTRA_NEW = "new_session"
    private var last: Glance? = null

    fun openApp(context: Context, newSession: Boolean = false, code: Int = 0): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_NEW, newSession).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Called on every state change; only touches the widget and tile when the summary changes. */
    fun publish(context: Context, g: Glance) {
        if (g == last) return
        last = g
        BenchWidget.updateAll(context, g)
        runCatching { TileService.requestListeningState(context, ComponentName(context, StatusTile::class.java)) }
    }

    fun current(): Glance = last ?: BenchApp.instance.store.value?.state?.value?.let { Glance.of(it) } ?: Glance(0, 0, null)
}

/** The home-screen widget. */
class BenchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val g = SystemReach.current()
        ids.forEach { manager.updateAppWidget(it, views(context, g)) }
    }

    companion object {
        fun views(context: Context, g: Glance) = RemoteViews(context.packageName, R.layout.widget_bench).apply {
            setTextViewText(R.id.widget_needs, g.needsLine)
            setTextColor(R.id.widget_needs, context.getColor(if (g.needs > 0) R.color.widget_accent else R.color.widget_text))
            setTextViewText(R.id.widget_working, g.workingLine)
            setOnClickPendingIntent(R.id.widget_root, SystemReach.openApp(context, code = 10))
            setOnClickPendingIntent(R.id.widget_new, SystemReach.openApp(context, newSession = true, code = 11))
        }

        fun updateAll(context: Context, g: Glance) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, BenchWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { m.updateAppWidget(it, views(context, g)) }
        }
    }
}

private fun TileService.launch(newSession: Boolean) {
    val pi = SystemReach.openApp(this, newSession, code = if (newSession) 21 else 20)
    if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pi)
    else @Suppress("DEPRECATION") startActivityAndCollapse(
        Intent(this, MainActivity::class.java).putExtra(SystemReach.EXTRA_NEW, newSession).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Quick Settings: start a new session from anywhere. */
class NewSessionTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "New session"; updateTile() }
    }
    override fun onClick() = launch(newSession = true)
}

/** Quick Settings: Bench at a glance — lit while Claude is working or something needs you. */
class StatusTile : TileService() {
    override fun onStartListening() {
        val g = SystemReach.current()
        qsTile?.apply {
            label = "Bench"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (g.needs > 0) g.needsLine else g.workingLine
            state = if (g.needs > 0 || g.working > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
    override fun onClick() = launch(newSession = false)
}
