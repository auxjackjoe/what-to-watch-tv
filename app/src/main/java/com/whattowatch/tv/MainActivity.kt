package com.whattowatch.tv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val Bg = Color(0xFF0A0A0D)
internal val Accent = Color(0xFFE50914)
internal val Gold = Color(0xFFF5C518)
internal val Muted = Color(0xFFA7A7B0)
internal val Field = Color(0xFF1E1E24)
internal val RailWidth = 64.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App(store) }
        }
    }
}

enum class Section(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Filled.Search),
    Home("Home", Icons.Filled.Home),
    New("New & Popular", Icons.Filled.Whatshot),
    Movies("Movies", Icons.Filled.Movie),
    Tv("TV Shows", Icons.Filled.Tv),
    Series("Movie Series", Icons.Filled.VideoLibrary),
    Christmas("Christmas", Icons.Filled.AcUnit),
    Genres("Genres", Icons.Filled.Category),
    Throwbacks("Throwbacks", Icons.Filled.History),
    MyList("My List", Icons.Filled.Bookmark),
    Settings("Settings", Icons.Filled.Settings),
}

sealed class Screen {
    data object Main : Screen()
    data class Details(val title: Title) : Screen()
    data class GenrePage(val genre: Genre) : Screen()
    data class PickApp(val slot: Int) : Screen()
    data class TestApp(val slot: Int) : Screen()
}

/** Everything a browse page remembers, so coming back lands exactly where you left off. */
class BrowseState(val specs: List<RowSpec>) {
    var rows by mutableStateOf<List<Row>>(emptyList())
    var loaded by mutableStateOf(false)
    var focusKey by mutableStateOf<String?>(null) // "<row title>|<title key>"
    var focusRow = 0
    var hero by mutableStateOf<Title?>(null)
    val list = LazyListState()
    val rowStates = mutableMapOf<String, LazyListState>()
}

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Main)) }
    var section by remember { mutableStateOf(Section.Home) }
    var version by remember { mutableIntStateOf(0) }
    var sidebarFocused by remember { mutableStateOf(false) }
    val sidebarFocus = remember { FocusRequester() }
    val pages = remember { mutableMapOf<String, BrowseState>() }

    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    val changed: () -> Unit = { version++ }
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    fun page(key: String, specs: () -> List<RowSpec>) = pages.getOrPut(key) { BrowseState(specs()) }

    val watchlist = remember(version) { store.watchlist() }
    val watched = remember(version) { store.watched() }

    var because by remember { mutableStateOf<Row?>(null) }
    val seed = watchlist.firstOrNull()
    LaunchedEffect(seed?.key) {
        because = seed?.let { s ->
            runCatching { Row("Because You Added ${s.name}", Tmdb.recommendations(s)) }.getOrNull()
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }
    BackHandler(enabled = stack.size == 1 && (!sidebarFocused || section != Section.Home)) {
        if (!sidebarFocused) runCatching { sidebarFocus.requestFocus() } else section = Section.Home
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (!Tmdb.hasKey) {
            Message("No TMDB key was built into this app. Add the TMDB_KEY secret on GitHub and rebuild.")
            return@Box
        }
        val open: (Title) -> Unit = { push(Screen.Details(it)) }
        when (val s = stack.last()) {
            Screen.Main -> {
                val inset = Modifier.fillMaxSize().padding(start = RailWidth)
                when (section) {
                    Section.Home -> Browse(
                        state = page("home") { Catalog.home() },
                        watched = watched,
                        inset = RailWidth,
                        extraRows = listOfNotNull(
                            if (watchlist.isNotEmpty()) Row("My List", watchlist) else null,
                            because,
                        ),
                        onOpen = open,
                    )
                    Section.New -> Browse(page("new") { Catalog.newAndPopular() }, watched, RailWidth, header = "New & Popular", onOpen = open)
                    Section.Movies -> Browse(page("movies") { Catalog.movies() }, watched, RailWidth, header = "Movies", onOpen = open)
                    Section.Tv -> Browse(page("tv") { Catalog.tv() }, watched, RailWidth, header = "TV Shows", onOpen = open)
                    Section.Series -> Browse(page("series") { Catalog.series() }, emptySet(), RailWidth, header = "Movie Series · in order", onOpen = open, posters = true)
                    Section.Christmas -> Browse(page("christmas") { Catalog.christmas() }, watched, RailWidth, header = "Christmas", onOpen = open)
                    Section.Throwbacks -> Browse(page("throwbacks") { Catalog.throwbacks() }, watched, RailWidth, header = "Throwbacks", onOpen = open)
                    Section.Genres -> Box(inset) { GenresGrid { push(Screen.GenrePage(it)) } }
                    Section.MyList -> Box(inset) { MyListGrid(watchlist, onOpen = open) }
                    Section.Search -> Box(inset) { SearchScreen(onOpen = open) }
                    Section.Settings -> Box(inset) {
                        SettingsScreen(
                            store = store,
                            version = version,
                            onPickApp = { push(Screen.PickApp(it)) },
                            onTest = { push(Screen.TestApp(it)) },
                            onClear = { store.clearAll(); changed(); toast("My List and watched history cleared") },
                        )
                    }
                }
                Sidebar(
                    current = section,
                    focusRequester = sidebarFocus,
                    onFocusChange = { sidebarFocused = it },
                    onSelect = { section = it },
                )
            }

            is Screen.GenrePage -> Browse(
                page("genre:${s.genre.name}") { Catalog.genre(s.genre) },
                watched,
                inset = 16.dp,
                header = s.genre.name,
                onOpen = open,
            )

            is Screen.Details -> Details(
                t = s.title,
                store = store,
                version = version,
                onChanged = changed,
                onOpen = open,
                onPickApp = { push(Screen.PickApp(it)) },
                toast = toast,
            )

            is Screen.TestApp -> TestAppScreen(s.slot, store, version, changed, toast)

            is Screen.PickApp -> PickAppScreen(slot = s.slot) { pkg ->
                store.setVodApp(s.slot, pkg)
                changed()
                pop()
            }
        }
    }
}

