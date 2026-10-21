package app.web.tasweef.twa

object LauncherInfo {
    /**
     * Tells the page (?twa=N, kept in its localStorage) what this app can do. Raise it with new abilities:
     * 2 downloads on the phone · 3 updates itself · 4 its own window (window.TasweefApp: sign-in, files, bars).
     */
    const val NATIVE_VERSION = "4"
}
