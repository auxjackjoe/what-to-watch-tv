package com.whattowatch.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
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

    class Pending(val pkg: String, val title: String, val until: Long) {
        var clickedSearch = false
        var busy = false
    }

    companion object {
        @Volatile var pending: Pending? = null
        @Volatile var inspectPkg: String? = null
        @Volatile var lastDump: String = ""

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.split(':').any {
                it.startsWith(ctx.packageName + "/") && it.endsWith("TypeSearchService")
            }
        }

        fun request(pkg: String, title: String) {
            pending = Pending(pkg, title, System.currentTimeMillis() + 20_000)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val attemptRunnable = Runnable { attempt() }
    private val dumpRunnable = Runnable { dump() }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (inspectPkg == pkg) {
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
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
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
            if (lines.size >= 150) return
            val flags = buildList {
                if (n.isEditable) add("EDIT")
                if (n.isClickable) add("click")
                if (n.isFocused) add("focused")
            }.joinToString(",")
            val parts = listOfNotNull(
                n.className?.toString()?.substringAfterLast('.'),
                n.viewIdResourceName?.substringAfterLast('/')?.let { "#$it" },
                n.text?.toString()?.take(30)?.let { "\"$it\"" },
                n.contentDescription?.toString()?.take(30)?.let { "($it)" },
                flags.ifEmpty { null },
            )
            lines.add("  ".repeat(minOf(depth, 8)) + parts.joinToString(" "))
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        lastDump = lines.joinToString("\n")
        inspectPkg = null
    }
}
