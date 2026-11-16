package ai.bench.app.phone

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.bench.app.R
import ai.bench.app.data.str
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * What a session asks of the phone, done on the phone. Each call names a share; the share is
 * checked again here (it may have changed since the server last heard), and so is Android's
 * permission for it. Returns the text the session reads, or throws with what went wrong.
 */
object PhoneActions {
    class Refused(message: String) : Exception(message)

    fun run(c: Context, tool: String, shareId: String, args: JsonObject): String {
        val share = PhoneShares.shares.value.firstOrNull { it.id == shareId } ?: throw Refused("This phone no longer shares that")
        PhoneShares.missing(c, share.kind)?.let { throw Refused("${share.name} is shared, but Android has not given Bench $it access yet. The person turns it on in Bench, This phone.") }
        fun a(k: String) = args[k].str()?.trim().orEmpty()
        return when (tool) {
            "events" -> events(c, a("from"), a("to"))
            "add_event" -> {
                if (share.access != "write") throw Refused("The calendar is shared to read only")
                addEvent(c, a("title"), a("start"), a("end"), a("place"), a("note"))
            }
            "contacts" -> contacts(c, a("query"))
            "sms" -> sms(c, a("to"), a("text"))
            "handover" -> handover(c, args)
            else -> throw Refused("The phone does not know “$tool”")
        }
    }

    private val day = DateTimeFormatter.ofPattern("EEE d MMM")
    private val time = DateTimeFormatter.ofPattern("H:mm")

    private fun parse(s: String, fallback: ZonedDateTime): ZonedDateTime {
        if (s.isBlank()) return fallback
        return runCatching { OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()) }
            .recoverCatching { java.time.LocalDateTime.parse(s).atZone(ZoneId.systemDefault()) }
            .recoverCatching { LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()) }
            .getOrElse { throw Refused("Could not read the time “$s”; use ISO, e.g. 2026-11-17T10:00:00+01:00") }
    }

    private fun events(c: Context, from: String, to: String): String {
        val start = parse(from, ZonedDateTime.now())
        val end = parse(to, start.plusDays(7))
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start.toInstant().toEpochMilli())
            android.content.ContentUris.appendId(it, end.toInstant().toEpochMilli())
        }.build()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY)
        val out = mutableListOf<String>()
        c.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
            while (cur.moveToNext() && out.size < 60) {
                val b = Instant.ofEpochMilli(cur.getLong(1)).atZone(ZoneId.systemDefault())
                val e = Instant.ofEpochMilli(cur.getLong(2)).atZone(ZoneId.systemDefault())
                val whenText = if (cur.getInt(4) == 1) "${b.format(day)}, all day" else "${b.format(day)} ${b.format(time)}–${e.format(time)}"
                out += listOfNotNull(whenText, cur.getString(0) ?: "(no title)", cur.getString(3)?.takeIf { it.isNotBlank() }).joinToString(" · ")
            }
        }
        return if (out.isEmpty()) "Nothing in the calendar from ${start.format(day)} to ${end.format(day)}." else out.joinToString("\n")
    }

    private fun addEvent(c: Context, title: String, start: String, end: String, place: String, note: String): String {
        if (title.isBlank()) throw Refused("Give the event a title")
        val b = parse(start, ZonedDateTime.now())
        val e = parse(end, b.plusHours(1))
        // The primary calendar the person can write to, else the first writable one.
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1"
        var calendar: Long? = null
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, sel, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                if (calendar == null || cur.getInt(1) == 1) calendar = cur.getLong(0)
                if (cur.getInt(1) == 1) break
            }
        }
        val id = calendar ?: throw Refused("No calendar on the phone can be written to")
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, id)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, b.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, e.toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            if (place.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, place)
            if (note.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, note)
        }
        c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: throw Refused("The calendar did not take the event")
        done(c, "Added to your calendar", "$title · ${b.format(day)} ${b.format(time)}" + if (place.isNotBlank()) ", $place" else "")
        return "Added “$title” on ${b.format(day)} at ${b.format(time)}."
    }

    private fun contacts(c: Context, query: String): String {
        if (query.isBlank()) throw Refused("Say whose number")
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val out = linkedSetOf<String>()
        c.contentResolver.query(uri, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext() && out.size < 12) out += "${cur.getString(0)}: ${cur.getString(1)}"
        }
        return if (out.isEmpty()) "No contact matches “$query”." else out.joinToString("\n")
    }

    private fun sms(c: Context, to: String, text: String): String {
        if (to.isBlank() || text.isBlank()) throw Refused("A text needs a number and a message")
        @Suppress("DEPRECATION")
        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(to, null, sms.divideMessage(text), null, null)
        return "Sent the text to $to."
    }

    /**
     * Something ready in its app for the person to finish. Opens straight away when they said so
     * and Android allows it; otherwise it waits as a notification they tap.
     */
    private fun handover(c: Context, args: JsonObject): String {
        fun a(k: String) = args[k].str()?.trim().orEmpty()
        val digits = a("to").filter { it.isDigit() }
        val intent = when (a("what")) {
            "message" -> if (a("app").equals("sms", true)) Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${a("to")}")).putExtra("sms_body", a("text"))
                else Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits" + if (a("text").isNotBlank()) "?text=${Uri.encode(a("text"))}" else ""))
            "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(a("to"))}")).putExtra(Intent.EXTRA_SUBJECT, a("subject")).putExtra(Intent.EXTRA_TEXT, a("text"))
            "event" -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                putExtra(CalendarContract.Events.TITLE, a("title").ifBlank { a("label") })
                if (a("place").isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, a("place"))
                if (a("start").isNotBlank()) {
                    val b = parse(a("start"), ZonedDateTime.now())
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, b.toInstant().toEpochMilli())
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, parse(a("end"), b.plusHours(1)).toInstant().toEpochMilli())
                }
            }
            // Opens in Google Maps when it is installed, else the browser.
            "route" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(a("place"))}"))
            "call" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${a("to")}"))
            "link" -> Intent(Intent.ACTION_VIEW, Uri.parse(a("url").ifBlank { throw Refused("A link needs a url") }))
            else -> throw Refused("Hand over a message, email, event, route, call or link")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val label = a("label").ifBlank { "Ready on your phone" }
        val canOpen = PhoneShares.openNow.value && android.provider.Settings.canDrawOverlays(c)
        if (canOpen && runCatching { c.startActivity(intent) }.isSuccess) return "Opened on the phone: $label. The person finishes it there."
        val code = (label + System.currentTimeMillis()).hashCode()
        val n = NotificationCompat.Builder(c, ai.bench.app.Notifier.PHONE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(label)
            .setContentText("Tap to open it and finish")
            .setContentIntent(PendingIntent.getActivity(c, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(code, n) }
        return "Ready on the phone as a notification: $label. The person taps it and finishes it there."
    }

    /** Something done in the background, said once, quietly. */
    private fun done(c: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(c, ai.bench.app.Notifier.PHONE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title).setContentText(text)
            .setSilent(true).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(c).notify((title + text).hashCode(), n) }
    }
}