// ---------- Sidebar (slim icons; widens with names when you move onto it) ----------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Sidebar(
    current: Section,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onSelect: (Section) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 240.dp else RailWidth, label = "rail")
    Box(
        Modifier
            .zIndex(2f)
            .fillMaxHeight()
            .width(if (expanded) width + 260.dp else width)
            .background(
                if (expanded) Brush.horizontalGradient(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.9f), 1f to Color.Transparent)
                else Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent)
            )
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(width)
                .onFocusChanged { expanded = it.hasFocus; onFocusChange(it.hasFocus) }
                .focusProperties { enter = { focusRequester } }
                .focusGroup()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Section.entries.forEach { s ->
                SidebarItem(
                    s,
                    selected = s == current,
                    expanded = expanded,
                    modifier = if (s == current) Modifier.focusRequester(focusRequester) else Modifier,
                ) { onSelect(s) }
            }
        }
    }
}

@Composable
fun SidebarItem(s: Section, selected: Boolean, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val tint = when {
        focused -> Color.White
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.5f)
    }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(22.dp).background(if (selected) Accent else Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Icon(
            s.icon, contentDescription = s.label, tint = tint,
            modifier = Modifier.size(if (focused) 24.dp else 21.dp),
        )
        if (expanded) {
            Spacer(Modifier.width(18.dp))
            Text(
                s.label,
                color = tint,
                fontSize = if (focused) 21.sp else 18.sp,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

// ---------- Building blocks ----------

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "btn")
    val bg = when {
        focused -> Color.White
        primary -> Color.White.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    val fg = if (focused) Color.Black else Color.White
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** Shared focus behaviour for cards: grow, lift, white outline, and restore focus when asked. */
@Composable
private fun cardModifier(
    restoreFocus: Boolean,
    radius: Dp,
    src: MutableInteractionSource,
    onFocused: () -> Unit,
    onClick: () -> Unit,
): Modifier {
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "card")
    val fr = remember { FocusRequester() }
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            delay(60)
            runCatching { fr.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(radius)
    return Modifier
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.shadow(18.dp, shape) else Modifier)
        .focusRequester(fr)
        .onFocusChanged { if (it.isFocused) onFocused() }
        .clip(shape)
        .background(Field)
        .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Wide 16:9 card with the title along the bottom, like Netflix and Apple TV. */
@Composable
fun LandscapeCard(t: Title, restoreFocus: Boolean, onFocus: (Title) -> Unit, onClick: (Title) -> Unit, width: Dp = 210.dp) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .then(cardModifier(restoreFocus, 8.dp, src, { onFocus(t) }, { onClick(t) }))
    ) {
        AsyncImage(
            model = t.cardUrl(),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Text(
            t.name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun Poster(t: Title, restoreFocus: Boolean, onFocus: (Title) -> Unit, onClick: (Title) -> Unit, width: Dp = 130.dp) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .then(cardModifier(restoreFocus, 8.dp, src, { onFocus(t) }, { onClick(t) }))
    ) {
        Text(t.name, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        AsyncImage(
            model = t.posterUrl(),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun CardRow(
    row: Row,
    state: LazyListState,
    focusKey: String?,
    posters: Boolean,
    onFocus: (Title) -> Unit,
    onClick: (Title) -> Unit,
    inset: Dp,
) {
    val items = remember(row) { row.items.distinctBy { it.key } }
    Column(Modifier.padding(top = 2.dp)) {
        Text(
            row.title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = inset + 32.dp),
        )
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = inset + 32.dp, end = 48.dp, top = 14.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(if (row.numbered) 2.dp else 14.dp),
        ) {
            itemsIndexed(items, key = { _, t -> t.key }) { i, t ->
                val restore = focusKey == "${row.title}|${t.key}"
                when {
                    row.numbered -> Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${i + 1}",
                            style = TextStyle(
                                fontSize = 118.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF6A6A75),
                                drawStyle = Stroke(width = 5f),
                            ),
                            modifier = Modifier.offset(x = 14.dp, y = 24.dp),
                        )
                        Poster(t, restore, onFocus, onClick, width = 112.dp)
                    }
                    posters -> Poster(t, restore, onFocus, onClick)
                    else -> LandscapeCard(t, restore, onFocus, onClick)
                }
            }
        }
    }
}

fun Title.meta(extra: Tmdb.Extra? = null): String = listOfNotNull(
    year.ifEmpty { null },
    when {
        extra != null && extra.runtime > 0 -> "${extra.runtime / 60}h ${extra.runtime % 60}m"
        extra != null && extra.seasons > 0 -> if (extra.seasons == 1) "1 Season" else "${extra.seasons} Seasons"
        else -> typeLabel
    },
    Catalog.genreNames(this).take(3).joinToString(" · ").ifEmpty { null },
).joinToString("   ")

@Composable
fun Rating(t: Title) {
    if (t.rating > 0) {
        Text("★ %.1f".format(t.rating), color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
    }
}

/** The title's logo art if TMDB has one, otherwise the name in big type. */
@Composable
fun TitleLogo(t: Title, maxWidth: Dp, maxHeight: Dp, fontSize: TextUnit) {
    var logo by remember(t.key) { mutableStateOf(Tmdb.cachedLogo(t.key)) }
    LaunchedEffect(t.key) {
        if (logo == null) {
            delay(200) // don't fetch for every poster you scroll past
            logo = runCatching { Tmdb.logo(t) }.getOrDefault("")
        }
    }
    Box(Modifier.heightIn(min = maxHeight * 0.6f, max = maxHeight), contentAlignment = Alignment.BottomStart) {
        val l = logo
        if (!l.isNullOrEmpty()) {
            AsyncImage(
                model = "https://image.tmdb.org/t/p/w500$l",
                contentDescription = t.name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight),
            )
        } else if (l != null) {
            Text(
                t.name, color = Color.White, fontSize = fontSize, lineHeight = fontSize * 1.1f,
                fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Backdrop(t: Title, dim: Float = 1f) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(t.backdropUrl()).crossfade(500).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopEnd,
        modifier = Modifier.fillMaxSize(),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Bg, 0.3f to Bg.copy(alpha = 0.85f * dim), 0.65f to Color.Transparent)
        )
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.7f to Bg.copy(alpha = 0.9f), 1f to Bg)))
}

