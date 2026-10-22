package app.web.tasweef.twa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads one video (or its audio) at a time with yt-dlp on the phone itself, shows progress in a
 * notification, and saves the file in Downloads/Tasweef. Nothing goes through a server: the phone's own
 * connection is used, which YouTube treats like any viewer.
 */
class DownloadService : Service() {

    private data class Job(
        val id: String,
        val url: String,
        val mode: String,
        val quality: String,
        val format: String,
        val bitrate: String,
        val title: String,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val waiting = AtomicInteger(0)
    @Volatile private var currentId: String? = null
    private val cancelled = mutableSetOf<String>()
    private var lastPaint = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
            synchronized(cancelled) { cancelled += id }
            if (id == currentId) runCatching { YoutubeDL.destroyProcessById(id) }
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val job = Job(
            id = "dl" + SystemClock.elapsedRealtime(),
            url = url,
            mode = intent.getStringExtra(EXTRA_MODE) ?: "video",
            quality = intent.getStringExtra(EXTRA_QUALITY) ?: "720",
            format = intent.getStringExtra(EXTRA_FORMAT) ?: "mp3",
            bitrate = intent.getStringExtra(EXTRA_BITRATE) ?: "128",
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
        )
        val ahead = waiting.incrementAndGet() - 1
        // must become a foreground service right away, even when this job waits behind another one
        val first = progressNote(job, getString(R.string.preparing), -1f, ahead)
        ServiceCompat.startForeground(
            this, FG_ID, first,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        worker.execute {
            try {
                run(job)
            } finally {
                if (waiting.decrementAndGet() == 0) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /* ───────── one download ───────── */

    private fun run(job: Job) {
        currentId = job.id
        if (isCancelled(job)) return done(job, null, getString(R.string.cancelled))
        paint(job, getString(R.string.preparing), -1f, force = true)
        if (!App.awaitDownloader()) return done(job, null, getString(R.string.failedOther))

        val dir = File(cacheDir, "dl/${job.id}").apply { deleteRecursively(); mkdirs() }
        try {
            var error = attempt(job, dir)
            // YouTube changes often: on a failure, update yt-dlp once and try again
            if (error != null && !isCancelled(job) && error != getString(R.string.failedNet) && error != getString(R.string.cancelled) && maybeUpdate(job, force = true)) {
                dir.listFiles()?.forEach { it.deleteRecursively() }
                error = attempt(job, dir)
            }
            if (isCancelled(job)) return done(job, null, getString(R.string.cancelled))
            if (error != null) return done(job, null, error)

            val file = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: return done(job, null, getString(R.string.failedOther))
            paint(job, getString(R.string.saving), -1f, force = true)
            val uri = save(file)
            done(job, uri, null)
        } catch (t: Throwable) {
            done(job, null, if (isCancelled(job)) getString(R.string.cancelled) else getString(R.string.failedOther))
        } finally {
            dir.deleteRecursively()
            currentId = null
        }
    }

    /** Runs yt-dlp; returns null on success or the message to show. */
    private fun attempt(job: Job, dir: File): String? {
        val req = YoutubeDLRequest(job.url)
        req.addOption("-o", dir.absolutePath + "/%(title).120B.%(ext)s")
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--no-part")
        // keep what yt-dlp learns from YouTube's player between runs: without a cache it fetches and decodes it every time
        req.addOption("--cache-dir", File(filesDir, "yt-dlp-cache").absolutePath)
        if (job.mode == "audio") {
            if (job.format == "best") {
                req.addOption("-f", "ba[ext=m4a]/ba/b")
            } else {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", if (job.format == "ogg") "vorbis" else job.format)
                if (job.format != "wav") req.addOption("--audio-quality", job.bitrate + "K")
            }
        } else {
            req.addOption("-f", "bv*+ba/b")
            // closest to the chosen height without going over it, preferring H.264/AAC so every player opens it
            req.addOption("-S", if (job.quality == "max") "res,vcodec:h264,acodec:aac" else "res:${job.quality},vcodec:h264,acodec:aac")
            req.addOption("--merge-output-format", "mp4")
        }
        return try {
            synchronized(App.ytLock) { YoutubeDL.execute(req, job.id) { progress: Float, _: Long, line: String ->
                when {
                    line.startsWith("[youtube]") || line.startsWith("[info]") ->
                        paint(job, getString(R.string.reading), -1f)
                    line.contains("[Merger]") || line.contains("[ExtractAudio]") ->
                        paint(job, getString(R.string.merging), -1f, force = true)
                    progress >= 0f -> paint(job, getString(R.string.downloading), progress)
                }
            } }
            null
        } catch (e: YoutubeDL.CanceledException) {
            getString(R.string.cancelled)
        } catch (e: Exception) {
            explain(e.message.orEmpty())
        }
    }

    private fun explain(msg: String): String = when {
        msg.contains("Sign in to confirm", true) || msg.contains("HTTP Error 429") || msg.contains("bot", true) -> getString(R.string.failedBot)
        msg.contains("Unable to download webpage", true) || msg.contains("Failed to resolve", true) ||
            msg.contains("Network is unreachable", true) || msg.contains("timed out", true) -> getString(R.string.failedNet)
        else -> getString(R.string.failedOther)
    }

    /** After a failure, updates yt-dlp right away (once per run) so the retry uses the newest version. */
    private var updatedThisRun = false
    private fun maybeUpdate(job: Job, force: Boolean): Boolean {
        if (updatedThisRun) return false
        paint(job, getString(R.string.updating), -1f, force = true)
        updatedThisRun = App.refreshDownloader(applicationContext, force = true)
        return updatedThisRun
    }

    private fun isCancelled(job: Job) = synchronized(cancelled) { job.id in cancelled }

    /* ───────── saving into Downloads/Tasweef ───────── */

    private fun save(file: File): Uri {
        val name = file.name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in setOf("mp3", "m4a", "opus", "ogg", "wav", "webm")) "audio/*" else "video/*"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tasweef")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Tasweef").apply { mkdirs() }
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, name.substringBeforeLast('.') + " ($n)." + ext).also { n++ }
        file.copyTo(target)
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
        return FileProvider.getUriForFile(this, getString(R.string.providerAuthority), target)
    }

    /* ───────── notifications ───────── */

    private fun label(job: Job) = job.title.ifBlank { job.url }

    private fun progressNote(job: Job, text: String, progress: Float, ahead: Int): Notification {
        val cancel = PendingIntent.getService(
            this, job.id.hashCode(),
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, job.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label(job))
            .setContentText(if (progress >= 0f) "$text · ${progress.toInt()}٪" else text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .addAction(0, getString(R.string.cancel), cancel)
        if (ahead > 0) b.setSubText(getString(R.string.queued, ahead))
        if (progress >= 0f) b.setProgress(100, progress.toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun paint(job: Job, text: String, progress: Float, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPaint < 600) return
        lastPaint = now
        notify(FG_ID, progressNote(job, text, progress, waiting.get() - 1))
    }

    private fun done(job: Job, uri: Uri?, error: String?) {
        val b = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setAutoCancel(true)
            .setContentTitle(if (uri != null) getString(R.string.done) else error ?: getString(R.string.failed))
            .setContentText(label(job))
        if (uri != null) {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(label(job) + "\n" + getString(R.string.doneText)))
            val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(PendingIntent.getActivity(this, job.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE))
        } else if (error != getString(R.string.cancelled)) {
            b.setContentTitle(getString(R.string.failed))
            b.setStyle(NotificationCompat.BigTextStyle().bigText(label(job) + "\n" + error))
        }
        notify(job.id.hashCode(), b.build())
    }

    private fun notify(id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, n)
        } catch (_: SecurityException) {
            // notifications not allowed: the download still finishes and is saved
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_MODE = "mode"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "id"
        const val ACTION_CANCEL = "app.web.tasweef.twa.CANCEL"
        private const val FG_ID = 4201
        private const val CH_PROGRESS = "downloads"
        private const val CH_DONE = "downloads_done"

        fun channels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, ctx.getString(R.string.chDownloads), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, ctx.getString(R.string.chDone), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
