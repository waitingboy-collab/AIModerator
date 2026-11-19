package com.rado.aimoderator

/**
 * Опростено копие на дървото с изгледи на екрана.
 * Отделено е от AccessibilityNodeInfo, за да може парсерът да се тества без телефон.
 */
data class UiNode(
    val text: String? = null,
    val desc: String? = null,
    val className: String? = null,
    val scrollable: Boolean = false,
    val children: List<UiNode> = emptyList()
)

/**
 * Намира чата на TikTok LIVE на екрана и вади редовете „потребител → съобщение“.
 *
 * TikTok крие имената на изгледите си, затова се ползва евристика:
 * търси се списък, чиито елементи съдържат по 1–4 текста. Това е чатът.
 */
object ChatParser {

    data class ParseResult(
        val rows: List<ChatRow>,
        val raw: List<String>,
        /** Имена от редове „X се присъедини“, видими в момента. */
        val joins: List<String> = emptyList()
    )

    private val JOIN_PATTERNS = listOf("се присъедини", "joined", "is here", "katıldı")
    /** Служебни редове: подаръци, последвания, споделяния. Само кратки и с точна форма. */
    private val SYSTEM_REGEX = listOf(
        Regex("^(изпрати|подари|sent|gifted|gönderdi)\\s+\\S+(\\s*[x×]\\s*\\d+)?$", RegexOption.IGNORE_CASE),
        Regex(
            "^(последва|сподели|се абонира|followed|shared|subscribed|takip etti)(\\s|$).{0,25}$",
            RegexOption.IGNORE_CASE
        )
    )
    private val UI_NOISE = listOf(
        "добави коментар", "кажи нещо", "напиши коментар", "say something", "add comment",
        "comment...", "type a message", "welcome to tiktok live", "добре дошли в tiktok live",
        "tiktok live", "moderator", "модератор"
    )
    private val LEVEL_REGEX = Regex("^(level|lv|lvl|ниво)\\s*\\d+$", RegexOption.IGNORE_CASE)
    private val NUMBER_REGEX = Regex("^[\\d.,kKmM+ ]{1,6}$")

    fun parse(root: UiNode): ParseResult {
        val candidates = ArrayList<UiNode>()
        collectLists(root, candidates, 0)

        var bestRows: List<List<String>> = emptyList()
        var bestScore = 0
        for (list in candidates) {
            val rows = list.children.map { rowTexts(it) }.filter { it.isNotEmpty() }
            val chatLike = rows.count { r -> r.size in 1..4 && r.sumOf { it.length } in 2..400 }
            val isList = isListLike(list)
            // обикновен контейнер (не списък) трябва да има поне 3 реда, за да не хванем бутони
            if (!isList && chatLike < 3) continue
            val score = chatLike + if (isList) 3 else 0
            if (score > bestScore) {
                bestScore = score
                bestRows = rows
            }
        }
        if (bestRows.size < 2) return ParseResult(emptyList(), emptyList())

        return ParseResult(
            bestRows.mapNotNull { toChatRow(it) },
            bestRows.map { it.joinToString(" | ") },
            bestRows.mapNotNull { joinName(it) }
        )
    }

    /** „Иван се присъедини“ / [„Иван“, „се присъедини“] → „Иван“. */
    fun joinName(texts: List<String>): String? {
        if (texts.isEmpty() || texts.size > 3) return null
        // В ред от няколко изгледа последният трябва да е само „се присъедини“,
        // иначе е обикновено съобщение, което случайно съдържа думата.
        if (texts.size >= 2) {
            val last = texts.last().lowercase().trim().trimEnd('.', '!', ' ')
            if (JOIN_PATTERNS.none { last == it }) return null
            return cleanUser(texts[texts.size - 2]).takeIf { it != "?" && it.length <= 40 }
        }
        val joined = texts.joinToString(" ").trim().trimEnd('.', '!', ' ')
        val lower = joined.lowercase()
        for (p in JOIN_PATTERNS) {
            val idx = lower.length - p.length
            if (idx > 0 && lower.endsWith(p)) {
                val name = cleanUser(joined.substring(0, idx))
                return name.takeIf { it != "?" && it.length <= 40 }
            }
        }
        return null
    }

