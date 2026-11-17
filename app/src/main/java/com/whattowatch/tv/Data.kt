package com.whattowatch.tv

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---------- A movie or TV show ----------

data class Title(
    val id: Int,
    val type: String, // "movie" or "tv"
    val name: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val year: String,
    val rating: Double,
    val genreIds: List<Int> = emptyList(),
) {
    val key get() = "$type:$id"
    val typeLabel get() = if (type == "tv") "TV Show" else "Movie"
    fun posterUrl() = poster?.let { "https://image.tmdb.org/t/p/w342$it" }
    fun backdropUrl() = (backdrop ?: poster)?.let { "https://image.tmdb.org/t/p/w1280$it" }
    fun cardUrl() = backdrop?.let { "https://image.tmdb.org/t/p/w780$it" } ?: posterUrl()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("media_type", type).put("title", name)
        .put("overview", overview).put("poster_path", poster ?: JSONObject.NULL)
        .put("backdrop_path", backdrop ?: JSONObject.NULL)
        .put("release_date", year).put("vote_average", rating)
        .put("genre_ids", JSONArray(genreIds))

    companion object {
        private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

        fun fromJson(o: JSONObject, fallbackType: String? = null): Title? {
            val type = o.str("media_type").ifEmpty { fallbackType ?: return null }
            if (type != "movie" && type != "tv") return null
            val poster = o.str("poster_path").ifEmpty { null } ?: return null
            return Title(
                id = o.optInt("id"),
                type = type,
                name = o.str("title").ifEmpty { o.str("name") },
                overview = o.str("overview"),
                poster = poster,
                backdrop = o.str("backdrop_path").ifEmpty { null },
                year = o.str("release_date").ifEmpty { o.str("first_air_date") }.take(4),
                rating = o.optDouble("vote_average", 0.0),
                genreIds = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
                    ?: emptyList(),
            )
        }
    }
}

data class Row(val title: String, val items: List<Title>, val numbered: Boolean = false)

// ---------- TMDB (free movie & TV database) ----------

object Tmdb {
    private val key = BuildConfig.TMDB_KEY
    val hasKey get() = key.isNotBlank()

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val q = params.toMutableMap()
            q.putIfAbsent("language", "en-US")
            val bearer = key.length > 50 // long "read access token" vs short "API key"
            if (!bearer) q["api_key"] = key
            val qs = q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val conn = URL("https://api.themoviedb.org/3$path?$qs").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (bearer) conn.setRequestProperty("Authorization", "Bearer $key")
            try {
                if (conn.responseCode !in 200..299) throw RuntimeException("TMDB error ${conn.responseCode}")
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }

    private fun parse(o: JSONObject, type: String?): List<Title> {
        val a = o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { Title.fromJson(a.getJSONObject(it), type) }
    }

