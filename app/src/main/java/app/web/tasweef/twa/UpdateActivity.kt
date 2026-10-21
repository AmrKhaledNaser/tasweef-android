package app.web.tasweef.twa

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Shows the update offer, downloads the new APK inside the app (no browser) and opens Android's installer.
 * Reached from the update notification, or from the web app's settings (tasweef://update).
 */
class UpdateActivity : Activity() {
    private var info: Updater.Info? = null
    private var dialog: AlertDialog? = null
    private var waitingForPermission = false
    @Volatile private var cancelled = false

    private val dlgTheme get() =
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(AlertDialog.Builder(this, dlgTheme).setMessage(R.string.updateChecking).setCancelable(true))
        thread {
            val i = Updater.fetch()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    i == null -> message(R.string.updateFailedCheck)
                    i.code <= Updater.installedCode(this) -> message(R.string.updateNone)
                    else -> { info = i; offer(i) }
                }
            }
        }
    }

    private fun show(b: AlertDialog.Builder) {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = b.create().apply {
            setOnDismissListener { if (!waitingForPermission) { cancelled = true; finish() } }
            show()
        }
    }

    private fun message(text: Int) =
        show(AlertDialog.Builder(this, dlgTheme).setMessage(text).setPositiveButton(R.string.ok, null))

    private fun offer(i: Updater.Info) = show(
        AlertDialog.Builder(this, dlgTheme)
            .setTitle(R.string.updateReady)
            .setMessage(getString(R.string.updateOffer, Updater.mb(i.size)))
            .setPositiveButton(R.string.updateNow) { _, _ -> start() }
            .setNegativeButton(R.string.later, null)
    )

    private fun start() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            show(
                AlertDialog.Builder(this, dlgTheme)
                    .setTitle(R.string.updateAllowTitle)
                    .setMessage(R.string.updateAllow)
                    .setPositiveButton(R.string.updateAllowBtn) { _, _ ->
                        waitingForPermission = true
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton(R.string.later, null)
            )
            return
        }
        download()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForPermission) {
            waitingForPermission = false
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) download()
            else message(R.string.updateNoPermission)
        }
    }

    private fun download() {
        val i = info ?: return finish()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val label = TextView(this).apply { setText(R.string.updateDownloading); gravity = Gravity.START }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p / 2)
            addView(label); addView(bar)
        }
        show(AlertDialog.Builder(this, dlgTheme).setTitle(R.string.updateReady).setView(box).setNegativeButton(R.string.cancel, null))
        thread {
            val file = File(cacheDir, "updates/tasweef.apk").apply { parentFile?.mkdirs(); delete() }
            try {
                val c = (URL(i.url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: i.size
                var got = 0L
                var shown = -1
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled) return@thread
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else -1
                            if (pct != shown) {
                                shown = pct
                                runOnUiThread {
                                    if (pct >= 0) { bar.isIndeterminate = false; bar.progress = pct; label.text = getString(R.string.updateDownloadingPct, pct) }
                                }
                            }
                        }
                    }
                }
                runOnUiThread { install(file) }
            } catch (_: Exception) {
                file.delete()
                runOnUiThread { if (!cancelled) message(R.string.updateFailed) }
            }
        }
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(this, getString(R.string.providerAuthority), file)
        startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        finish()
    }
}
