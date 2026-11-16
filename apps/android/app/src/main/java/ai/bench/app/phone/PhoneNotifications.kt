package ai.bench.app.phone

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/** A message from a shared app, as its notification showed it. */
data class PhoneMessage(val app: String, val chat: String, val from: String, val text: String, val at: Long)

/**
 * Reads message notifications from the apps the phone can share (WhatsApp, Telegram), keeps the
 * recent ones, and holds each chat's reply action so Bench can answer from the notification the
 * way a watch does. Android starts it once the person gives Bench notification access; what it
 * keeps is only used for apps that are shared.
 */
class PhoneNotifications : NotificationListenerService() {
    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { take(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = take(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) { replies.entries.removeAll { it.value.key == sbn.key } }
    }

    private fun take(sbn: StatusBarNotification) {
        val app = PhoneShares.messageApps[sbn.packageName]
            ?: PhoneShares.shares.value.firstOrNull { it.kind == "use" && it.path == sbn.packageName }?.name ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val chat = (style?.conversationTitle?.toString() ?: title).ifBlank { return }
        val found = style?.messages?.mapNotNull { m ->
            val text = m.text?.toString() ?: return@mapNotNull null
            PhoneMessage(app, chat, m.person?.name?.toString() ?: chat, text, m.timestamp)
        }.orEmpty().ifEmpty {
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            listOf(PhoneMessage(app, chat, title.ifBlank { chat }, text, sbn.postTime))
        }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        synchronized(lock) {
            found.forEach { m -> if (messages.none { it.app == m.app && it.chat == m.chat && it.at == m.at && it.text == m.text }) messages += m }
            messages.sortBy { it.at }
            while (messages.size > 300) messages.removeAt(0)
            if (reply != null) replies["$app\u0000$chat"] = Reply(sbn.key, reply)
        }
    }

    private class Reply(val key: String, val action: Notification.Action)

    companion object {
        private val lock = Any()
        private val messages = mutableListOf<PhoneMessage>()
        private val replies = mutableMapOf<String, Reply>()

        fun recent(app: String, chat: String?): List<PhoneMessage> = synchronized(lock) {
            messages.filter { it.app == app && (chat.isNullOrBlank() || it.chat.contains(chat, ignoreCase = true)) }
        }

        /** The chats that can be answered now, by their name. */
        fun answerable(app: String): List<String> = synchronized(lock) {
            replies.keys.filter { it.startsWith("$app\u0000") }.map { it.substringAfter('\u0000') }
        }

        /** Answers a chat through its notification. Null when done, else why not. */
        fun reply(context: Context, app: String, chat: String, text: String): String? {
            val r = synchronized(lock) {
                replies["$app\u0000$chat"] ?: replies.entries.firstOrNull { it.key.startsWith("$app\u0000") && it.key.substringAfter('\u0000').contains(chat, ignoreCase = true) }?.value
            } ?: return "No $app notification from “$chat” to reply to. It may have been read or cleared; use phone_handover to prepare the reply instead."
            val inputs = r.action.remoteInputs ?: return "That notification has no reply"
            val intent = Intent()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return runCatching { r.action.actionIntent.send(context, 0, intent); null }.getOrElse { "The reply did not go: ${it.message}" }
        }
    }
}
