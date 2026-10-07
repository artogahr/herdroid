package dev.herdroid.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.herdroid.HerdroidApp
import dev.herdroid.MainActivity
import dev.herdroid.R

/**
 * Keeps the process running for a few minutes after you leave the app, so the SSH link
 * survives a quick switch to another app. Without it Android freezes the process, the
 * socket dies, and the return waits for a new handshake and herdr lookup.
 *
 * On Android 14+ this is a `shortService`, which the system ends after about three
 * minutes. Older versions have no such type, so the service stops itself after the same time.
 */
class ConnectionService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val connection = (application as HerdroidApp).connection
        if (intent?.action == ACTION_DISCONNECT) {
            connection.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown for a few minutes after leaving Herdroid, while it stays connected"
                setShowBadge(false)
            },
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val disconnect =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Connected to ${connection.config.label}")
                .setContentText("Staying connected for a few minutes.")
                .setContentIntent(open)
                .addAction(0, "Disconnect", disconnect)
                .setSilent(true)
                .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        if (Build.VERSION.SDK_INT < 34) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ stopSelf() }, LIMIT_MS)
        }
        return START_NOT_STICKY
    }

    // Android ends a shortService here; not stopping within a few seconds crashes the app.
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "dev.herdroid.DISCONNECT"
        private const val LIMIT_MS = 3 * 60_000L

        fun start(context: Context) {
            // Android refuses to start a foreground service once the app is in the
            // background. The app then behaves as without the service.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
                .onFailure { Log.w("ConnectionService", "could not start", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
