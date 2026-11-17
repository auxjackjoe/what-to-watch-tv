package com.whattowatch.tv

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Calendar

/** One row on a page: its name and how to load it. */
class RowSpec(
    val title: String,
    val numbered: Boolean = false,
    val loadRow: (suspend () -> Row)? = null,
    val load: suspend () -> List<Title> = { emptyList() },
)

/** A genre, with TMDB's id for it on the movie side and the TV side (they differ). */
data class Genre(val name: String, val movie: Int?, val tv: Int?, val color: Long)

object Catalog {
    val year: Int = Calendar.getInstance().get(Calendar.YEAR)

    val genres = listOf(
        Genre("Action", 28, 10759, 0xFFB23A48),
        Genre("Comedy", 35, 35, 0xFFE09F3E),
        Genre("Drama", 18, 18, 0xFF5B5F97),
        Genre("Thriller", 53, null, 0xFF2D3142),
        Genre("Crime", 80, 80, 0xFF6B2737),
        Genre("Sci-Fi", 878, 10765, 0xFF1F7A8C),
        Genre("Horror", 27, null, 0xFF3D0C11),
        Genre("Romance", 10749, null, 0xFFC1437A),
        Genre("Mystery", 9648, 9648, 0xFF4A4E69),
        Genre("Adventure", 12, 10759, 0xFF2A9D8F),
        Genre("Fantasy", 14, 10765, 0xFF7B2CBF),
        Genre("Animation", 16, 16, 0xFFF4A261),
        Genre("Family", 10751, 10751, 0xFF52B788),
        Genre("Documentary", 99, 99, 0xFF6C757D),
        Genre("Reality", null, 10764, 0xFFE76F51),
        Genre("Kids", null, 10762, 0xFF4CC9F0),
        Genre("War", 10752, 10768, 0xFF495057),
        Genre("Western", 37, 37, 0xFF9C6644),
    )

    fun genreNames(t: Title): List<String> = t.genreIds.mapNotNull { id ->
        genres.firstOrNull { (if (t.type == "tv") it.tv else it.movie) == id }?.name
    }.distinct()

    // ----- query building blocks -----

    private fun dates(type: String, from: Int, to: Int): Map<String, String> =
        if (type == "movie") mapOf("primary_release_date.gte" to "$from-01-01", "primary_release_date.lte" to "$to-12-31")
        else mapOf("first_air_date.gte" to "$from-01-01", "first_air_date.lte" to "$to-12-31")

    /** The biggest titles of a period: most-voted-on, which tracks what people actually watched. */
    private fun hits(type: String, from: Int, to: Int, genre: Genre? = null) =
        dates(type, from, to) + mapOf("sort_by" to "vote_count.desc") + g(type, genre)

