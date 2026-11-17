package com.whattowatch.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types a movie title into a VOD app's search box after you press Watch.
 *
 * It does nothing unless What To Watch has just asked it to (a "pending" search that
 * expires after 20 seconds), and it only acts inside the VOD app it was asked about.
 */
class TypeSearchService : AccessibilityService() {

    class Pending(val pkg: String, val title: String, val isTv: Boolean, val until: Long) {
        var clickedSearch = false
        var busy = false
        var lastClick = 0L
    }

    companion object {
        @Volatile var pending: Pending? = null
        @Volatile var inspectPkg: String? = null
        @Volatile var inspectUntil: Long = 0
        @Volatile var lastDump: String = ""
        private val dumps = LinkedHashMap<String, String>()

        fun startInspect(pkg: String) {
            synchronized(dumps) { dumps.clear() }
            lastDump = ""
            inspectPkg = pkg
            inspectUntil = System.currentTimeMillis() + 120_000
        }

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.split(':').any {
                it.startsWith(ctx.packageName + "/") && it.endsWith("TypeSearchService")
            }
        }

        fun request(pkg: String, title: String, isTv: Boolean) {
            pending = Pending(pkg, title, isTv, System.currentTimeMillis() + if (isTv) 30_000 else 20_000)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val attemptRunnable = Runnable { attempt() }
    private val dumpRunnable = Runnable { dump() }
    private var screenName = "screen"

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.substringAfterLast('.')?.let { screenName = it }
        }

        if (inspectPkg == pkg && System.currentTimeMillis() < inspectUntil) {
            handler.removeCallbacks(dumpRunnable)
            handler.postDelayed(dumpRunnable, 2500)
        }

