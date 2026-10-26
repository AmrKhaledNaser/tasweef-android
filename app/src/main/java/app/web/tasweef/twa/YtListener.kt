package app.web.tasweef.twa

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notices videos downloaded in the YouTube app. With the person's "notification access" turned on, Android
 * hands this a copy of each notification; only the YouTube app's are looked at. Its download notification
 * carries the video's title and a progress bar ("5 MB / 19 MB"). When a download finishes, the title goes
 * into a small queue on the phone, and the page (tasweef.web.app) turns each title into a video and adds it
 * to the "Downloads" list as soon as it is open. Nothing else is read, kept or sent anywhere.
 */
class YtListener : NotificationListenerService() {
    /** notification key → (title, highest percent seen) for downloads in progress */
    private val active = HashMap<String, Pair<String, Int>>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != YOUTUBE) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // Only a video's own notification carries its picture. YouTube's other notices ("YouTube · Looking for
        // incomplete downloads", "Downloading 2 videos"…) have none, and their title is not a video's, so they are
        // never taken (one was, before this check). A download it was tracking stays tracked.
        @Suppress("DEPRECATION")
        val hasPicture = n.getLargeIcon() != null || ex.get(Notification.EXTRA_LARGE_ICON) != null
        if (!hasPicture || generic(title)) return

        val key = sbn.key
        val seen = active[key]
        // "Video downloaded" (or the Arabic): finished
        if (DONE.containsMatchIn(text)) { active.remove(key); YtQueue.add(this, title); return }
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val pct = when {
            max > 0 -> ex.getInt(Notification.EXTRA_PROGRESS, 0) * 100 / max
            else -> sizePct(text) // "5 MB / 19 MB"
        }
        if (pct >= 0) {
            val best = maxOf(pct, seen?.second ?: 0)
            active[key] = title to best
            if (best >= 98) { active.remove(key); YtQueue.add(this, title) }
        } else if (seen != null) {
            // the same video's notification, now without a progress bar: done
            active.remove(key)
            YtQueue.add(this, title)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != YOUTUBE) return
        val seen = active.remove(sbn.key) ?: return
        // gone near the end: finished; gone early: cancelled, so nothing is added
        if (seen.second >= 90) YtQueue.add(this, seen.first)
    }

    companion object {
        const val YOUTUBE = "com.google.android.youtube"
        private val SIZE = Regex("""([\d.,]+)\s*([KMG]B|[كمج]ب|ميجا|كيلو|جيجا)\S*\s*/\s*([\d.,]+)\s*([KMG]B|[كمج]ب|ميجا|كيلو|جيجا)""", RegexOption.IGNORE_CASE)
        private val GENERIC = Regex("""(^\d+\s)|downloading|downloaded|download complete|incomplete|جار.? ?التنزيل|تم التنزيل|اكتمل التنزيل|فيديوهات""", RegexOption.IGNORE_CASE)
        private val APP_NAME = Regex("""^(youtube|يوتيوب)$""", RegexOption.IGNORE_CASE)
        private val DONE = Regex("""video downloaded|^downloaded$|download complete|تم تنزيل|تم التنزيل|اكتمل التنزيل""", RegexOption.IGNORE_CASE)

        private fun generic(title: String) = title.isEmpty() || APP_NAME.matches(title) || (title.length < 40 && GENERIC.containsMatchIn(title))

        /** Percent from "5 MB / 19 MB" (Arabic or Western digits); -1 when the text has no sizes. */
        fun sizePct(text: String): Int {
            val m = SIZE.find(latinDigits(text)) ?: return -1
            fun bytes(v: String, u: String): Double {
                val n = v.replace(",", ".").toDoubleOrNull() ?: return 0.0
                val unit = u.uppercase()
                return n * when {
                    unit.startsWith("G") || unit.startsWith("ج") -> 1e9
                    unit.startsWith("M") || unit.startsWith("م") -> 1e6
                    else -> 1e3
                }
            }
            val done = bytes(m.groupValues[1], m.groupValues[2])
            val total = bytes(m.groupValues[3], m.groupValues[4])
            return if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
        }

        private fun latinDigits(s: String) = buildString(s.length) {
            for (c in s) append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    '٫' -> '.'
                    else -> c
                }
            )
        }
    }
}

/** Titles of videos the YouTube app finished downloading, waiting for the page to add them. */
object YtQueue {
    /** set while the app's window is open, so the page hears about a new title right away */
    @Volatile var onChange: (() -> Unit)? = null
    private const val TWICE_WINDOW = 15 * 60_000L // one download can be noticed twice (progress, then "downloaded")
    private const val WEEK = 7 * 24 * 3600_000L

    private fun prefs(c: Context) = c.getSharedPreferences("ytq", Context.MODE_PRIVATE)

    @Synchronized fun add(c: Context, title: String) {
        if (title.isBlank()) return
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val seen = try { JSONObject(p.getString("seen", "{}")!!) } catch (_: Exception) { JSONObject() }
        if (now - seen.optLong(title, 0) < TWICE_WINDOW) return // the same download noticed twice
        seen.put(title, now)
        seen.keys().asSequence().toList().forEach { if (now - seen.optLong(it, 0) > WEEK) seen.remove(it) }
        val q = try { JSONArray(p.getString("q", "[]")) } catch (_: Exception) { JSONArray() }
        q.put(JSONObject().put("key", "$now-${title.hashCode()}").put("title", title).put("at", now))
        p.edit().putString("q", q.toString()).putString("seen", seen.toString()).apply()
        onChange?.invoke()
    }

    @Synchronized fun list(c: Context): String = prefs(c).getString("q", "[]") ?: "[]"

    @Synchronized fun done(c: Context, keysJson: String) {
        val keys = try { JSONArray(keysJson).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } catch (_: Exception) { return }
        val q = try { JSONArray(prefs(c).getString("q", "[]")) } catch (_: Exception) { JSONArray() }
        val left = JSONArray()
        for (i in 0 until q.length()) q.optJSONObject(i)?.let { if (it.optString("key") !in keys) left.put(it) }
        prefs(c).edit().putString("q", left.toString()).apply()
    }
}