@Composable
fun Message(text: String) {
    Text(text, color = Muted, fontSize = 18.sp, modifier = Modifier.padding(48.dp))
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = modifier,
    )
}

@Composable
fun LoadingRows(inset: Dp) {
    Column(Modifier.padding(start = inset + 32.dp, top = 8.dp)) {
        repeat(2) {
            Box(Modifier.size(width = 180.dp, height = 16.dp).clip(RoundedCornerShape(4.dp)).background(Field))
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(5) {
                    Box(Modifier.width(210.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Field))
                }
            }
        }
    }
}

// ---------- Browse page (Home, Movies, TV, Series, Christmas, genres, throwbacks) ----------

@Composable
fun Browse(
    state: BrowseState,
    watched: Set<String>,
    inset: Dp,
    extraRows: List<Row> = emptyList(),
    header: String? = null,
    posters: Boolean = false,
    onOpen: (Title) -> Unit,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        if (!state.loaded) {
            state.rows = Catalog.load(state.specs)
            state.loaded = true
        }
    }

    // Extra rows (My List etc.) go right after the first row, like Netflix's "Continue Watching".
    val rows = remember(state.rows, extraRows, watched) {
        val base = state.rows.map { r -> r.copy(items = r.items.filter { it.key !in watched }) }
        (base.take(1) + extraRows + base.drop(1)).filter { it.items.isNotEmpty() }.distinctBy { it.title }
    }
    val firstRow = rows.firstOrNull()
    val first = firstRow?.items?.firstOrNull()
    if (state.focusKey == null && first != null) {
        state.focusKey = "${firstRow.title}|${first.key}"
        state.hero = first
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hero = state.hero
        if (hero != null) Backdrop(hero)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(maxHeight * 0.5f).padding(start = inset + 32.dp)) {
                if (header != null) SectionHeader(header, Modifier.padding(top = 26.dp))
                if (hero != null) {
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.46f).padding(bottom = 4.dp)) {
                        TitleLogo(hero, maxWidth = 340.dp, maxHeight = 96.dp, fontSize = 38.sp)
                        Row(Modifier.padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Rating(hero)
                            Text(hero.meta(), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                        }
                        Text(
                            hero.overview, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, lineHeight = 20.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            when {
                !state.loaded -> LoadingRows(inset)
                rows.isEmpty() -> Message("Couldn't load anything. Check the box's internet connection.")
                else -> LazyColumn(
                    state = state.list,
                    contentPadding = PaddingValues(bottom = 240.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(rows, key = { _, r -> r.title }) { i, row ->
                        CardRow(
                            row = row,
                            state = state.rowStates.getOrPut(row.title) { LazyListState() },
                            focusKey = state.focusKey,
                            posters = posters,
                            inset = inset,
                            onFocus = { t ->
                                state.focusKey = "${row.title}|${t.key}"
                                state.hero = t
                                // Keep the row you're on lined up just under the big picture.
                                if (state.focusRow != i) {
                                    state.focusRow = i
                                    scope.launch { state.list.animateScrollToItem(i) }
                                }
                            },
                            onClick = onOpen,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Genres ----------

@Composable
fun GenresGrid(onPick: (Genre) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 40.dp, top = 26.dp)) {
        SectionHeader("Genres")
        Text(
            "Pick one to see what's popular, new, and from a few years back",
            color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 48.dp, top = 8.dp, start = 4.dp, end = 4.dp),
        ) {
            itemsIndexed(Catalog.genres) { i, gn ->
                GenreTile(gn, if (i == 0) Modifier.focusRequester(fr) else Modifier) { onPick(gn) }
            }
        }
    }
}

@Composable
fun GenreTile(gn: Genre, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "genre")
    val c = Color(gn.color)
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .aspectRatio(16f / 8f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focused) Modifier.shadow(16.dp, shape) else Modifier)
            .clip(shape)
            .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.55f), Color(0xFF111116))))
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(16.dp)
    ) {
        Text(gn.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.BottomStart))
    }
}

