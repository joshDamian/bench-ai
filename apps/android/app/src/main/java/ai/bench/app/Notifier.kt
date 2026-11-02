package ai.bench.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.bench.app.data.Approval
import ai.bench.app.data.str

object Notifier {
    private const val APPROVALS = "approvals"
    private const val TURNS = "turns"
    const val EXTRA_SESSION = "session_id"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(APPROVALS, "Claude needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Approvals, questions and plans waiting on an answer"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(TURNS, "Finished and stopped", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A session finished its turn, or stopped with an error"
            },
        )
    }

    fun approval(context: Context, a: Approval, sessionTitle: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context,
            a.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SESSION, a.session_id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = when (a.tool) {
            "AskUserQuestion" -> "Claude is asking a question"
            "ExitPlanMode" -> "Claude has a plan"
            "Bash" -> "Run: " + (a.input["command"].str() ?: "a command")
            else -> a.title ?: ai.bench.app.ui.toolName(a.tool)
        }
        val n = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(sessionTitle)
            .setContentText(what)
            .setStyle(NotificationCompat.BigTextStyle().bigText(what))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(a.id.hashCode(), n) }
    }

    /** A turn ended: finished, or stopped with an error. */
    fun turn(context: Context, sessionId: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, sessionId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SESSION, sessionId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, TURNS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify("turn-$sessionId".hashCode(), n) }
    }

    fun clear(context: Context, approvalId: String) = NotificationManagerCompat.from(context).cancel(approvalId.hashCode())
}