    private fun interleave(a: List<Title>, b: List<Title>): List<Title> {
        val out = ArrayList<Title>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out.add(it) }
            b.getOrNull(i)?.let { out.add(it) }
        }
        return out
    }

    suspend fun trendingAll(window: String = "week") = parse(get("/trending/all/$window"), null)
    suspend fun trending(type: String, window: String = "week") = parse(get("/trending/$type/$window"), type)

    suspend fun discover(type: String, params: Map<String, String>) =
        parse(get("/discover/$type", params + ("include_adult" to "false")), type)

    /** Movies and shows mixed together. Pass null to skip one side. */
    suspend fun both(movie: Map<String, String>?, tv: Map<String, String>?) = coroutineScope {
        val m = async { movie?.let { discover("movie", it) } ?: emptyList() }
        val t = async { tv?.let { discover("tv", it) } ?: emptyList() }
        interleave(m.await(), t.await())
    }
    suspend fun popular(type: String) = parse(get("/$type/popular"), type)
    suspend fun topRated(type: String) = parse(get("/$type/top_rated"), type)
    suspend fun recommendations(t: Title) = parse(get("/${t.type}/${t.id}/recommendations"), t.type)
    suspend fun search(q: String) = parse(get("/search/multi", mapOf("query" to q)), null)

    // ----- title logos (the stylised title art Netflix and Apple TV show) -----
    private val logoCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun cachedLogo(key: String): String? = logoCache[key]

    /** Path of the best English logo, or "" if there isn't one. */
    suspend fun logo(t: Title): String {
        logoCache[t.key]?.let { return it }
        val a = get("/${t.type}/${t.id}/images", mapOf("include_image_language" to "en,null")).optJSONArray("logos")
        var path = ""
        if (a != null && a.length() > 0) {
            for (i in 0 until a.length()) {
                val l = a.getJSONObject(i)
                if (l.optString("iso_639_1") == "en") { path = l.optString("file_path"); break }
            }
            if (path.isEmpty()) path = a.getJSONObject(0).optString("file_path")
        }
        logoCache[t.key] = path
        return path
    }

    // ----- extra details for the details page -----
    data class Extra(val runtime: Int, val seasons: Int, val collectionId: Int?)

    suspend fun extra(t: Title): Extra {
        val o = get("/${t.type}/${t.id}")
        val c = o.optJSONObject("belongs_to_collection")
        return Extra(o.optInt("runtime"), o.optInt("number_of_seasons"), c?.optInt("id")?.takeIf { it > 0 })
    }

    /** A movie series (e.g. James Bond), in release order. */
    suspend fun collection(id: Int): Row {
        val o = get("/collection/$id")
        val a = o.optJSONArray("parts") ?: JSONArray()
        val parts = (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.isNull("release_date") && it.optString("release_date").isNotEmpty() }
            .sortedBy { it.optString("release_date") }
            .mapNotNull { Title.fromJson(it, "movie") }
        return Row(o.optString("name").removeSuffix(" Collection"), parts)
    }

    private suspend fun discoverService(type: String, providers: String) = parse(
        get(
            "/discover/$type", mapOf(
                "with_watch_providers" to providers,
                "watch_region" to "US",
                "with_watch_monetization_types" to "flatrate",
                "sort_by" to "popularity.desc",
            )
        ), type
    )

    suspend fun onService(providers: String) = coroutineScope {
        val tv = async { discoverService("tv", providers) }
        val movies = async { discoverService("movie", providers) }
        interleave(tv.await(), movies.await())
    }
}

