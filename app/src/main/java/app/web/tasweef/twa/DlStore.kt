package app.web.tasweef.twa

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

/**
 * The videos and audio this app downloaded on the phone (Downloads/Tasweef), for the page's Downloads view:
 * each one with the link it came from and its title, so the page can show its picture, play it inside the app
 * and add it to a list. Files saved before this record existed are found in the phone's media library.
 * Only files this app saved are listed; a file deleted elsewhere drops out of the list by itself.
 */
object DlStore {
    /** set while the app's window is open, so the page hears about a new download right away */
    @Volatile var onChange: (() -> Unit)? = null

    private fun prefs(c: Context) = c.getSharedPreferences("dls", Context.MODE_PRIVATE)
    private fun read(c: Context) = try { JSONArray(prefs(c).getString("list", "[]")) } catch (_: Exception) { JSONArray() }

    @Synchronized fun add(c: Context, uri: Uri, name: String, mime: String, mode: String, url: String, title: String) {
        val list = read(c)
        val o = JSONObject()
            .put("id", "d" + System.currentTimeMillis())
            .put("uri", uri.toString()).put("name", name).put("mime", mime)
            .put("mode", if (mime.startsWith("audio") || mode == "audio") "audio" else "video")
            .put("url", url).put("title", title).put("at", System.currentTimeMillis())
        list.put(o)
        prefs(c).edit().putString("list", list.toString()).apply()
        onChange?.invoke()
    }

    /** Every download still on the phone, newest first, as JSON [{id, uri, name, mime, mode, url, title, at, size}]. */
    @Synchronized fun list(c: Context): String {
        val list = read(c)
        val out = JSONArray()
        val known = mutableSetOf<String>()
        var changed = false
        val keep = JSONArray()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val size = sizeOf(c, Uri.parse(o.optString("uri")))
            if (size < 0) { changed = true; continue } // deleted elsewhere
            keep.put(o)
            known += o.optString("uri")
            out.put(JSONObject(o.toString()).put("size", size))
        }
        if (changed) prefs(c).edit().putString("list", keep.toString()).apply()
        // older downloads (before the app kept this list): the files it saved into Downloads/Tasweef
        if (Build.VERSION.SDK_INT >= 29) try {
            c.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED),
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf("Download/Tasweef%"), null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0)).toString()
                    if (uri in known) continue
                    val mime = cur.getString(2) ?: ""
                    if (!mime.startsWith("video") && !mime.startsWith("audio")) continue
                    out.put(JSONObject().put("id", "m" + cur.getLong(0)).put("uri", uri).put("name", cur.getString(1) ?: "")
                        .put("mime", mime).put("mode", if (mime.startsWith("audio")) "audio" else "video")
                        .put("url", "").put("title", (cur.getString(1) ?: "").substringBeforeLast('.'))
                        .put("at", cur.getLong(4) * 1000).put("size", cur.getLong(3)))
                }
            }
        } catch (_: Exception) { /* no media library access: only the kept list */ }
        val sorted = (0 until out.length()).map { out.getJSONObject(it) }.sortedByDescending { it.optLong("at") }
        return JSONArray(sorted).toString()
    }

    /** The file of one download, by its id in the list. */
    fun find(c: Context, id: String): JSONObject? {
        val all = try { JSONArray(list(c)) } catch (_: Exception) { return null }
        for (i in 0 until all.length()) if (all.getJSONObject(i).optString("id") == id) return all.getJSONObject(i)
        return null
    }

    /** Deletes the file from the phone (it is one this app saved) and forgets it. */
    @Synchronized fun delete(c: Context, id: String): Boolean {
        val o = find(c, id) ?: return false
        val ok = try { c.contentResolver.delete(Uri.parse(o.optString("uri")), null, null) > 0 } catch (_: Exception) { false }
        val list = read(c)
        val keep = JSONArray()
        for (i in 0 until list.length()) list.optJSONObject(i)?.let { if (it.optString("id") != id) keep.put(it) }
        prefs(c).edit().putString("list", keep.toString()).apply()
        return ok
    }

    /** The file's size in bytes, or -1 when it is gone. */
    fun sizeOf(c: Context, uri: Uri): Long = try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }
}
