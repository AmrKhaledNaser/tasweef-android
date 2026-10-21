package app.web.tasweef.twa

import android.net.Uri

/**
 * Opens tasweef.web.app full screen in Chrome. Every launch carries ?twa=2 so the web app knows
 * it runs inside this APK and can hand downloads to [DownloadActivity] instead of an external site.
 */
class LauncherActivity : com.google.androidbrowserhelper.trusted.LauncherActivity() {
    override fun getLaunchingUrl(): Uri {
        val url = super.getLaunchingUrl()
        return if (url.getQueryParameter("twa") == null) {
            url.buildUpon().appendQueryParameter("twa", NATIVE_VERSION).build()
        } else url
    }

    companion object {
        /** Raise when the web app needs to know about a new native ability. */
        const val NATIVE_VERSION = "3" // 2: downloads on the phone; 3: also updates itself (tasweef://update)
    }
}
