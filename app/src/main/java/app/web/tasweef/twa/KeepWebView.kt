package app.web.tasweef.twa

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * The app's WebView. While a video or audio plays, it keeps telling the page it is on screen when the phone's
 * screen turns off or another app opens: the WebView would otherwise pause what plays (and the page, YouTube's
 * player included, would see itself hidden). When playing stops, the real visibility is passed on.
 */
class KeepWebView(context: Context) : WebView(context) {
    private var real = View.VISIBLE

    var keepPlaying = false
        set(v) {
            field = v
            if (!v && real != View.VISIBLE) super.onWindowVisibilityChanged(real)
        }

    override fun onWindowVisibilityChanged(visibility: Int) {
        real = visibility
        super.onWindowVisibilityChanged(if (keepPlaying) View.VISIBLE else visibility)
    }
}
