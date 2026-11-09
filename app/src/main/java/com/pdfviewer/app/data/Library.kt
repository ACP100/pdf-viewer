package com.pdfviewer.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RecentDoc(
    val uri: String,
    val name: String,
    val size: Long,
    val lastOpened: Long,
    val lastPage: Int,
    val pageCount: Int,
    val starred: Boolean,
)

data class UserBookmark(val page: Int, val title: String, val created: Long)

/** Recents, starred files, per-document bookmarks and reading position. */
object Library {
    private lateinit var prefs: SharedPreferences
    private lateinit var appDir: File
    private val _recents = MutableStateFlow<List<RecentDoc>>(emptyList())
    val recents: StateFlow<List<RecentDoc>> get() = _recents

    fun init(context: Context) {
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        appDir = context.filesDir
        _recents.value = load()
    }

    private fun load(): List<RecentDoc> {
        val raw = prefs.getString("recents", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentDoc(
                    o.getString("uri"), o.optString("name", "document.pdf"), o.optLong("size"),
                    o.optLong("lastOpened"), o.optInt("lastPage"), o.optInt("pageCount"), o.optBoolean("starred"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<RecentDoc>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("uri", d.uri); put("name", d.name); put("size", d.size); put("lastOpened", d.lastOpened)
                put("lastPage", d.lastPage); put("pageCount", d.pageCount); put("starred", d.starred)
            })
        }
        prefs.edit().putString("recents", arr.toString()).apply()
        _recents.value = list
    }

    fun get(uri: String): RecentDoc? = _recents.value.firstOrNull { it.uri == uri }

    fun opened(uri: String, name: String, size: Long, pageCount: Int) {
        val old = get(uri)
        val doc = RecentDoc(uri, name, size, System.currentTimeMillis(), old?.lastPage ?: 0, pageCount, old?.starred ?: false)
        persist(listOf(doc) + _recents.value.filter { it.uri != uri }.take(199))
    }

    fun updatePage(uri: String, page: Int) {
        val list = _recents.value.map { if (it.uri == uri) it.copy(lastPage = page) else it }
        persist(list)
    }

    fun setStarred(uri: String, starred: Boolean) = persist(_recents.value.map { if (it.uri == uri) it.copy(starred = starred) else it })

    fun rename(uri: String, newUri: String, name: String) =
        persist(_recents.value.map { if (it.uri == uri) it.copy(uri = newUri, name = name) else it })

    fun remove(uri: String) {
        persist(_recents.value.filter { it.uri != uri })
        thumbFile(uri).delete()
    }

    fun clearRecents() {
        persist(_recents.value.filter { it.starred })
    }

    // ------------------------------------------------------------ bookmarks

    fun bookmarks(uri: String): List<UserBookmark> {
        val raw = prefs.getString("bm_" + key(uri), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i); UserBookmark(o.getInt("page"), o.optString("title"), o.optLong("created"))
            }.sortedBy { it.page }
        }.getOrDefault(emptyList())
    }

    fun setBookmarks(uri: String, list: List<UserBookmark>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().apply { put("page", it.page); put("title", it.title); put("created", it.created) }) }
        prefs.edit().putString("bm_" + key(uri), arr.toString()).apply()
    }

    // ------------------------------------------------------------ files

    fun key(uri: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun thumbFile(uri: String): File = File(File(appDir, "thumbs").apply { mkdirs() }, key(uri) + ".png")
}

/** Persistent viewer and app settings. */
object Settings {
    private lateinit var prefs: SharedPreferences
    fun init(context: Context) { prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var horizontalScroll: Boolean
        get() = prefs.getBoolean("horizontal", false)
        set(v) = prefs.edit().putBoolean("horizontal", v).apply()
    var colorMode: Int // 0 normal, 1 night, 2 sepia
        get() = prefs.getInt("colorMode", 0)
        set(v) = prefs.edit().putInt("colorMode", v).apply()
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.edit().putBoolean("keepScreenOn", v).apply()
    var author: String
        get() = prefs.getString("author", null)?.takeIf { it.isNotBlank() } ?: "Me"
        set(v) = prefs.edit().putString("author", v).apply()
    var ttsRate: Float
        get() = prefs.getFloat("ttsRate", 1f)
        set(v) = prefs.edit().putFloat("ttsRate", v).apply()
    var reflowFontSize: Float
        get() = prefs.getFloat("reflowSize", 18f)
        set(v) = prefs.edit().putFloat("reflowSize", v).apply()
    var appTheme: Int // 0 system, 1 light, 2 dark
        get() = prefs.getInt("appTheme", 0)
        set(v) = prefs.edit().putInt("appTheme", v).apply()
    var highlightFields: Boolean
        get() = prefs.getBoolean("highlightFields", true)
        set(v) = prefs.edit().putBoolean("highlightFields", v).apply()

    fun toolColor(tool: String, default: Int): Int = prefs.getInt("color_$tool", default)
    fun setToolColor(tool: String, color: Int) = prefs.edit().putInt("color_$tool", color).apply()
    fun toolWidth(tool: String, default: Float): Float = prefs.getFloat("width_$tool", default)
    fun setToolWidth(tool: String, w: Float) = prefs.edit().putFloat("width_$tool", w).apply()
}
