package app.web.tasweef.twa

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * The app: tasweef.web.app in the app's own window (Android's WebView), so it opens at once, without Chrome
 * starting first and without Chrome's "running in Chrome" notice. Android's launch screen shows the mark and
 * stays until the page's own launch animation has drawn its first frame in exactly the same place.
 * The page talks to the app through window.TasweefApp (downloads on the phone, updates, Google sign-in…).
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    @Volatile private var pageReady = false
    @Volatile private var trusted = false // the main page is tasweef.web.app (the bridge answers only then)
    private var insetTop = 0
    private var insetBottom = 0
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !pageReady }
        // the page draws the same mark at the same spot underneath, so the launch screen just goes away
        splash.setOnExitAnimationListener { it.remove() }
        window.decorView.postDelayed({ pageReady = true }, 5000) // never keep it longer than this

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val d = resources.displayMetrics.density
            insetTop = (bars.top / d).toInt()
            insetBottom = (bars.bottom / d).toInt()
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        setBarsUi(prefs.getString("barColor", null), prefs.getBoolean("barDark", isNight()))

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false) // links that open a new window come through shouldOverrideUrlLoading
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE // the owner's computer on home Wi-Fi is http
            userAgentString = "$userAgentString TasweefApp/${LauncherInfo.NATIVE_VERSION}"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) // YouTube's player
        web.addJavascriptInterface(Bridge(), "TasweefApp")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullView != null -> fullCallback?.onCustomViewHidden()
                    web.canGoBack() -> web.goBack()
                    else -> moveTaskToBack(true)
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(startUrl(intent) ?: HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startUrl(intent)?.let { web.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** Where to open: a shared link goes to /share, a tasweef.web.app link opens itself, otherwise home. */
    private fun startUrl(i: Intent?): String? {
        if (i == null) return null
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val title = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            return "$ORIGIN/share?twa=${LauncherInfo.NATIVE_VERSION}&title=${Uri.encode(title)}&text=${Uri.encode(text)}"
        }
        val data = i.data ?: return null
        if (data.scheme == "https" && data.host == HOST) {
            return data.buildUpon().appendQueryParameter("twa", LauncherInfo.NATIVE_VERSION).build().toString()
        }
        return null
    }

    private fun isNight() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun setBarsUi(color: String?, dark: Boolean) {
        val c = try { if (color != null) Color.parseColor(color) else null } catch (_: Exception) { null }
            ?: if (dark) Color.parseColor("#181818") else Color.WHITE
        root.setBackgroundColor(c)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(c))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    /* ───────── the page's window.TasweefApp ───────── */

    inner class Bridge {
        @JavascriptInterface fun version(): String = LauncherInfo.NATIVE_VERSION

        /** Safe-area sizes in CSS px, for the launch animation to start exactly where Android's launch screen is. */
        @JavascriptInterface fun insets(): String = "{\"top\":$insetTop,\"bottom\":$insetBottom}"

        /** The page's launch animation has drawn its first frame: Android's launch screen can go. */
        @JavascriptInterface fun ready() { pageReady = true }

        /** Colour behind the status and navigation bars, and the theme the person chose (system | light | dark). */
        @JavascriptInterface fun setBars(color: String, dark: Boolean, theme: String) {
            if (!trusted) return
            getSharedPreferences("app", MODE_PRIVATE).edit().putString("barColor", color).putBoolean("barDark", dark).apply()
            runOnUiThread {
                setBarsUi(color, dark)
                // Android's launch screen then opens in the same theme next time
                if (Build.VERSION.SDK_INT >= 31) {
                    val mode = when (theme) { "dark" -> UiModeManager.MODE_NIGHT_YES; "light" -> UiModeManager.MODE_NIGHT_NO; else -> UiModeManager.MODE_NIGHT_AUTO }
                    try { getSystemService(UiModeManager::class.java).setApplicationNightMode(mode) } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface fun googleSignIn() { if (trusted) runOnUiThread { signInWithGoogle() } }

        @JavascriptInterface fun checkUpdate() { if (trusted) runOnUiThread { startActivity(Intent(this@MainActivity, UpdateActivity::class.java)) } }

        @JavascriptInterface fun share(text: String) {
            if (!trusted) return
            runOnUiThread {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
        }

        /** Saves a file the page made (an export, a file from the owner's computer) into Downloads/Tasweef. */
        @JavascriptInterface fun saveFile(name: String, mime: String, base64: String): Boolean {
            if (!trusted) return false
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tasweef")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Tasweef").apply { mkdirs() }
                    File(dir, name).writeBytes(bytes)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, R.string.savedToDownloads, Toast.LENGTH_SHORT).show() }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Android's own "Sign in with Google" sheet; the page signs in to Firebase with the token it gets back. */
    private fun signInWithGoogle() {
        lifecycleScope.launch {
            val reply = try {
                val req = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build()).build()
                val cred = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, req).credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    "${JSONObject.quote(GoogleIdTokenCredential.createFrom(cred.data).idToken)}, null"
                } else "null, 'unsupported'"
            } catch (_: GetCredentialCancellationException) {
                "null, 'cancelled'"
            } catch (e: Exception) {
                "null, ${JSONObject.quote(e.javaClass.simpleName + ": " + (e.message ?: ""))}"
            }
            js("window.__twGoogle && window.__twGoogle($reply)")
        }
    }

    /* ───────── navigation ───────── */

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            trusted = Uri.parse(url).let { it.scheme == "https" && it.host == HOST }
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            // a page without the launch animation (an old cached copy) still lets the launch screen go
            view.postDelayed({ pageReady = true }, 1200)
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url
            if (!req.isForMainFrame) return false
            if (u.scheme == "https" && (u.host == HOST || u.host == AUTH_HOST)) return false
            if (u.scheme == "intent") return openIntent(u.toString())
            if (u.scheme == "tasweef") return route(u)
            // everything else (YouTube, the APK page, external download sites…) opens in its own app or the browser
            try { startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: ActivityNotFoundException) {}
            return true
        }
    }

    /** intent://download?…#Intent;scheme=tasweef;package=…;end from the page: handled inside the app. */
    private fun openIntent(url: String): Boolean {
        val i = try { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) } catch (_: Exception) { return true }
        val data = i.data
        if (data?.scheme == "tasweef") return route(data)
        i.addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null).setSelector(null)
        try { startActivity(i) } catch (_: ActivityNotFoundException) {
            i.getStringExtra("browser_fallback_url")?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
        return true
    }

    private fun route(data: Uri): Boolean {
        val target = when (data.host) { "download" -> DownloadActivity::class.java; "update" -> UpdateActivity::class.java; else -> null }
        if (target != null) startActivity(Intent(Intent.ACTION_VIEW, data, this, target))
        return true
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = cb
            return try { pickFile.launch(params.createIntent()); true } catch (_: Exception) { fileCallback = null; false }
        }

        // a video's full-screen button
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullView = view; fullCallback = callback
            (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        override fun onHideCustomView() {
            fullView?.let { (window.decorView as ViewGroup).removeView(it) }
            fullView = null; fullCallback = null
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val HOST = "tasweef.web.app"
        const val AUTH_HOST = "tasweef-a6f0f.firebaseapp.com"
        const val ORIGIN = "https://$HOST"
        val HOME = "$ORIGIN/?twa=${LauncherInfo.NATIVE_VERSION}"
        /** Firebase's web OAuth client: Google gives the ID token for it, and Firebase signs in with that token. */
        const val WEB_CLIENT_ID = "45151870971-jsqo2jh8pip3fugr197v73jcl49pbpu2.apps.googleusercontent.com"
    }
}