// ---------- My List ----------

@Composable
fun MyListGrid(items: List<Title>, onOpen: (Title) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 40.dp, top = 26.dp)) {
        SectionHeader("My List")
        if (items.isEmpty()) {
            Text(
                "Nothing here yet. Open any movie or show and press + My List.",
                color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(210.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 48.dp, start = 8.dp, end = 8.dp),
        ) {
            itemsIndexed(items, key = { _, t -> t.key }) { i, t ->
                LandscapeCard(t, restoreFocus = i == 0, onFocus = {}, onClick = onOpen)
            }
        }
    }
}

// ---------- Details ----------

@Composable
fun Details(
    t: Title,
    store: Store,
    version: Int,
    onChanged: () -> Unit,
    onOpen: (Title) -> Unit,
    onPickApp: (Int) -> Unit,
    toast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var more by remember(t.key) { mutableStateOf<List<Title>>(emptyList()) }
    var extra by remember(t.key) { mutableStateOf<Tmdb.Extra?>(null) }
    var series by remember(t.key) { mutableStateOf<Row?>(null) }
    LaunchedEffect(t.key) { more = runCatching { Tmdb.recommendations(t) }.getOrDefault(emptyList()) }
    LaunchedEffect(t.key) {
        extra = runCatching { Tmdb.extra(t) }.getOrNull()
        extra?.collectionId?.let { id ->
            series = runCatching { Tmdb.collection(id) }.getOrNull()?.let { it.copy(title = "${it.title} Series · in order") }
        }
    }

    val inList = remember(version, t.key) { store.inWatchlist(t) }
    val seen = remember(version, t.key) { store.isWatched(t) }
    val app1 = remember(version) { store.vodApp(1) }
    val app2 = remember(version) { store.vodApp(2) }

    val firstButton = remember { FocusRequester() }
    LaunchedEffect(t.key) {
        delay(120)
        runCatching { firstButton.requestFocus() }
    }

    fun watch(slot: Int, pkg: String?) {
        if (pkg == null) onPickApp(slot) else toast(Apps.open(ctx, pkg, t.name, store.method(slot)))
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(t)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 48.dp, bottom = 120.dp)) {
            item {
                Column(Modifier.padding(horizontal = 56.dp).fillMaxWidth(0.55f)) {
                    TitleLogo(t, maxWidth = 420.dp, maxHeight = 120.dp, fontSize = 44.sp)
                    Row(Modifier.padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Rating(t)
                        Text(t.meta(extra), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
                    }
                    Text(
                        t.overview, color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, lineHeight = 23.sp,
                        maxLines = 5, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        "Watch in ${app1?.let { Apps.label(ctx, it) } ?: "VOD app"}",
                        modifier = Modifier.focusRequester(firstButton),
                        icon = Icons.Filled.PlayArrow,
                        primary = true,
                    ) { watch(1, app1) }
                    TvButton(app2?.let { Apps.label(ctx, it) } ?: "2nd app", icon = Icons.Filled.PlayArrow) { watch(2, app2) }
                    TvButton("My List", icon = if (inList) Icons.Filled.Check else Icons.Filled.Add) {
                        store.toggleWatchlist(t); onChanged()
                    }
                    TvButton(if (seen) "Watched" else "Mark Watched", icon = if (seen) Icons.Filled.Check else null) {
                        store.toggleWatched(t); onChanged()
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
            series?.let { s ->
                item(key = "series") {
                    CardRow(s, rememberLazyListState(), null, posters = true, onFocus = {}, onClick = onOpen, inset = 24.dp)
                }
            }
            if (more.isNotEmpty()) {
                item(key = "more") {
                    CardRow(Row("More Like This", more), rememberLazyListState(), null, posters = false, onFocus = {}, onClick = onOpen, inset = 24.dp)
                }
            }
        }
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen(onOpen: (Title) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fr.requestFocus() }
    }
    LaunchedEffect(q) {
        if (q.trim().length < 2) return@LaunchedEffect
        delay(500)
        results = runCatching { Tmdb.search(q.trim()) }.getOrDefault(results)
    }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 40.dp, top = 26.dp)) {
        SectionHeader("Search")
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(10.dp))
                .background(Field)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 21.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box {
                        if (q.isEmpty()) Text("Movies, shows…", color = Muted, fontSize = 21.sp)
                        inner()
                    }
                },
                modifier = Modifier.focusRequester(fr).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(210.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(results.distinctBy { it.key }, key = { _, t -> t.key }) { _, t ->
                LandscapeCard(t, restoreFocus = false, onFocus = {}, onClick = onOpen)
            }
        }
    }
}
