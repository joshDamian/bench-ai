package ai.bench.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app's connection to the server open while it is closed, so approvals, finished
 * turns and errors still notify. Bench is self-hosted, with no push service to wake the phone;
 * the open socket is the wake-up. Shows one quiet, silent notification while it runs.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while Bench stays connected to hear from Claude"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val host = BenchApp.instance.store.value?.api?.config?.baseUrl?.substringAfter("://")?.substringBefore(":")?.substringBefore(".") ?: "your server"
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Connected to $host")
            .setContentText("Bench tells you when Claude needs you")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 7, n, type)
        BenchApp.instance.store.value?.reconnectNow()
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "connection"
        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
        fun stop(context: Context) = context.stopService(Intent(context, ConnectionService::class.java))
    }
}
