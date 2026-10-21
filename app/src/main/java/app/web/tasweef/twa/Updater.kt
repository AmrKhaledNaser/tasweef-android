package app.web.tasweef.twa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Self-update: every release carries a small version.json next to the APKs. Once a day the app reads the
 * latest one; when it has a higher build number, a notification offers the update, and [UpdateActivity]
 * downloads and installs it without going through the browser.
 */
object Updater {
    const val INFO_URL = "https://github.com/AmrKhaledNaser/tasweef-android/releases/latest/download/version.json"
    private const val CH = "updates"
    private const val NOTE_ID = 4301

    data class Info(val code: Long, val name: String, val url: String, val size: Long)

    fun installedCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    /** Reads version.json; null when offline or malformed. Runs on the calling thread. */
    fun fetch(): Info? = try {
        val c = (URL(INFO_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000; instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        Info(
            code = j.getLong("code"),
            name = j.optString("name", ""),
            url = if (is64) j.getString("url") else j.optString("url32", j.getString("url")),
            size = if (is64) j.optLong("size") else j.optLong("size32", j.optLong("size")),
        )
    } catch (_: Exception) {
        null
    }

    /** Called when the app starts: checks at most once a day and notifies once per new build. */
    fun checkInBackground(ctx: Context) {
        val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checkedAt", 0) < 20 * 3600 * 1000L) return
        thread(name = "update-check") {
            val info = fetch() ?: return@thread
            prefs.edit().putLong("checkedAt", now).apply()
            if (info.code <= installedCode(ctx)) return@thread
            if (prefs.getLong("notified", 0) == info.code) return@thread
            prefs.edit().putLong("notified", info.code).apply()
            notify(ctx, info)
        }
    }

    private fun notify(ctx: Context, info: Info) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, ctx.getString(R.string.chUpdates), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(ctx, R.color.accent))
            .setContentTitle(ctx.getString(R.string.updateReady))
            .setContentText(ctx.getString(R.string.updateTap, mb(info.size)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTE_ID, n)
        } catch (_: SecurityException) { /* notifications off: the update is still offered from the app's settings */ }
    }

    fun mb(bytes: Long): String = if (bytes > 0) "%d".format(Math.round(bytes / 1048576.0)) else "؟"
}
