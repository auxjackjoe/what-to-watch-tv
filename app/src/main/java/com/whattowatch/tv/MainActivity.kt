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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF101014)
private val Accent = Color(0xFFE5383B)
private val Gold = Color(0xFFF2C14E)
private val Muted = Color(0xFFB3B3BD)
private val Field = Color(0xFF24242C)
private val RailWidth = 76.dp

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

/** Everything a browse page remembers, so coming back lands where you left off. */
class BrowseState(val specs: List<RowSpec>) {
    var rows by mutableStateOf<List<Row>>(emptyList())
    var loaded by mutableStateOf(false)
    var focusKey by mutableStateOf<String?>(null)
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
        when (val s = stack.last()) {
            Screen.Main -> {
                Box(Modifier.fillMaxSize().padding(start = RailWidth)) {
                    val open: (Title) -> Unit = { push(Screen.Details(it)) }
                    when (section) {
                        Section.Home -> Browse(
                            state = page("home") { Catalog.home() },
                            watched = watched,
                            extraRows = listOfNotNull(
                                if (watchlist.isNotEmpty()) Row("My List", watchlist) else null,
                                because,
                            ),
                            onOpen = open,
                        )
                        Section.New -> Browse(page("new") { Catalog.newAndPopular() }, watched, header = "New & Popular", onOpen = open)
                        Section.Movies -> Browse(page("movies") { Catalog.movies() }, watched, header = "Movies", onOpen = open)
                        Section.Tv -> Browse(page("tv") { Catalog.tv() }, watched, header = "TV Shows", onOpen = open)
                        Section.Throwbacks -> Browse(page("throwbacks") { Catalog.throwbacks() }, watched, header = "Throwbacks", onOpen = open)
                        Section.Genres -> GenresGrid { push(Screen.GenrePage(it)) }
                        Section.MyList -> MyListGrid(watchlist, onOpen = open)
                        Section.Search -> SearchScreen(onOpen = open)
                        Section.Settings -> SettingsScreen(
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

            is Screen.GenrePage -> Box(Modifier.fillMaxSize().padding(start = 24.dp)) {
                Browse(
                    page("genre:${s.genre.name}") { Catalog.genre(s.genre) },
                    watched,
                    header = s.genre.name,
                    onOpen = { push(Screen.Details(it)) },
                )
            }

            is Screen.Details -> Details(
                t = s.title,
                store = store,
                version = version,
                onChanged = changed,
                onOpen = { push(Screen.Details(it)) },
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

// ---------- Sidebar ----------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Sidebar(
    current: Section,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onSelect: (Section) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 250.dp else RailWidth, label = "rail")
    Box(
        Modifier
            .zIndex(2f)
            .fillMaxHeight()
            .width(width + if (expanded) 120.dp else 0.dp)
            .background(
                if (expanded) Brush.horizontalGradient(0f to Color.Black, 0.6f to Color.Black.copy(alpha = 0.92f), 1f to Color.Transparent)
                else Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.55f))
            )
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(width)
                .onFocusChanged { expanded = it.hasFocus; onFocusChange(it.hasFocus) }
                .focusProperties { enter = { focusRequester } }
                .focusGroup()
                .padding(vertical = 24.dp),
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
        focused -> Color.Black
        selected -> Color.White
        else -> Muted
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(s.icon, contentDescription = s.label, tint = tint, modifier = Modifier.size(26.dp))
            if (selected && !expanded) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 8.dp)
                        .size(width = 16.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Accent)
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.width(18.dp))
            Text(
                s.label, color = tint, fontSize = 18.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
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
        primary -> Color.White.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.18f)
    }
    val fg = if (focused || primary) Color.Black else Color.White
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(6.dp))
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

@Composable
fun Poster(
    t: Title,
    restoreFocus: Boolean,
    onFocus: (Title) -> Unit,
    onClick: (Title) -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 136.dp,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "poster")
    val fr = remember { FocusRequester() }
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            delay(60)
            runCatching { fr.requestFocus() }
        }
    }
    Box(
        modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(fr)
            .onFocusChanged { if (it.isFocused) onFocus(t) }
            .clip(RoundedCornerShape(6.dp))
            .background(Field)
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(interactionSource = src, indication = null) { onClick(t) }
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
fun PosterRow(
    row: Row,
    state: LazyListState,
    focusKey: String?,
    onFocus: (Title) -> Unit,
    onClick: (Title) -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            row.title,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 40.dp),
        )
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (row.numbered) 4.dp else 14.dp),
        ) {
            itemsIndexed(row.items.distinctBy { it.key }, key = { _, t -> t.key }) { i, t ->
                if (row.numbered) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${i + 1}",
                            style = TextStyle(
                                fontSize = 120.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF5A5A66),
                                drawStyle = Stroke(width = 5f),
                            ),
                            modifier = Modifier.offset(x = 16.dp, y = 22.dp),
                        )
                        Poster(t, restoreFocus = t.key == focusKey, onFocus = onFocus, onClick = onClick, width = 120.dp)
                    }
                } else {
                    Poster(t, restoreFocus = t.key == focusKey, onFocus = onFocus, onClick = onClick)
                }
            }
        }
    }
}