        val p = pending ?: return
        if (System.currentTimeMillis() > p.until) {
            pending = null
            return
        }
        if (pkg != p.pkg || p.busy) return
        // Wait for the screen to settle before looking at it.
        handler.removeCallbacks(attemptRunnable)
        handler.postDelayed(attemptRunnable, 500)
    }

    // ---------- finding things on screen ----------

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < 3000) {
            val n = queue.removeFirst()
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun AccessibilityNodeInfo.words(): String = listOfNotNull(
        text?.toString(), contentDescription?.toString(), viewIdResourceName,
    ).joinToString(" ")

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ?: node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** The app's "TV Shows" / "Series" button, if this screen has one (never "Live TV"). */
    private fun findTvTab(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val exact = setOf("tv shows", "tv show", "tv series", "series", "shows", "tv", "tvshows")
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (n in nodes) {
            for (raw in listOfNotNull(n.text?.toString(), n.contentDescription?.toString())) {
                val w = raw.trim().lowercase()
                if (w.isEmpty() || w.length > 20 || "live" in w || "search" in w) continue
                val score = when {
                    w in exact -> 2
                    "tv show" in w || "series" in w -> 1
                    else -> 0
                }
                if (score > bestScore) { best = n; bestScore = score }
            }
        }
        return best
    }

    private fun byId(nodes: List<AccessibilityNodeInfo>, idEnd: String) =
        nodes.firstOrNull { it.viewIdResourceName?.endsWith(idEnd) == true }

    private fun pressed(p: Pending, node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !click(node)) return false
        p.lastClick = System.currentTimeMillis()
        return true
    }

    private fun back(p: Pending) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        p.lastClick = System.currentTimeMillis()
    }

    /**
     * Returns true when the current screen is a search box for the right kind of title
     * (or we can't tell). Otherwise presses the buttons that lead there and returns false.
     *
     * Heat VOD: home → "TV Series" → its Search button → search screen labelled
     * "TV Series - Search". Opening the search screen directly gives "Movies - Search".
     */
    private fun rightSection(p: Pending, nodes: List<AccessibilityNodeInfo>): Boolean {
        val wantWords = if (p.isTv) listOf("series", "tv", "show") else listOf("movie", "film")
        val wrongWords = if (p.isTv) listOf("movie", "film") else listOf("series", "tv", "show")
        fun String.has(words: List<String>) = words.any { contains(it, ignoreCase = true) }

        val hasField = nodes.any { it.isEditable }
        val searchLabel = byId(nodes, "search_result_count")?.text?.toString()
        if (hasField) {
            if (searchLabel != null && searchLabel.has(wrongWords) && !searchLabel.has(wantWords)) {
                back(p) // search screen for the other section: go back and pick the right one
                return false
            }
            return true
        }

        // A section screen (Heat VOD's VodActivity shows its type, e.g. "TV Series").
        val sectionLabel = byId(nodes, "enter_type")?.text?.toString()
        if (sectionLabel != null) {
            if (sectionLabel.has(wantWords)) {
                val search = byId(nodes, "ss_text_v")
                    ?: nodes.firstOrNull { it.text?.toString()?.trim().equals("search", ignoreCase = true) }
                pressed(p, search)
            } else {
                back(p)
            }
            return false
        }

        if (!p.isTv) return true // movies: the normal steps below handle it

        // Home screen: press the TV Series / TV Shows tile.
        if (pressed(p, findTvTab(nodes))) return false
        return true // nothing recognisable: try the normal steps (search button / text box)
    }

    private fun openMain(pkg: String) {
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        runCatching {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    private fun currentNodes(pkg: String): List<AccessibilityNodeInfo>? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null
        return flatten(root)
    }

    // ---------- typing the title ----------

    private fun attempt() {
        val p = pending ?: return
        val nodes = currentNodes(p.pkg) ?: return

        // Give the app time to react after each button press.
        if (System.currentTimeMillis() - p.lastClick < 1200) {
            handler.removeCallbacks(attemptRunnable)
            handler.postDelayed(attemptRunnable, 700)
            return
        }

        // 0. Make sure we're searching the right section (Heat VOD keeps Movies and TV Series apart).
        if (!rightSection(p, nodes)) return

        // 1. A normal text box: put the title straight in.
        val field = nodes.firstOrNull { it.isEditable }
            ?: nodes.firstOrNull { it.className?.toString()?.contains("EditText") == true }
        if (field != null) {
            p.busy = true
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.title)
            }
            if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                handler.postDelayed({
                    if (Build.VERSION.SDK_INT >= 30) {
                        field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                    pending = null
                }, 400)
                return
            }
            p.busy = false
        }

        // 2. The app's own on-screen letter keyboard: press the letters one by one.
        val letterKeys = nodes.count { n ->
            val t = n.text?.toString()?.trim() ?: ""
            t.length == 1 && t[0].isLetterOrDigit()
        }
        if (letterKeys >= 20) {
            p.busy = true
            typeOnKeyboard(p, p.title.toList(), 0)
            return
        }

        // 3. No search box yet: press the app's Search button once, then look again.
        if (!p.clickedSearch) {
            val btn = nodes.firstOrNull { it.words().contains("search", ignoreCase = true) }
            if (btn != null && click(btn)) p.clickedSearch = true
        }
    }

    private fun typeOnKeyboard(p: Pending, chars: List<Char>, i: Int) {
        if (pending !== p) return
        if (i >= chars.size) {
            pending = null
            return
        }
        val nodes = currentNodes(p.pkg)
        if (nodes != null) {
            val c = chars[i]
            val key = if (c == ' ') {
                nodes.firstOrNull { n ->
                    val w = n.words()
                    w.contains("space", ignoreCase = true) || n.text?.toString() == "␣"
                }
            } else {
                nodes.firstOrNull { n -> n.text?.toString()?.trim()?.equals(c.toString(), ignoreCase = true) == true }
            }
            key?.let { click(it) } // characters the keyboard doesn't have are skipped
        }
        handler.postDelayed({ typeOnKeyboard(p, chars, i + 1) }, 180)
    }

    // ---------- inspect (for troubleshooting) ----------

    private fun dump() {
        val pkg = inspectPkg ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val lines = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (lines.size >= 70) return
            val text = n.text?.toString()?.trim()?.take(28)
            val desc = n.contentDescription?.toString()?.trim()?.take(28)
            val id = n.viewIdResourceName?.substringAfterLast('/')
            val flags = buildList {
                if (n.isEditable) add("EDIT")
                if (n.isClickable) add("click")
                if (n.isFocused) add("focused")
            }
            if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || id != null || flags.isNotEmpty()) {
                val parts = listOfNotNull(
                    n.className?.toString()?.substringAfterLast('.'),
                    id?.let { "#$it" },
                    text?.takeIf { it.isNotEmpty() }?.let { "\"$it\"" },
                    desc?.takeIf { it.isNotEmpty() }?.let { "($it)" },
                    flags.joinToString(",").ifEmpty { null },
                )
                lines.add("  ".repeat(minOf(depth, 6)) + parts.joinToString(" "))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        synchronized(dumps) {
            dumps.remove(screenName)
            dumps[screenName] = lines.joinToString("\n")
            while (dumps.size > 3) dumps.remove(dumps.keys.first())
            lastDump = dumps.entries.joinToString("\n\n") { "== ${it.key} ==\n${it.value}" }
        }
    }
}
