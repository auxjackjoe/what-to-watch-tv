package com.whattowatch.tv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0E0E14)
private val Accent = Color(0xFFE8A33D)
private val Muted = Color(0xFFB8B8C4)
private val Field = Color(0xFF26262F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App(store) }
        }
    }
}

sealed class Screen {
    data object Home : Screen()
    data object Search : Screen()
    data object Settings : Screen()
    data class Details(val title: Title) : Screen()
    data class PickApp(val slot: Int) : Screen()
}

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    var version by remember { mutableIntStateOf(0) } // bumps when the watchlist etc. changes
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    val changed: () -> Unit = { version++ }
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }

    // Home data lives here so it survives opening a details page and coming back.
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var because by remember { mutableStateOf<Row?>(null) }
    var focusKey by remember { mutableStateOf<String?>(null) }
    var hero by remember { mutableStateOf<Title?>(null) }
    val columnState = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }

    LaunchedEffect(Unit) {
        if (!Tmdb.hasKey) {
            error = "No TMDB key was built into this app. Add the TMDB_KEY secret on GitHub and rebuild."
        } else {
            rows = Tmdb.homeRows()
            if (rows.isEmpty()) error = "Couldn't load movies. Check the box's internet connection."
        }
        loading = false
    }

    val watchlist = remember(version) { store.watchlist() }
    val watched = remember(version) { store.watched() }

    val seed = watchlist.firstOrNull()
    LaunchedEffect(seed?.key) {
        because = seed?.let { s ->
            runCatching { Row("Because You Added ${s.name}", Tmdb.recommendations(s)) }.getOrNull()
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (val s = stack.last()) {
            Screen.Home -> {
                val allRows = buildList {
                    if (watchlist.isNotEmpty()) add(Row("My List", watchlist))
                    because?.let { add(it) }
                    rows.forEach { r -> add(Row(r.title, r.items.filter { it.key !in watched })) }
                }.filter { it.items.isNotEmpty() }

                val first = allRows.firstOrNull()?.items?.firstOrNull()
                if (focusKey == null && first != null) {
                    focusKey = first.key
                    hero = first
                }

                Home(
                    rows = allRows,
                    hero = hero,
                    loading = loading,
                    error = error,
                    columnState = columnState,
                    rowStates = rowStates,
                    focusKey = focusKey,
                    onFocus = { t -> focusKey = t.key; hero = t },
                    onOpen = { push(Screen.Details(it)) },
                    onSearch = { push(Screen.Search) },
                    onSettings = { push(Screen.Settings) },
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

            Screen.Search -> SearchScreen(onOpen = { push(Screen.Details(it)) })

            Screen.Settings -> SettingsScreen(
                store = store,
                version = version,
                onPickApp = { push(Screen.PickApp(it)) },
                onClear = { store.clearAll(); changed(); toast("My List and watched history cleared") },
            )

            is Screen.PickApp -> PickAppScreen(slot = s.slot) { pkg ->
                store.setVodApp(s.slot, pkg)
                changed()
                pop()
            }
        }
    }
}

// ---------- Building blocks ----------

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val bg = when {
        focused -> Color.White
        primary -> Accent
        else -> Color(0x33FFFFFF)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp)
    ) {
        Text(
            text,
            color = if (focused || primary) Color.Black else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

@Composable
fun Poster(
    t: Title,
    restoreFocus: Boolean,
    onFocus: (Title) -> Unit,
    onClick: (Title) -> Unit,
    modifier: Modifier = Modifier,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "scale")
    val fr = remember { FocusRequester() }
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            delay(60)
            runCatching { fr.requestFocus() }
        }
    }
    Box(
        modifier
            .width(130.dp)
            .aspectRatio(2f / 3f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(fr)
            .onFocusChanged { if (it.isFocused) onFocus(t) }
            .clip(RoundedCornerShape(8.dp))
            .background(Field)
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
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
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            row.title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 48.dp),
        )
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(row.items.distinctBy { it.key }, key = { it.key }) { t ->
                Poster(t, restoreFocus = t.key == focusKey, onFocus = onFocus, onClick = onClick)
            }
        }
    }
}