    private fun popular(type: String, genre: Genre? = null, extra: Map<String, String> = emptyMap()) =
        mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "50") + g(type, genre) + extra

    private fun topRated(type: String, genre: Genre? = null) = mapOf(
        "sort_by" to "vote_average.desc",
        "vote_count.gte" to if (type == "movie") "1500" else "400",
    ) + g(type, genre)

    private fun g(type: String, genre: Genre?): Map<String, String> {
        val id = if (type == "movie") genre?.movie else genre?.tv
        return if (id != null) mapOf("with_genres" to id.toString()) else emptyMap()
    }

    // ----- pages -----

    fun home() = listOf(
        RowSpec("Top 10 Today", numbered = true) { Tmdb.trendingAll("day").take(10) },
        RowSpec("Trending This Week") { Tmdb.trendingAll("week") },
    ) + (if (isHolidaySeason) listOf(christmasFavorites()) else emptyList()) + listOf(
        RowSpec("Popular on Netflix") { Tmdb.onService("8") },
        RowSpec("Popular on Max") { Tmdb.onService("1899|384") },
        RowSpec("Biggest Hits of ${year - 1}") { Tmdb.both(hits("movie", year - 1, year - 1), hits("tv", year - 1, year - 1)) },
        RowSpec("Popular on Disney+") { Tmdb.onService("337") },
        RowSpec("Popular on Prime Video") { Tmdb.onService("9|119") },
        RowSpec("Blast From the Past") { Tmdb.both(hits("movie", year - 8, year - 4), hits("tv", year - 8, year - 4)) },
        RowSpec("Popular on Hulu") { Tmdb.onService("15") },
        RowSpec("Popular on Apple TV+") { Tmdb.onService("350") },
        RowSpec("Popular on Paramount+") { Tmdb.onService("531") },
        RowSpec("Popular on Peacock") { Tmdb.onService("386|387") },
        RowSpec("Critically Acclaimed") { Tmdb.both(topRated("movie"), topRated("tv")) },
    )

    fun newAndPopular() = listOf(
        RowSpec("Top 10 Movies Today", numbered = true) { Tmdb.trending("movie", "day").take(10) },
        RowSpec("Top 10 TV Shows Today", numbered = true) { Tmdb.trending("tv", "day").take(10) },
        RowSpec("New Movies This Year") { Tmdb.discover("movie", popular("movie", extra = dates("movie", year, year))) },
        RowSpec("New TV This Year") { Tmdb.discover("tv", popular("tv", extra = dates("tv", year, year))) },
        RowSpec("Trending Movies This Week") { Tmdb.trending("movie") },
        RowSpec("Trending TV This Week") { Tmdb.trending("tv") },
        RowSpec("Last Year's Favorites") { Tmdb.both(hits("movie", year - 1, year - 1), hits("tv", year - 1, year - 1)) },
    )

    fun movies() = listOf(
        RowSpec("Top 10 Movies Today", numbered = true) { Tmdb.trending("movie", "day").take(10) },
        RowSpec("Popular Movies") { Tmdb.popular("movie") },
        RowSpec("New This Year") { Tmdb.discover("movie", popular("movie", extra = dates("movie", year, year))) },
        RowSpec("Top Rated Movies") { Tmdb.discover("movie", topRated("movie")) },
    ) + genres.filter { it.movie != null }.take(12).map { gn ->
        RowSpec("${gn.name} Movies") { Tmdb.discover("movie", popular("movie", gn)) }
    }

    fun tv() = listOf(
        RowSpec("Top 10 TV Shows Today", numbered = true) { Tmdb.trending("tv", "day").take(10) },
        RowSpec("Popular TV Shows") { Tmdb.popular("tv") },
        RowSpec("New This Year") { Tmdb.discover("tv", popular("tv", extra = dates("tv", year, year))) },
        RowSpec("Top Rated TV Shows") { Tmdb.discover("tv", topRated("tv")) },
    ) + genres.filter { it.tv != null }.distinctBy { it.tv }.map { gn ->
        RowSpec("${gn.name} TV") { Tmdb.discover("tv", popular("tv", gn)) }
    }

    fun genre(gn: Genre): List<RowSpec> {
        val m = gn.movie != null
        val t = gn.tv != null
        return listOfNotNull(
            RowSpec("Popular ${gn.name}") {
                Tmdb.both(if (m) popular("movie", gn) else null, if (t) popular("tv", gn) else null)
            },
            RowSpec("New ${gn.name}") {
                Tmdb.both(
                    if (m) popular("movie", gn, dates("movie", year - 1, year)) else null,
                    if (t) popular("tv", gn, dates("tv", year - 1, year)) else null,
                )
            },
            if (m) RowSpec("${gn.name} Movies") { Tmdb.discover("movie", popular("movie", gn)) } else null,
            if (t) RowSpec("${gn.name} TV Shows") { Tmdb.discover("tv", popular("tv", gn)) } else null,
            RowSpec("Top Rated ${gn.name}") {
                Tmdb.both(if (m) topRated("movie", gn) else null, if (t) topRated("tv", gn) else null)
            },
            RowSpec("${gn.name} From a Few Years Back") {
                Tmdb.both(
                    if (m) hits("movie", year - 6, year - 2, gn) else null,
                    if (t) hits("tv", year - 6, year - 2, gn) else null,
                )
            },
            RowSpec("${gn.name} From the 2010s") {
                Tmdb.both(if (m) hits("movie", 2010, 2019, gn) else null, if (t) hits("tv", 2010, 2019, gn) else null)
            },
            RowSpec("${gn.name} Classics") {
                Tmdb.both(if (m) hits("movie", 1970, 2009, gn) else null, if (t) hits("tv", 1970, 2009, gn) else null)
            },
        )
    }

    fun throwbacks(): List<RowSpec> = (1..7).map { back ->
        val y = year - back
        RowSpec("Biggest Hits of $y") { Tmdb.both(hits("movie", y, y), hits("tv", y, y)) }
    } + listOf(
        RowSpec("2010s Favorites") { Tmdb.both(hits("movie", 2010, 2019), hits("tv", 2010, 2019)) },
        RowSpec("2000s Favorites") { Tmdb.both(hits("movie", 2000, 2009), hits("tv", 2000, 2009)) },
        RowSpec("90s Classics") { Tmdb.both(hits("movie", 1990, 1999), hits("tv", 1990, 1999)) },
        RowSpec("80s Classics") { Tmdb.discover("movie", hits("movie", 1980, 1989)) },
    )

    /** Loads all rows of a page in parallel; rows that fail or come back empty are skipped. */
    private val gate = Semaphore(8) // be gentle with TMDB and the box's Wi-Fi

    suspend fun load(specs: List<RowSpec>): List<Row> = coroutineScope {
        specs.map { s ->
            async {
                gate.withPermit {
                    runCatching { s.loadRow?.invoke() ?: Row(s.title, s.load(), s.numbered) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
    }

    // ----- Movie series -----

    val seriesIds = listOf(
        645, 1241, 435259, 10, 86311, 131292, 131295, 284433, 531241, 448150, 748, 263, 119, 121938,
        9485, 87359, 328, 84, 295, 131635, 404609, 2344, 31562, 1570, 528, 8091, 87096, 726871,
        230, 264, 1575, 553717, 304, 135483, 14890, 90863, 86055, 2980, 8945, 33514, 8650, 102322,
        1006, 86119, 2602, 91361, 313086, 10194, 2150, 86066, 468222, 137697, 89137, 77816, 8354,
        87118, 495527, 9888,
    )

    fun series(): List<RowSpec> = seriesIds.map { id -> RowSpec("series:$id", loadRow = { Tmdb.collection(id) }) }

    // ----- Christmas -----

    private const val XMAS = "207317" // TMDB keyword "christmas"

    private fun xmas(type: String, extra: Map<String, String> = emptyMap()) =
        mapOf("with_keywords" to XMAS, "sort_by" to "vote_count.desc") + extra

    fun christmasFavorites() = RowSpec("Christmas Favorites") { Tmdb.discover("movie", xmas("movie")) }

    fun christmas(): List<RowSpec> = listOf(
        christmasFavorites(),
        RowSpec("Popular This Season") {
            Tmdb.discover("movie", xmas("movie", mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "30")))
        },
        RowSpec("New Christmas Movies") {
            Tmdb.discover("movie", xmas("movie", dates("movie", year - 3, year) + mapOf("sort_by" to "popularity.desc")))
        },
        RowSpec("Christmas Comedies") { Tmdb.discover("movie", xmas("movie", mapOf("with_genres" to "35"))) },
        RowSpec("Animated & Family Christmas") { Tmdb.discover("movie", xmas("movie", mapOf("with_genres" to "16|10751"))) },
        RowSpec("Christmas Romance") { Tmdb.discover("movie", xmas("movie", mapOf("with_genres" to "10749"))) },
        RowSpec("Christmas Classics") { Tmdb.discover("movie", xmas("movie", dates("movie", 1940, 1999))) },
        RowSpec("Christmas Action & Thrillers") { Tmdb.discover("movie", xmas("movie", mapOf("with_genres" to "28|53|80"))) },
        RowSpec("Christmas TV & Specials") { Tmdb.discover("tv", xmas("tv")) },
    )

    val isHolidaySeason: Boolean
        get() = Calendar.getInstance().get(Calendar.MONTH).let { it == Calendar.NOVEMBER || it == Calendar.DECEMBER }
}
