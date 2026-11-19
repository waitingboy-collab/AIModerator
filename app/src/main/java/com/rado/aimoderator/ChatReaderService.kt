package com.rado.aimoderator

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Чете чата на TikTok LIVE, отворен на екрана.
 * Модерацията само чете. Единственото, което услугата пише, е поздрав –
 * и то само ако си включил „Автоматично“ в раздел Поздрави.
 */
class ChatReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastRun = 0L
    private var sending = false

    private val scanRunnable = Runnable {
        pending = false
        lastRun = System.currentTimeMillis()
        scan()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ModRepository.init(this)
        ModRepository.serviceConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) ModRepository.diag.lastApp = pkg
        if (!isTikTok(pkg)) return
        ModRepository.diag.tiktokEvents++
        if (!ModRepository.scanning && !ModRepository.diag.dumpRequested) return
        if (sending || pending) return
        pending = true
        // най-много ~1.5 прочита в секунда – чатът се прерисува постоянно
        val wait = (SCAN_INTERVAL_MS - (System.currentTimeMillis() - lastRun)).coerceAtLeast(0)
        handler.postDelayed(scanRunnable, wait)
    }

    private fun scan() {
        val root = tiktokRoot()
        if (root == null) { ModRepository.diag.note = "Не намирам прозореца на TikTok"; return }
        val pkg = root.packageName?.toString().orEmpty()
        if (!isTikTok(pkg)) { ModRepository.diag.note = "Активният прозорец е $pkg, не TikTok"; return }
        val counter = IntArray(1)
        val ui = toUi(root, 0, counter)
        val result = ChatParser.parse(ui)
        ModRepository.diag.apply {
            scans++
            lastNodes = counter[0]
            lastTexts = ChatParser.countTexts(ui)
            lastRows = result.rows.size
            note = if (result.rows.isEmpty()) "Екранът се чете, но чатът не е разпознат" else ""
        }
        if (ModRepository.diag.dumpRequested) {
            ModRepository.diag.dumpRequested = false
            ModRepository.onDump(pkg, ChatParser.dump(ui))
        }
        ModRepository.onVisible(result.rows, result.raw, result.joins)
    }

    /** Прозорецът на TikTok – дори ако в момента друг прозорец е „активен“ (клавиатура, известие). */
    private fun tiktokRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (isTikTok(it.packageName?.toString().orEmpty())) return it }
        return try {
            windows.mapNotNull { it.root }.firstOrNull { isTikTok(it.packageName?.toString().orEmpty()) }
                ?: rootInActiveWindow
        } catch (_: Exception) {
            rootInActiveWindow
        }
    }

    @Suppress("DEPRECATION")
    private fun toUi(n: AccessibilityNodeInfo, depth: Int, counter: IntArray): UiNode {
        counter[0]++
        val kids = ArrayList<UiNode>()
        if (depth < 40 && counter[0] < 2500) {
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                kids += toUi(c, depth + 1, counter)
                if (Build.VERSION.SDK_INT < 33) c.recycle()
            }
        }
        return UiNode(
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            className = n.className?.toString(),
            scrollable = n.isScrollable,
            children = kids
        )
    }

    // ---------- изпращане на поздрав ----------

    /**
     * Пише текста в полето за коментар на TikTok и натиска „Изпрати“.
     * Спира, ако в момента ти пишеш нещо, за да не ти изтрие текста.
     */
    fun sendToChat(text: String, done: (Boolean, String) -> Unit) {
        if (sending) { done(false, "Още изпраща предишния"); return }
        val root = tiktokRoot()
        if (root == null || !isTikTok(root.packageName?.toString().orEmpty())) {
            done(false, "TikTok не е на екрана – само предложение"); return
        }
        sending = true
        val finish = { ok: Boolean, note: String -> sending = false; done(ok, note) }

        val edit = findEditable(root)
        if (edit != null) {
            typeAndSend(text, finish)
            return
        }
        val opener = findNode(root) { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.lowercase().orEmpty()
            INPUT_HINTS.any { t.startsWith(it) }
        }
        if (opener == null || !clickNodeOrParent(opener)) {
            finish(false, "Не намерих полето за коментар")
            return
        }
        handler.postDelayed({ typeAndSend(text, finish) }, 700)
    }

    private fun typeAndSend(text: String, finish: (Boolean, String) -> Unit) {
        val root = rootInActiveWindow ?: return finish(false, "Екранът се смени")
        val edit = findEditable(root) ?: return finish(false, "Не намерих полето за коментар")
        val current = edit.text?.toString().orEmpty()
        val showingHint = Build.VERSION.SDK_INT >= 26 && edit.isShowingHintText
        if (current.isNotBlank() && !showingHint && INPUT_HINTS.none { current.lowercase().startsWith(it) }) {
            return finish(false, "Пишеш в момента – поздравът е само предложение")
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return finish(false, "TikTok не позволи да се въведе текст")
        }
        handler.postDelayed({
            val r = rootInActiveWindow ?: return@postDelayed finish(false, "Екранът се смени")
            // Бутонът се търси само до полето за писане и само с точно име,
            // за да не се натисне по грешка „Изпрати подарък“ или нещо друго.
            val area = findEditable(r)?.let { e -> e.parent?.parent ?: e.parent } ?: r
            val send = findNode(area) { n ->
                val t = (n.contentDescription ?: n.text)?.toString()?.lowercase()?.trim().orEmpty()
                SEND_WORDS.any { t == it } && FORBIDDEN.none { t.contains(it) }
            }
            val ok = when {
                send != null && clickNodeOrParent(send) -> true
                Build.VERSION.SDK_INT >= 30 -> findEditable(r)?.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                ) == true
                else -> false
            }
            if (ok) finish(true, "Изпратено") else finish(false, "Не намерих бутона „Изпрати“")
        }, 400)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNode(root) { it.isEditable || it.className?.toString()?.contains("EditText") == true }

    private fun clickNodeOrParent(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var steps = 0
        while (cur != null && steps < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            steps++
        }
        return false
    }

    private fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000) {
            val n = queue.removeFirst()
            visited++
            if (pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        ModRepository.serviceConnected = false
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: ChatReaderService? = null
            private set

        private const val SCAN_INTERVAL_MS = 700L

        fun isTikTok(pkg: String): Boolean =
            pkg in TIKTOK_PACKAGES || pkg.contains("musically") || pkg.contains("tiktok") || pkg.contains("trill")

        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",      // TikTok
            "com.ss.android.ugc.trill",      // TikTok (някои региони)
            "com.zhiliaoapp.musically.go"    // TikTok Lite
        )
        private val INPUT_HINTS = listOf(
            "кажи нещо", "добави коментар", "напиши коментар", "коментирай", "say something", "add comment", "comment"
        )
        private val SEND_WORDS = listOf("изпрати", "изпращане", "публикувай", "send", "post")
        private val FORBIDDEN = listOf("подар", "gift", "монет", "coin", "покан", "invite")
    }
}