    private fun isListLike(n: UiNode): Boolean {
        val cls = n.className.orEmpty()
        return n.scrollable || cls.contains("RecyclerView") || cls.contains("ListView")
    }

    /** Кандидати за чат: списъци и всякакви контейнери с поне 2 деца. */
    private fun collectLists(n: UiNode, out: MutableList<UiNode>, depth: Int) {
        if (depth > 60) return
        if (n.children.size >= 2) out += n
        n.children.forEach { collectLists(it, out, depth + 1) }
    }

    /** Текстово описание на целия екран – за отстраняване на проблеми. */
    fun dump(root: UiNode, maxLines: Int = 3000): String {
        val sb = StringBuilder()
        var lines = 0
        fun walk(n: UiNode, depth: Int) {
            if (lines >= maxLines) return
            val cls = n.className.orEmpty().substringAfterLast('.')
            val parts = ArrayList<String>()
            parts += cls.ifEmpty { "?" }
            n.text?.takeIf { it.isNotBlank() }?.let { parts += "\"" + it.replace('\n', ' ').take(120) + "\"" }
            n.desc?.takeIf { it.isNotBlank() }?.let { parts += "[" + it.replace('\n', ' ').take(120) + "]" }
            if (n.scrollable) parts += "(scroll)"
            if (n.children.isNotEmpty()) parts += "{${n.children.size}}"
            sb.append("  ".repeat(depth.coerceAtMost(30))).append(parts.joinToString(" ")).append('\n')
            lines++
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return sb.toString()
    }

    fun countTexts(n: UiNode): Int =
        (if (!n.text.isNullOrBlank() || !n.desc.isNullOrBlank()) 1 else 0) + n.children.sumOf { countTexts(it) }

    /** Всички текстове в един ред от чата, по реда на екрана. */
    fun rowTexts(row: UiNode): List<String> {
        val texts = ArrayList<String>()
        val descs = ArrayList<String>()
        fun walk(n: UiNode, depth: Int) {
            if (depth > 15) return
            n.text?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            n.desc?.trim()?.takeIf { it.isNotEmpty() }?.let { descs += it }
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(row, 0)
        val src = if (texts.isNotEmpty()) texts else descs
        return src.filter { !isNoise(it) }.distinct()
    }

    private fun isNoise(t: String): Boolean {
        val l = t.lowercase()
        return LEVEL_REGEX.matches(l) || NUMBER_REGEX.matches(l) || UI_NOISE.any { l == it || l.startsWith(it) }
    }

    fun toChatRow(texts: List<String>): ChatRow? {
        if (texts.isEmpty()) return null
        if (joinName(texts) != null) return null
        val tail = (if (texts.size >= 2) texts.drop(1).joinToString(" ") else texts[0]).trim()
        if (texts.size <= 3 && SYSTEM_REGEX.any { it.matches(tail) || it.matches(texts.last().trim()) }) return null

        val (user, msg) = if (texts.size >= 2) {
            texts[0] to texts.drop(1).joinToString(" ")
        } else {
            splitSingle(texts[0])
        }
        val cleanMsg = msg.trim()
        if (cleanMsg.isEmpty()) return null
        return ChatRow(cleanUser(user), cleanMsg)
    }

    /** „име: текст“ в един изглед. */
    private fun splitSingle(s: String): Pair<String, String> {
        for (sep in listOf(": ", "：", ":")) {
            val idx = s.indexOf(sep)
            if (idx in 1..30) return s.substring(0, idx) to s.substring(idx + sep.length)
        }
        return "?" to s
    }

    private fun cleanUser(u: String): String =
        u.trim().removePrefix("@").removeSuffix(":").trim().ifEmpty { "?" }
}
