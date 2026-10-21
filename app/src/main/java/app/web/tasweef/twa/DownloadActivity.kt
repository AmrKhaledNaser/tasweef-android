package app.web.tasweef.twa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Receives tasweef://download?u=<video link>&m=video|audio&q=<height|max>&f=<audio format>&b=<kbps>&t=<title>
 * from the web app, asks for the permissions it needs once, then hands the job to [DownloadService].
 * It has no screen of its own: the person stays in the app and follows the download in the notifications.
 */
class DownloadActivity : Activity() {
    private var pending: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val link = data?.getQueryParameter("u")
        if (data == null || link.isNullOrBlank() || !(link.startsWith("https://") || link.startsWith("http://"))) {
            Toast.makeText(this, R.string.badLink, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pending = Intent(this, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_URL, link)
            putExtra(DownloadService.EXTRA_MODE, data.getQueryParameter("m") ?: "video")
            putExtra(DownloadService.EXTRA_QUALITY, data.getQueryParameter("q") ?: "720")
            putExtra(DownloadService.EXTRA_FORMAT, data.getQueryParameter("f") ?: "mp3")
            putExtra(DownloadService.EXTRA_BITRATE, data.getQueryParameter("b") ?: "128")
            putExtra(DownloadService.EXTRA_TITLE, data.getQueryParameter("t") ?: "")
        }

        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) missing += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) missing += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (missing.isEmpty()) start() else requestPermissions(missing.toTypedArray(), REQ)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            Toast.makeText(this, R.string.needStorage, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        start() // notifications are optional: the download still runs without them
    }

    private fun start() {
        pending?.let { ContextCompat.startForegroundService(this, it) }
        Toast.makeText(this, R.string.started, Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }

    companion object { private const val REQ = 7 }
}
