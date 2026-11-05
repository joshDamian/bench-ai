package ai.bench.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import ai.bench.app.data.Approval
import ai.bench.app.data.Session
import ai.bench.app.data.arr
import ai.bench.app.data.get
import ai.bench.app.data.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object Notifier {
    private const val APPROVALS = "approvals"
    private const val TURNS = "turns"
    private const val RUNNING = "running"
    const val EXTRA_SESSION = "session_id"
    const val ACTION = "ai.bench.app.ACTION"
    const val REPLY_KEY = "reply"

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
        nm.createNotificationChannel(
            NotificationChannel(RUNNING, "Working now", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A live card for each session Claude is working in, with a Stop button"
                setShowBadge(false)
            },
        )
    }

    private fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openSession(context: Context, sessionId: String, code: Int) = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_SESSION, sessionId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A broadcast to [ActionReceiver]; mutable when it carries a typed reply. */
    private fun action(context: Context, kind: String, id: String, code: Int, mutable: Boolean = false): PendingIntent {
        val i = Intent(context, ActionReceiver::class.java).setAction(ACTION).putExtra("kind", kind).putExtra("id", id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, code, i, flags)
    }

    /**
     * Claude is waiting. A permission can be allowed or denied from the notification; a
     * question takes a typed answer; a plan can be approved. Anything else opens the session.
     */
    fun approval(context: Context, a: Approval, sessionTitle: String) {
        if (!allowed(context)) return
        val code = a.id.hashCode()
        val what = when (a.tool) {
            "AskUserQuestion" -> a.input["questions"].arr()?.firstOrNull()?.let { it["question"].str() } ?: "Claude is asking a question"
            "ExitPlanMode" -> "Claude has a plan"
            "Bash" -> "Run: " + (a.input["command"].str() ?: "a command")
            else -> a.title ?: ai.bench.app.ui.toolName(a.tool)
        }
        val b = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(sessionTitle)
            .setContentText(what)
            .setStyle(NotificationCompat.BigTextStyle().bigText(what))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openSession(context, a.session_id, code))
            .setAutoCancel(true)
        when (a.tool) {
            "AskUserQuestion" -> {
                val input = RemoteInput.Builder(REPLY_KEY).setLabel("Your answer").build()
                b.addAction(
                    NotificationCompat.Action.Builder(0, "Answer", action(context, "answer", a.id, code + 1, mutable = true))
                        .addRemoteInput(input).setAllowGeneratedReplies(false).build(),
                )
            }
            "ExitPlanMode" -> b.addAction(0, "Approve", action(context, "plan", a.id, code + 1))
            else -> {
                b.addAction(0, "Deny", action(context, "deny", a.id, code + 2))
                b.addAction(0, "Allow", action(context, "allow", a.id, code + 1))
            }
        }
        runCatching { NotificationManagerCompat.from(context).notify(code, b.build()) }
    }

    /** A turn ended: finished, or stopped with an error. */
    fun turn(context: Context, sessionId: String, title: String, text: String) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, TURNS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openSession(context, sessionId, sessionId.hashCode()))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify("turn-$sessionId".hashCode(), n) }
    }

    /**
     * The live card for a session Claude is working in: a running timer since it started,
     * its project, and Stop. On Android 16 it asks to be promoted to a Live Update, so it
     * shows on the lock screen and as a chip in the status bar.
     */
    fun running(context: Context, s: Session, project: String, startedAt: Long) {
        if (!allowed(context)) return
        val code = "run-${s.id}".hashCode()
        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putCharSequence("android.shortCriticalText", "Working")
        }
        val n = NotificationCompat.Builder(context, RUNNING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(s.title)
            .setContentText("Working · $project")
            .setSubText(project)
            .setWhen(startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .setContentIntent(openSession(context, s.id, code))
            .addAction(0, "Stop", action(context, "stop", s.id, code + 1))
            .addExtras(extras)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(code, n) }
    }

    fun stopRunning(context: Context, sessionId: String) = NotificationManagerCompat.from(context).cancel("run-$sessionId".hashCode())

    fun clear(context: Context, approvalId: String) = NotificationManagerCompat.from(context).cancel(approvalId.hashCode())
}

/** Answers what a notification's buttons ask, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val id = intent.getStringExtra("id") ?: return
        val app = BenchApp.instance
        val store = app.store.value ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.REPLY_KEY)?.toString()
        val pending = goAsync()
        app.appScope.launch {
            runCatching {
                when (kind) {
                    "allow" -> store.api.answer(id, buildJsonObject { put("allow", true) })
                    "deny" -> store.api.answer(id, buildJsonObject { put("allow", false); put("message", "") })
                    "plan" -> store.api.answer(id, buildJsonObject { put("allow", true); put("mode", "acceptEdits") })
                    "answer" -> {
                        val a = store.state.value.approvals.firstOrNull { it.id == id }
                        val q = a?.input?.get("questions").arr()?.firstOrNull()?.let { it["question"].str() } ?: ""
                        store.api.answer(id, buildJsonObject {
                            put("allow", true)
                            putJsonObject("answers") { put(q, reply ?: "") }
                        })
                    }
                    "stop" -> store.api.interrupt(id)
                }
            }
            if (kind == "stop") Notifier.stopRunning(context, id) else Notifier.clear(context, id)
            pending.finish()
        }
    }
}