fun Title.meta(): String = listOfNotNull(
    year.ifEmpty { null },
    typeLabel,
    if (rating > 0) "★ %.1f".format(rating) else null,
).joinToString("  ·  ")

@Composable
fun Backdrop(t: Title) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(t.backdropUrl()).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(0f to Bg, 0.45f to Bg.copy(alpha = 0.75f), 1f to Color.Transparent)
        )
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg)))
}

// ---------- Home ----------

@Composable
fun Home(
    rows: List<Row>,
    hero: Title?,
    loading: Boolean,
    error: String?,
    columnState: LazyListState,
    rowStates: MutableMap<String, LazyListState>,
    focusKey: String?,
    onFocus: (Title) -> Unit,
    onOpen: (Title) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            if (hero != null) {
                Backdrop(hero)
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 48.dp, bottom = 8.dp)
                        .fillMaxWidth(0.5f)
                ) {
                    Text(
                        hero.name, color = Color.White, fontSize = 34.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(hero.meta(), color = Accent, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        hero.overview, color = Muted, fontSize = 15.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvButton("Search", onClick = onSearch)
                TvButton("Settings", onClick = onSettings)
            }
            Text(
                "What To Watch", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 28.dp, start = 48.dp),
            )
        }

        when {
            loading -> Message("Loading…")
            error != null && rows.isEmpty() -> Message(error)
            else -> LazyColumn(
                state = columnState,
                contentPadding = PaddingValues(bottom = 60.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(rows, key = { it.title }) { row ->
                    PosterRow(
                        row = row,
                        state = rowStates.getOrPut(row.title) { LazyListState() },
                        focusKey = focusKey,
                        onFocus = onFocus,
                        onClick = onOpen,
                    )
                }
            }
        }
    }
}

@Composable
fun Message(text: String) {
    Text(text, color = Muted, fontSize = 18.sp, modifier = Modifier.padding(48.dp))
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
        if (pkg == null) onPickApp(slot) else toast(Apps.open(ctx, pkg, t.name))
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(t)
        Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
            Column(Modifier.padding(horizontal = 48.dp).fillMaxWidth(0.6f)) {
                Text(t.name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(t.meta(), color = Accent, fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp))
                Text(t.overview, color = Muted, fontSize = 17.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.padding(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvButton(
                    "▶  Watch in ${app1?.let { Apps.label(ctx, it) } ?: "VOD app"}",
                    modifier = Modifier.focusRequester(firstButton),
                    primary = true,
                ) { watch(1, app1) }
                TvButton("Watch in ${app2?.let { Apps.label(ctx, it) } ?: "2nd app"}") { watch(2, app2) }
                TvButton(if (inList) "✓  My List" else "+  My List") { store.toggleWatchlist(t); onChanged() }
                TvButton(if (seen) "Watched ✓" else "Mark Watched") { store.toggleWatched(t); onChanged() }
            }
            if (more.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
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

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)) {
        Text("Search", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = q,
            onValueChange = { q = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box {
                    if (q.isEmpty()) Text("Type a movie or show…", color = Muted, fontSize = 22.sp)
                    inner()
                }
            },
            modifier = Modifier
                .focusRequester(fr)
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(6.dp))
                .background(Field)
                .padding(16.dp),
        )
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(results.distinctBy { it.key }, key = { it.key }) { t ->
                Poster(t, restoreFocus = false, onFocus = {}, onClick = onOpen)
            }
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(store: Store, version: Int, onPickApp: (Int) -> Unit, onClear: () -> Unit) {
    val ctx = LocalContext.current
    val app1 = remember(version) { store.vodApp(1)?.let { Apps.label(ctx, it) } ?: "Not chosen" }
    val app2 = remember(version) { store.vodApp(2)?.let { Apps.label(ctx, it) } ?: "Not chosen" }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fr.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Main VOD app (the big Watch button): $app1", color = Muted, fontSize = 17.sp)
        TvButton("Choose main VOD app", modifier = Modifier.focusRequester(fr)) { onPickApp(1) }
        Text("Second VOD app: $app2", color = Muted, fontSize = 17.sp)
        TvButton("Choose second VOD app") { onPickApp(2) }
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
