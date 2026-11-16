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
    /** Hand-overs, things done on the phone, and what sessions ask the phone to share. */
    const val PHONE = "phone"
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
        nm.createNotificationChannel(
            NotificationChannel(PHONE, "On this phone", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messages ready to send, events added, and what Bench asks this phone to share"
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
        val phone = a.tool == ai.bench.app.ui.PHONE_REPLY || a.tool == ai.bench.app.ui.PHONE_SMS || a.tool == ai.bench.app.ui.PHONE_CONFIRM
        val what = when (a.tool) {
            ai.bench.app.ui.PHONE_REPLY, ai.bench.app.ui.PHONE_SMS -> a.input["text"].str().orEmpty()
            ai.bench.app.ui.PHONE_CONFIRM -> a.input["detail"].str().orEmpty()
            "AskUserQuestion" -> a.input["questions"].arr()?.firstOrNull()?.let { it["question"].str() } ?: "Claude is asking a question"
            "ExitPlanMode" -> "Claude has a plan"
            "Bash" -> "Run: " + (a.input["command"].str() ?: "a command")
            else -> a.title ?: ai.bench.app.ui.toolName(a.tool)
        }
        val b = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (a.tool == ai.bench.app.ui.PHONE_CONFIRM) ai.bench.app.ui.askLine(a) else if (phone) ai.bench.app.ui.askLine(a) + "?" else sessionTitle)
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
            // A step in an app on the hidden screen that pays, books or sends: yes, watch it first, or no.
            ai.bench.app.ui.PHONE_CONFIRM -> {
                b.addAction(0, "Yes", action(context, "allow", a.id, code + 1))
                b.addAction(0, "Watch", watch(context, code + 3))
                b.addAction(0, "Don't", action(context, "dont", a.id, code + 2))
            }
            // A reply or a text: send it as written, change it first, or don't.
            ai.bench.app.ui.PHONE_REPLY, ai.bench.app.ui.PHONE_SMS -> {
                b.addAction(0, "Send", action(context, "allow", a.id, code + 1))
                val input = RemoteInput.Builder(REPLY_KEY).setLabel("Your version").build()
                b.addAction(
                    NotificationCompat.Action.Builder(0, "Edit", action(context, "edit", a.id, code + 3, mutable = true))
                        .addRemoteInput(input).setAllowGeneratedReplies(false).build(),
                )
                b.addAction(0, "Don't", action(context, "dont", a.id, code + 2))
            }
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

    /** Opens Bench on the hidden screen's live view. */
    private fun watch(context: Context, code: Int) = PendingIntent.getActivity(
        context, code, Intent(context, MainActivity::class.java).putExtra(EXTRA_WATCH, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    const val EXTRA_WATCH = "watch_hidden_screen"
    private const val USING = 0x7e57

    /** While Bench uses an app on the hidden screen: which, with Watch and Stop. */
    fun using(context: Context, app: String) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, RUNNING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Bench is using $app")
            .setContentText("On a hidden screen. Your screen is yours.")
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setUsesChronometer(true).setWhen(System.currentTimeMillis()).setShowWhen(true)
            .setContentIntent(watch(context, USING))
            .addAction(0, "Watch", watch(context, USING + 1))
            .addAction(0, "Stop", action(context, "hands_stop", "hands", USING + 2))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(USING, n) }
    }

    fun stopUsing(context: Context) = NotificationManagerCompat.from(context).cancel(USING)

    /** A session asks this phone to share something more: allow it from here, or open Bench. */
    fun phoneAsk(context: Context, ask: ai.bench.app.phone.PhoneAsk) {
        if (!allowed(context)) return
        val code = ask.id.hashCode()
        val open = PendingIntent.getActivity(context, code, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, PHONE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Bench asks to use ${ai.bench.app.ui.phoneAskWhat(ask)}")
            .setContentText(ask.why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ask.why))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Not now", action(context, "phone_deny", ask.id, code + 2))
            .addAction(0, "Allow", action(context, "phone_allow", ask.id, code + 1))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(code, n) }
    }

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
        // What a session asked this phone to share: decided here, no server call of its own.
        // Stop on "Bench is using …": close the hidden screen and stop the session that used it.
        if (kind == "hands_stop") {
            val sid = ai.bench.app.phone.PhoneHands.open.value?.sessionId
            app.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                ai.bench.app.phone.PhoneHands.done(context)
                if (sid != null) runCatching { app.store.value?.api?.interrupt(sid) }
            }
            return
        }
        if (kind == "phone_allow" || kind == "phone_deny") {
            ai.bench.app.phone.PhoneLink.asks.value.firstOrNull { it.id == id }?.let { ai.bench.app.phone.PhoneLink.answer(context, it, kind == "phone_allow") }
            Notifier.clear(context, id)
            return
        }
        val pending = goAsync()
        app.appScope.launch {
            runCatching {
                when (kind) {
                    "allow" -> store.api.answer(id, buildJsonObject { put("allow", true) })
                    "deny" -> store.api.answer(id, buildJsonObject { put("allow", false); put("message", "") })
                    "plan" -> store.api.answer(id, buildJsonObject { put("allow", true); put("mode", "acceptEdits") })
                    "edit" -> store.api.answer(id, buildJsonObject { put("allow", true); if (!reply.isNullOrBlank()) put("text", reply.trim()) })
                    "dont" -> store.api.answer(id, buildJsonObject { put("allow", false); put("message", "The person chose not to send it. Don't send it another way.") })
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
