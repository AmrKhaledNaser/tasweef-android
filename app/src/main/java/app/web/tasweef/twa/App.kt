package app.web.tasweef.twa

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Unpacks yt-dlp (with its Python) and ffmpeg once in the background; downloads wait for it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Updater.checkInBackground(this)
        // the APK an update downloaded is no longer needed once the app starts again (the update replaced the app)
        thread(name = "update-cleanup") { File(cacheDir, "updates").deleteRecursively() }
        thread(name = "downloader-init") {
            try {
                YoutubeDL.init(this)
                FFmpeg.init(this)
            } catch (t: Throwable) {
                initError = t
            }
            ready.countDown()
            // the daily yt-dlp update runs here, in the background, instead of in front of a download
            if (initError == null) refreshDownloader(this)
        }
    }

    companion object {
        private val ready = CountDownLatch(1)
        @Volatile var initError: Throwable? = null

        /** Held while yt-dlp runs or updates, so an update never swaps its files under a running download. */
        val ytLock = Any()

        /** Updates yt-dlp when the last update is a day old (or [force]); true when it updated. */
        fun refreshDownloader(ctx: android.content.Context, force: Boolean = false): Boolean {
            val prefs = ctx.getSharedPreferences("downloader", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong("updatedAt", 0L) < 24 * 3600 * 1000L) return false
            return synchronized(ytLock) {
                try {
                    YoutubeDL.updateYoutubeDL(ctx.applicationContext)
                    prefs.edit().putLong("updatedAt", now).apply()
                    true
                } catch (_: Exception) { false }
            }
        }

        /** Blocks until the downloader is unpacked; false if that failed. */
        fun awaitDownloader(): Boolean {
            ready.await()
            return initError == null
        }
    }
}
