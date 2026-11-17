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
        HelperStatus()
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
    var dump by remember { mutableStateOf(TypeSearchService.lastDump) }
    LaunchedEffect(Unit) {
        while (true) {
            dump = TypeSearchService.lastDump
            delay(1000)
        }
    }
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
            HelperStatus()
            Text(
                "Now using: ${current ?: "Automatic"}",
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
            TvButton("Use: automatic (recommended)") { store.setMethod(slot, null); onChanged(); toast("Saved") }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("Troubleshooting", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Press Inspect: $appName opens its search screen. Wait 3 seconds, press Back, then take a photo of the text that appears below.",
                color = Muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp),
            )
            TvButton("Inspect $appName search screen") {
                if (!TypeSearchService.isEnabled(ctx)) toast("Turn on the search typing helper first")
                else Apps.inspect(ctx, pkg)
            }
            if (dump.isNotEmpty()) {
                Text(dump, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
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

// ---------- Typing helper status ----------

@Composable
fun HelperStatus() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(TypeSearchService.isEnabled(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            on = TypeSearchService.isEnabled(ctx)
            delay(1000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            if (on) "✓ Search typing helper is ON" else "Search typing helper is OFF (Watch can't type the title for you)",
            color = if (on) Color(0xFF6BD68A) else Gold, fontSize = 17.sp,
        )
        if (!on) {
            TvButton("Turn it on") {
                if (!Apps.openAccessibilitySettings(ctx)) {
                    Toast.makeText(ctx, "Open box Settings → Accessibility manually", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "Find \"What To Watch search helper\" and turn it on", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