// ---------- Things saved on the box ----------

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("wtw", Context.MODE_PRIVATE)

    private fun load(k: String): List<Title> = runCatching {
        val a = JSONArray(p.getString(k, "[]"))
        (0 until a.length()).mapNotNull { Title.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(k: String, list: List<Title>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        p.edit().putString(k, a.toString()).apply()
    }

    fun watchlist(): List<Title> = load("watchlist")
    fun watched(): Set<String> = p.getStringSet("watched", emptySet())?.toSet() ?: emptySet()
    fun inWatchlist(t: Title) = watchlist().any { it.key == t.key }
    fun isWatched(t: Title) = t.key in watched()

    fun toggleWatchlist(t: Title) {
        val l = watchlist().toMutableList()
        if (!l.removeAll { it.key == t.key }) l.add(0, t)
        save("watchlist", l)
    }

    fun toggleWatched(t: Title) {
        val w = watched().toMutableSet()
        if (!w.remove(t.key)) {
            w.add(t.key)
            save("watchlist", watchlist().filter { it.key != t.key })
        }
        p.edit().putStringSet("watched", w).apply()
    }

    fun clearAll() {
        p.edit().remove("watchlist").remove("watched").apply()
    }

    fun vodApp(slot: Int): String? = p.getString("app$slot", null)
    fun setVodApp(slot: Int, pkg: String) =
        p.edit().putString("app$slot", pkg).remove("method$slot").apply()

    fun method(slot: Int): String? = p.getString("method$slot", null)
    fun setMethod(slot: Int, m: String?) = p.edit().putString("method$slot", m).apply()
}

// ---------- Opening your VOD apps ----------

data class AppInfo(val pkg: String, val label: String)

object Apps {
    fun installed(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (r in pm.queryIntentActivities(i, 0)) {
                val pkg = r.activityInfo.packageName
                if (pkg != ctx.packageName) found.putIfAbsent(pkg, r.loadLabel(pm).toString())
            }
        }
        return found.map { AppInfo(it.key, it.value) }.sortedBy { it.label.lowercase() }
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private val extraKeys = listOf(
        SearchManager.QUERY, "QUERY", "keyword", "keywords", "search", "searchText", "search_text",
        "searchKey", "search_key", "key", "q", "title", "name", "user_query", "android.intent.extra.TEXT",
    )

    private fun Intent.withTitle(title: String): Intent {
        extraKeys.forEach { putExtra(it, title) }
        return addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Activities inside the VOD app whose name mentions search, e.g. "SearchActivity". */
    fun searchActivities(ctx: Context, pkg: String): List<String> = allActivities(ctx, pkg)
        .filter { it.contains("search", ignoreCase = true) }

    @Suppress("DEPRECATION")
    fun allActivities(ctx: Context, pkg: String): List<String> = runCatching {
        ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            .activities.orEmpty().filter { it.exported }.map { it.name }
    }.getOrDefault(emptyList())

    /**
     * Every way we know of to hand a title to the VOD app, keeping only the ones the
     * box says the app can receive. Labels are stable so a chosen one can be saved.
     */
    fun methods(ctx: Context, pkg: String, title: String): List<Pair<String, Intent>> {
        val pm = ctx.packageManager
        val out = ArrayList<Pair<String, Intent>>()
        fun add(label: String, i: Intent) {
            if (i.resolveActivity(pm) != null) out.add(label to i)
        }
        add("Play-from-search request",
            Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(pkg).withTitle(title))
        add("Standard search request", Intent(Intent.ACTION_SEARCH).setPackage(pkg).withTitle(title))
        add("Web search request", Intent(Intent.ACTION_WEB_SEARCH).setPackage(pkg).withTitle(title))
        add("Share text to app",
            Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg).withTitle(title))
        for (a in searchActivities(ctx, pkg)) {
            val short = a.substringAfterLast('.')
            add("$short (search request)", Intent(Intent.ACTION_SEARCH).setClassName(pkg, a).withTitle(title))
            add("$short (direct)", Intent().setClassName(pkg, a).withTitle(title))
        }
        return out
    }

    fun launch(ctx: Context, i: Intent): Boolean = runCatching { ctx.startActivity(i) }.isSuccess

    /**
     * Opens the VOD app for this title. Uses the hand-off method chosen on the test
     * screen if there is one; otherwise just opens the app. The title is always copied
     * so it can be pasted into the app's search box. Returns a message for the viewer.
     */
    fun open(ctx: Context, pkg: String, title: String, method: String?, isTv: Boolean = false): String {
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("title", title))
        val helper = TypeSearchService.isEnabled(ctx)

        val opened = when {
            method != null -> methods(ctx, pkg, title).firstOrNull { it.first == method }
                ?.let { launch(ctx, it.second) } ?: false
            // With the typing helper on, go straight to the app's own search screen if it has one.
            helper -> searchActivities(ctx, pkg).firstOrNull()
                ?.let { launch(ctx, Intent().setClassName(pkg, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: false
            else -> false
        } || openApp(ctx, pkg)

        if (!opened) return "Couldn't open that app. Pick it again in Settings."
        if (helper) {
            TypeSearchService.request(pkg, title, isTv)
            return "Typing \"$title\"…"
        }
        return "Search for: $title"
    }

    /** Opens the app normally (inspect mode helper, and fallback). */
    fun openApp(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val main = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return launch(ctx, main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens the app's search screen (or the app) and records what's on screen. */
    fun inspect(ctx: Context, pkg: String) {
        TypeSearchService.startInspect(pkg)
        openApp(ctx, pkg)
    }

    fun openAccessibilitySettings(ctx: Context): Boolean = launch(
        ctx, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
