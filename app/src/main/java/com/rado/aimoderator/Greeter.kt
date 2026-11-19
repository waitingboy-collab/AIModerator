package com.rado.aimoderator

enum class GreetMode(val label: String, val hint: String) {
    OFF("Изключено", "Не поздравява"),
    SUGGEST("Предложение", "Показва готов поздрав – копираш и пращаш сам"),
    AUTO("Автоматично", "Пише поздрава в чата сам, с ограничение колко често")
}

data class GreetSettings(
    val mode: GreetMode = GreetMode.OFF,
    val onJoin: Boolean = true,
    val onFirstMessage: Boolean = true,
    val templates: List<String> = DEFAULT_TEMPLATES,
    val useAi: Boolean = false,
    val aiStyle: String = "топло, весело, на „ти“",
    /** Най-малко време между два поздрава в чата. Под 30 сек. TikTok може да го сметне за спам. */
    val minIntervalSec: Int = 45,
    val maxNamesPerMessage: Int = 4
) {
    companion object {
        const val MIN_INTERVAL_SEC = 30
        const val MAX_CHARS = 150
        val DEFAULT_TEMPLATES = listOf(
            "Добре дошли, {name}! 👋",
            "Здравейте, {name}! Радвам се, че сте тук 😊",
            "{name}, добре дошли в лайва! 🙌",
            "Привет, {name}! Настанявайте се 😄"
        )
    }
}

/**
 * Събира новите зрители и решава кога и кого да поздрави.
 * Обединява няколко имена в едно съобщение, за да не пълни чата.
 * Никога не поздравява маркирани, подозрителни или вече поздравени.
 */
class Greeter(private val random: java.util.Random = java.util.Random()) {

    private val pending = LinkedHashMap<String, Long>() // име → кога е видяно
    private val greeted = HashSet<String>()
    private val blocked = HashSet<String>()
    private var lastGreetAt = 0L
    private var lastTemplate = -1
    var greetCount = 0
        private set

    fun offer(name: String, now: Long) {
        val key = key(name)
        if (name == "?" || name.isBlank() || key in greeted || key in blocked || pending.containsKey(name)) return
        if (BotDetector.looksRandom(name)) return
        pending[name] = now
    }

    /** Маркиран или подозрителен – не го поздравявай. */
    fun block(name: String) {
        val key = key(name)
        blocked += key
        pending.keys.removeAll { key(it) == key }
    }

    /** Имената, които да поздравим сега, или null ако още е рано. */
    fun due(now: Long, s: GreetSettings): List<String>? {
        // много стари чакащи (напр. при наплив) просто ги пропускаме
        pending.entries.removeAll { now - it.value > 120_000 }
        if (s.mode == GreetMode.OFF || pending.isEmpty()) return null

        val interval = maxOf(s.minIntervalSec, GreetSettings.MIN_INTERVAL_SEC) * 1000L
        if (now - lastGreetAt < interval) return null
        // изчакай малко, за да се съберат няколко имена в едно съобщение
        val oldest = pending.values.minOrNull() ?: return null
        if (now - oldest < 4_000 && pending.size < s.maxNamesPerMessage) return null

        val names = pending.keys.take(s.maxNamesPerMessage.coerceIn(1, 6))
        names.forEach { pending.remove(it); greeted += key(it) }
        lastGreetAt = now
        greetCount++
        return names
    }

    fun fromTemplate(names: List<String>, s: GreetSettings): String {
        val templates = s.templates.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { GreetSettings.DEFAULT_TEMPLATES }
        var idx = random.nextInt(templates.size)
        if (templates.size > 1 && idx == lastTemplate) idx = (idx + 1) % templates.size
        lastTemplate = idx
        val t = templates[idx]
        // ако стане твърде дълго, махаме имена отзад
        var n = names.size
        while (n >= 1) {
            val text = fill(t, names.take(n))
            if (text.length <= GreetSettings.MAX_CHARS || n == 1) return text.take(GreetSettings.MAX_CHARS)
            n--
        }
        return fill(t, names.take(1)).take(GreetSettings.MAX_CHARS)
    }

    fun reset() {
        pending.clear(); greeted.clear(); blocked.clear()
        lastGreetAt = 0L; greetCount = 0
    }

    val pendingCount: Int get() = pending.size

    companion object {
        fun key(name: String) = name.trim().lowercase()

        fun joinNames(names: List<String>): String = when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " и " + names.last()
        }

        fun fill(template: String, names: List<String>): String {
            val joined = joinNames(names)
            return if (template.contains("{name}")) template.replace("{name}", joined) else "$template $joined"
        }

        /** Прави AI поздрава безопасен за чата: една линия, без връзки, до 150 знака. */
        fun sanitize(text: String): String {
            val oneLine = text.replace(Regex("\\s+"), " ").trim()
            val noLinks = oneLine.replace(Regex("(https?://\\S+|www\\.\\S+)"), "").trim()
            return noLinks.take(GreetSettings.MAX_CHARS)
        }
    }
}
