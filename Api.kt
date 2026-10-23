package com.roohux.app

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Item(val kind: String, val id: String, val title: String, val sub: String, val thumb: String)

enum class Filter(val label: String) { Songs("Songs"), Videos("Videos"), Channels("Artists & channels"), Playlists("Playlists") }

/** Official YouTube Data API v3 client (metadata only; playback is via the embedded IFrame player). */
object Api {
    private const val BASE = "https://www.googleapis.com/youtube/v3/"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(path: String, params: String): JSONObject = withContext(Dispatchers.IO) {
        val key = BuildConfig.YT_API_KEY
        if (key.isBlank()) throw IOException("No YouTube API key in this build. Add YT_API_KEY (GitHub secret or local.properties) and rebuild.")
        val c = URL("$BASE$path?$params&key=${enc(key)}").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 15000
            val code = try { c.responseCode } catch (e: IOException) { throw IOException("Network error. Check your connection and try again.") }
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val reason = runCatching {
                    JSONObject(body).getJSONObject("error").getJSONArray("errors").getJSONObject(0).getString("reason")
                }.getOrDefault("")
                throw IOException(
                    when {
                        reason == "quotaExceeded" -> "Daily YouTube API quota reached. Try again tomorrow."
                        code == 400 || code == 403 -> "YouTube rejected the request ($reason). Check the API key and its restrictions."
                        else -> "YouTube error $code"
                    }
                )
            }
            JSONObject(body)
        } finally { c.disconnect() }
    }

    private fun clean(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
    private fun thumb(sn: JSONObject): String {
        val t = sn.optJSONObject("thumbnails") ?: return ""
        return (t.optJSONObject("high") ?: t.optJSONObject("medium") ?: t.optJSONObject("default"))?.optString("url") ?: ""
    }

    private fun parseSearch(j: JSONObject): List<Item> {
        val a = j.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Item>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.optJSONObject("id") ?: continue
            val sn = o.optJSONObject("snippet") ?: continue
            val pair = when {
                id.has("videoId") -> "video" to id.getString("videoId")
                id.has("channelId") -> "channel" to id.getString("channelId")
                id.has("playlistId") -> "playlist" to id.getString("playlistId")
                else -> null
            } ?: continue
            out += Item(pair.first, pair.second, clean(sn.optString("title")), clean(sn.optString("channelTitle")), thumb(sn))
        }
        return out
    }

    suspend fun search(q: String, f: Filter): List<Item> {
        val type = when (f) { Filter.Channels -> "channel"; Filter.Playlists -> "playlist"; else -> "video" }
        var p = "part=snippet&type=$type&maxResults=25&q=${enc(q)}"
        if (type == "video") p += "&videoEmbeddable=true"
        if (f == Filter.Songs) p += "&videoCategoryId=10"
        return parseSearch(get("search", p))
    }

    suspend fun channelVideos(channelId: String) =
        parseSearch(get("search", "part=snippet&type=video&order=date&maxResults=25&videoEmbeddable=true&channelId=${enc(channelId)}"))

    suspend fun trending(): List<Item> {
        val region = Locale.getDefault().country.ifBlank { "US" }
        val j = get("videos", "part=snippet&chart=mostPopular&videoCategoryId=10&maxResults=25&regionCode=$region")
        val a = j.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val sn = o.getJSONObject("snippet")
            Item("video", o.getString("id"), clean(sn.optString("title")), clean(sn.optString("channelTitle")), thumb(sn))
        }
    }

    suspend fun playlistItems(id: String): List<Item> {
        val a = get("playlistItems", "part=snippet&maxResults=50&playlistId=${enc(id)}").optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Item>()
        for (i in 0 until a.length()) {
            val sn = a.getJSONObject(i).optJSONObject("snippet") ?: continue
            val vid = sn.optJSONObject("resourceId")?.optString("videoId").orEmpty()
            val title = sn.optString("title")
            if (vid.isBlank() || title == "Private video" || title == "Deleted video") continue
            out += Item("video", vid, clean(title), clean(sn.optString("videoOwnerChannelTitle")), thumb(sn))
        }
        return out
    }

    /** relatedToVideoId was removed from the API, so "related" = search by the current title. */
    suspend fun related(item: Item) = search(item.title, Filter.Videos).filter { it.id != item.id }
}
