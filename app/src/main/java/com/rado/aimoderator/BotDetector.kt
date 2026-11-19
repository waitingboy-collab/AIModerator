package com.rado.aimoderator

/**
 * Правила за ботове и спам, които не изискват AI:
 *  - едно и също съобщение 3+ пъти от един човек за минута
 *  - 6+ съобщения за 15 секунди (флууд)
 *  - еднакво съобщение от 3+ различни акаунта за 30 секунди (нападение от ботове)
 *  - име от случайни букви/цифри + повтаряне
 */
class BotDetector {

    data class Signal(val verdict: Verdict, val reason: String, val alsoUsers: List<String> = emptyList())

    private data class Msg(val user: String, val norm: String, val time: Long)

    private val history = ArrayDeque<Msg>()

    /** Връща сигнал, ако съобщението изглежда като бот/спам. */
    fun observe(user: String, text: String, now: Long): Signal? {
        while (history.isNotEmpty() && now - history.first().time > 60_000) history.removeFirst()
        val norm = LocalFilter.tokenize(text).joinToString(" ")
        val msg = Msg(user, norm, now)
        history.addLast(msg)
        if (norm.isEmpty()) return null

        val known = user != "?"

        // нападение: еднакъв текст от няколко акаунта
        if (norm.length >= 4) {
            val sameText = history.filter { it.norm == norm && now - it.time <= 30_000 }
            val users = sameText.map { it.user }.filter { it != "?" }.distinct()
            if (users.size >= 3) {
                return Signal(Verdict.BOT, "Еднакво съобщение от ${users.size} акаунта", users.filter { it != user })
            }
        }

        if (known) {
            val mine = history.filter { it.user == user }
            val repeats = mine.count { it.norm == norm }
            if (repeats >= 3) return Signal(Verdict.SPAM, "Пише едно и също $repeats пъти")
            val recent = mine.count { now - it.time <= 15_000 }
            if (recent >= 6) return Signal(Verdict.SPAM, "Флууд: $recent съобщения за 15 сек.")
            if (looksRandom(user) && repeats >= 2) return Signal(Verdict.BOT, "Случайно име и повтаря съобщения")
        }
        return null
    }

    fun reset() = history.clear()

    companion object {
        private val USER_DIGITS = Regex("^user\\d{4,}$", RegexOption.IGNORE_CASE)
        private val VOWELS = "aeiouyаъоуеиюяэы".toSet()

        /** Име, което прилича на автоматично създадено: user12345678, xk7qz9w2v… */
        fun looksRandom(name: String): Boolean {
            val n = name.lowercase().filter { it.isLetterOrDigit() }
            if (n.length < 6) return false
            if (USER_DIGITS.matches(n)) return true
            val digits = n.count { it.isDigit() }
            if (digits >= 6) return true
            if (n.length >= 8 && digits.toDouble() / n.length >= 0.4 && digits < n.length) return true
            // дълги поредици от съгласни в латиница: „xkqzrtw“
            var run = 0
            for (c in n) {
                run = if (c in 'a'..'z' && c !in VOWELS) run + 1 else 0
                if (run >= 5) return true
            }
            return false
        }
    }
}