fun Title.meta(): String = listOfNotNull(
    year.ifEmpty { null },
    typeLabel,
    Catalog.genreNames(this).take(2).joinToString(" • ").ifEmpty { null },
).joinToString("   ")

@Composable
fun Rating(t: Title) {
    if (t.rating > 0) {
        Text("★ %.1f".format(t.rating), color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
    }
}

@Composable
fun Backdrop(t: Title) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(t.backdropUrl()).crossfade(400).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = Modifier.fillMaxSize(),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Bg, 0.35f to Bg.copy(alpha = 0.85f), 0.7f to Color.Transparent)
        )
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Bg)))
}

@Composable
fun Message(text: String) {
    Text(text, color = Muted, fontSize = 18.sp, modifier = Modifier.padding(48.dp))
}

@Composable
fun LoadingRows() {
    Column(Modifier.padding(start = 40.dp, top = 8.dp)) {
        repeat(2) {
            Box(Modifier.size(width = 200.dp, height = 18.dp).clip(RoundedCornerShape(4.dp)).background(Field))
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(8) {
                    Box(Modifier.width(136.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Field))
                }
            }
        }
    }
}

// ---------- Browse page (Home, Movies, TV, genres, throwbacks) ----------

@Composable
fun Browse(
    state: BrowseState,
    watched: Set<String>,
    extraRows: List<Row> = emptyList(),
    header: String? = null,
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
        (base.take(1) + extraRows + base.drop(1)).filter { it.items.isNotEmpty() }
    }
    val first = rows.firstOrNull()?.items?.firstOrNull()
    if (state.focusKey == null && first != null) {
        state.focusKey = first.key
        state.hero = first
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.5f
        val hero = state.hero
        if (hero != null) {
            Box(Modifier.fillMaxWidth().height(maxHeight * 0.72f)) { Backdrop(hero) }
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                if (header != null) {
                    Text(
                        header.uppercase(), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        modifier = Modifier.padding(start = 40.dp, top = 28.dp),
                    )
                }
                if (hero != null) {
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 40.dp, bottom = 4.dp)
                            .fillMaxWidth(0.48f)
                    ) {
                        Text(
                            hero.name, color = Color.White, fontSize = 40.sp, lineHeight = 44.sp,
                            fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Rating(hero)
                            Text(hero.meta(), color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                        }
                        Text(
                            hero.overview, color = Muted, fontSize = 15.sp, lineHeight = 21.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            when {
                !state.loaded -> LoadingRows()
                rows.isEmpty() -> Message("Couldn't load anything. Check the box's internet connection.")
                else -> LazyColumn(
                    state = state.list,
                    contentPadding = PaddingValues(bottom = 200.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(rows, key = { _, r -> r.title }) { i, row ->
                        PosterRow(
                            row = row,
                            state = state.rowStates.getOrPut(row.title) { LazyListState() },
                            focusKey = state.focusKey,
                            onFocus = { t ->
                                state.focusKey = t.key
                                state.hero = t
                                if (state.list.firstVisibleItemIndex != i || state.list.firstVisibleItemScrollOffset != 0) {
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
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp)) {
        Text("GENRES", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text(
            "Pick a genre to see what's popular, new, and from a few years back",
            color = Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        )
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 48.dp, top = 8.dp),
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
    Box(
        modifier
            .aspectRatio(16f / 8f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.45f), Color.Black)))
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(18.dp)
    ) {
        Text(gn.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.BottomStart))
    }
}

// ---------- My List ----------

@Composable
fun MyListGrid(items: List<Title>, onOpen: (Title) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp)) {
        Text("MY LIST", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        if (items.isEmpty()) {
            Text(
                "Nothing here yet. Open any movie or show and press + My List.",
                color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(136.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp, start = 8.dp, end = 8.dp),
        ) {
            itemsIndexed(items, key = { _, t -> t.key }) { i, t ->
                Poster(t, restoreFocus = i == 0, onFocus = {}, onClick = onOpen)
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
    LaunchedEffect(t.key) { more = runCatching { Tmdb.recommendations(t) }.getOrDefault(emptyList()) }

    val inList = remember(version, t.key) { store.inWatchlist(t) }
    val seen = remember(version, t.key) { store.isWatched(t) }
    val app1 = remember(version) { store.vodApp(1) }
    val app2 = remember(version) { store.vodApp(2) }

    val firstButton = remember { FocusRequester() }
    LaunchedEffect(t.key) {
        delay(100)
        runCatching { firstButton.requestFocus() }
    }

    fun watch(slot: Int, pkg: String?) {
        if (pkg == null) onPickApp(slot) else toast(Apps.open(ctx, pkg, t.name, store.method(slot)))
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(t)
        Column(Modifier.fillMaxSize().padding(top = 56.dp)) {
            Column(Modifier.padding(horizontal = 56.dp).fillMaxWidth(0.55f)) {
                Text(
                    t.name, color = Color.White, fontSize = 46.sp, lineHeight = 50.sp,
                    fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Rating(t)
                    Text(t.meta(), color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
                }
                Text(t.overview, color = Muted, fontSize = 17.sp, lineHeight = 24.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(26.dp))
            Row(Modifier.padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    "Watch in ${app1?.let { Apps.label(ctx, it) } ?: "VOD app"}",
                    modifier = Modifier.focusRequester(firstButton),
                    icon = Icons.Filled.PlayArrow,
                    primary = true,
                ) { watch(1, app1) }
                TvButton("${app2?.let { Apps.label(ctx, it) } ?: "2nd app"}", icon = Icons.Filled.PlayArrow) { watch(2, app2) }
                TvButton("My List", icon = if (inList) Icons.Filled.Check else Icons.Filled.Add) {
                    store.toggleWatchlist(t); onChanged()
                }
                TvButton(if (seen) "Watched" else "Mark Watched", icon = if (seen) Icons.Filled.Check else null) {
                    store.toggleWatched(t); onChanged()
                }
            }
            if (more.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Box(Modifier.padding(start = 16.dp)) {
                    PosterRow(
                        row = Row("More Like This", more),
                        state = rememberLazyListState(),
                        focusKey = null,
                        onFocus = {},
                        onClick = onOpen,
                    )
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

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp)) {
        Text("SEARCH", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(8.dp))
                .background(Field)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box {
                        if (q.isEmpty()) Text("Movies, shows…", color = Muted, fontSize = 22.sp)
                        inner()
                    }
                },
                modifier = Modifier.focusRequester(fr).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(136.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            itemsIndexed(results.distinctBy { it.key }, key = { _, t -> t.key }) { _, t ->
                Poster(t, restoreFocus = false, onFocus = {}, onClick = onOpen)
            }
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(
    store: Store,
    version: Int,
    onPickApp: (Int) -> Unit,
    onTest: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val ctx = LocalContext.current
    val app1 = remember(version) { store.vodApp(1)?.let { Apps.label(ctx, it) } ?: "Not chosen" }
    val app2 = remember(version) { store.vodApp(2)?.let { Apps.label(ctx, it) } ?: "Not chosen" }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fr.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().padding(start = 40.dp, top = 28.dp, end = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("SETTINGS", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text("Main VOD app (the big Watch button): $app1", color = Color.White, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Choose main VOD app", modifier = Modifier.focusRequester(fr)) { onPickApp(1) }
            TvButton("Test movie hand-off") { if (store.vodApp(1) != null) onTest(1) else onPickApp(1) }
        }
        Spacer(Modifier.height(6.dp))
        Text("Second VOD app: $app2", color = Color.White, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Choose second VOD app") { onPickApp(2) }
            TvButton("Test movie hand-off") { if (store.vodApp(2) != null) onTest(2) else onPickApp(2) }
        }
        Spacer(Modifier.height(12.dp))
        TvButton("Clear My List and watched history", onClick = onClear)
        Text(
            "To remove this app completely: box Settings → Apps → What To Watch → Uninstall.",
            color = Muted, fontSize = 15.sp,
        )
    }
}

@Composable
fun PickAppScreen(slot: Int, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { Apps.installed(ctx) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fr.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Text(
            if (slot == 1) "Pick your main VOD app" else "Pick your second VOD app",
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
        )
        Text("For example VOD Ultra or Heat VOD", color = Muted, fontSize = 17.sp)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(apps, key = { it.pkg }) { a ->
                TvButton(
                    a.label,
                    modifier = if (a == apps.first()) Modifier.focusRequester(fr) else Modifier,
                ) { onPick(a.pkg) }
            }
        }
    }
}

// ---------- Hand-off test ----------

@Composable
fun TestAppScreen(slot: Int, store: Store, version: Int, onChanged: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val pkg = store.vodApp(slot) ?: return
    val appName = remember(pkg) { Apps.label(ctx, pkg) }
    var title by remember { mutableStateOf("Inception") }
    val methods = remember(pkg, title) { Apps.methods(ctx, pkg, title) }
    val activities = remember(pkg) { Apps.allActivities(ctx, pkg) }
    val current = remember(version) { store.method(slot) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fr.requestFocus() }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Test movie hand-off: $appName", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Press Try on each one. If $appName opens showing the movie below, come back and press Use this.",
                color = Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "Now using: ${current ?: "Just open the app"}",
                color = Gold, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Test movie:  ", color = Muted, fontSize = 18.sp)
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .focusRequester(fr)
                        .width(320.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Field)
                        .padding(12.dp),
                )
            }
        }
        if (methods.isEmpty()) {
            item { Message("$appName doesn't accept any of the usual hand-off requests.") }
        }
        items(methods, key = { it.first }) { (label, intent) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.width(360.dp))
                TvButton("Try") { if (!Apps.launch(ctx, intent)) toast("That one failed to open") }
                TvButton(if (current == label) "✓ Using" else "Use this") {
                    store.setMethod(slot, label); onChanged(); toast("Saved")
                }
            }
        }
        item {
            TvButton("Use: just open the app") { store.setMethod(slot, null); onChanged(); toast("Saved") }
        }
        item {
            Text(
                "App screens found (for troubleshooting): " +
                    activities.joinToString(", ") { it.substringAfterLast('.') }.ifEmpty { "none visible" },
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
