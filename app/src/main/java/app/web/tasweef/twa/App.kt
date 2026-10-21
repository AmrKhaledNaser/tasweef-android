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
        }
    }

    companion object {
        private val ready = CountDownLatch(1)
        @Volatile var initError: Throwable? = null

        /** Blocks until the downloader is unpacked; false if that failed. */
        fun awaitDownloader(): Boolean {
            ready.await()
            return initError == null
        }
    }
}
