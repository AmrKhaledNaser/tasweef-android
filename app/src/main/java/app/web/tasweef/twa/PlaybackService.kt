package app.web.tasweef.twa

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a video or audio playing inside the app while the screen is off or another app is open: a playback
 * notification (what plays, pause/play, stop) keeps the app running, and the phone's CPU and Wi-Fi stay awake
 * for it. The page starts it when its player opens and stops it when the player closes.
 */
class PlaybackService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var title = ""
    private var playing = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> { onCommand?.invoke(if (playing) "pause" else "play"); playing = !playing; show() }
            ACTION_STOP -> { onCommand?.invoke("stop"); finish(); return START_NOT_STICKY }
            ACTION_STATE -> { playing = intent.getBooleanExtra(EXTRA_PLAYING, playing); show() }
            else -> {
                title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                playing = true
                channel(this)
                ServiceCompat.startForeground(
                    this, NOTE_ID, note(),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
                )
                hold()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun hold() {
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tasweef:playback").apply { setReferenceCounted(false); acquire() }
        if (wifi == null) wifi = try {
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tasweef:playback") // (the low-latency lock lets go when the screen turns off)
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { null }
    }

    private fun show() {
        if (title.isEmpty() && !running) return
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note())
    }

    private fun note(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { getString(R.string.appName) })
            .setContentText(getString(if (playing) R.string.playNow else R.string.playPaused))
            .setContentIntent(open)
            .setOngoing(playing)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(if (playing) R.string.playPause else R.string.playResume), toggle)
            .addAction(0, getString(R.string.playStop), stop)
            .build()
    }

    private fun finish() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onCreate() { super.onCreate(); running = true }

    override fun onDestroy() {
        running = false
        try { wake?.release() } catch (_: Exception) { }
        try { wifi?.release() } catch (_: Exception) { }
        wake = null; wifi = null
        super.onDestroy()
    }

    companion object {
        private const val NOTE_ID = 4301
        private const val CHANNEL = "playback"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_STOP = "stop"
        private const val ACTION_STATE = "state"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"

        /** The window that plays it: "pause", "play" or "stop" from the notification's buttons. */
        @Volatile var onCommand: ((String) -> Unit)? = null
        @Volatile var running = false

        fun start(c: Context, title: String) {
            ContextCompat.startForegroundService(c, Intent(c, PlaybackService::class.java).putExtra(EXTRA_TITLE, title))
        }
        fun stop(c: Context) { if (running) c.stopService(Intent(c, PlaybackService::class.java)); running = false }
        /** Paused or playing again from the page itself: the notification's button follows. */
        fun state(c: Context, playing: Boolean) {
            if (running) try { c.startService(Intent(c, PlaybackService::class.java).setAction(ACTION_STATE).putExtra(EXTRA_PLAYING, playing)) } catch (_: Exception) { }
        }

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.chPlayback), NotificationManager.IMPORTANCE_LOW))
        }
    }
}
