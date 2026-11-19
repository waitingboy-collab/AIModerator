package com.rado.aimoderator

/**
 * Бърз офлайн филтър. Хваща очевидното веднага, преди AI да отговори,
 * и работи сам, когато няма интернет, ключ или е свършил безплатният лимит.
 *
 * Правила в речника:
 *  - "дума"     – точно съвпадение
 *  - "основа*"  – всяка дума, която започва така
 *  - "две думи" – фраза, търси се в целия текст
 *
 * Пише се на български (кирилица и шльокавица), английски и турски.
 */
class LocalFilter(extraBlocked: Collection<String> = emptyList()) {

    data class Hit(val verdict: Verdict, val word: String, val reason: String)

    private class Entry(val word: String, val verdict: Verdict)

    private val exact = HashMap<String, Entry>()
    private val prefixes = ArrayList<Entry>()
    private val phrases = ArrayList<Entry>()

    init {
        add(
            Verdict.THREAT,
            "ще те убия", "ще те пребия", "ще те намеря", "знам къде живееш", "ще те смачкам",
            "ще ти счупя", "убий се", "умри", "shte te ubiq", "shte te namerq", "znam kade jiveesh",
            "kill you", "i will find you", "i know where you live", "kys", "seni öldürürüm"
        )
        add(
            Verdict.HELP,
            "искам да умра", "ще се самоубия", "самоубия", "не искам да живея", "ще се обеся",
            "iskam da umra", "ne iskam da jiveq", "want to die", "kill myself", "end my life",
            "ölmek istiyorum"
        )
        add(
            Verdict.SEXUAL,
            "секс*", "порно*", "нюдс*", "голи снимки", "гола снимка", "покажи цици", "цици*",
            "свали си", "покажи се гола", "покажи краката", "seks*", "porno*", "goli snimki",
            "pokaji cici", "cici", "sex", "sexy", "nudes", "nude", "boobs", "tits", "dick*", "porn*",
            "send pics", "show feet", "onlyfans", "çıplak"
        )
        add(
            Verdict.HATE,
            "мангал*", "чернилк*", "педераст*", "мръсни цигани", "мръсен циганин", "мръсни турци",
            "nigg*", "faggot*", "fag", "pederast*"
        )
        add(
            Verdict.INSULT,
            // български
            "идиот*", "тъп", "тъпа", "тъпо", "тъпи", "тъпак*", "тъпанар*", "глупак*", "глупачк*",
            "кретен*", "дебил*", "малоум*", "простак*", "простачк*", "боклук*", "лайн*", "курв*",
            "путк*", "педал", "шибан*", "еба*", "мамка ти", "мамицата", "майка ти", "деба",
            "урод*", "жалък", "жалка", "загубеняк*", "смотан*", "свиня", "грозна си", "грозен си",
            "грозна", "дърта", "маймун*", "млъквай", "млъкни", "разкарай се", "чупка",
            // шльокавица
            "idiot*", "tapak*", "tupak*", "glupak*", "kreten*", "debil*", "prostak*", "bokluk*",
            "kurv*", "putk*", "shiban*", "mamka ti", "mamicata", "grozna", "grozen", "mlakni",
            // английски
            "stupid", "dumb", "dumbass", "moron*", "retard*", "bitch*", "whore*", "slut*",
            "fuck you", "fck u", "stfu", "shut up", "loser", "ugly", "asshole*", "clown",
            // турски
            "orospu*", "piç", "amk", "aq", "siktir*", "amına*", "salak*", "aptal*", "gerizekal*"
        )
        add(
            Verdict.SPAM,
            "follow for follow", "f4f", "l4l", "последвай ме", "последвайте ме", "последвай профила",
            "виж профила ми", "линк в био", "линк в профила", "безплатни монети", "безплатни coins",
            "free coins", "free gifts", "link in bio", "check my profile", "check my live",
            "ела в лайва ми", "ела на лайва ми", "posledvai me", "telegram", "whatsapp", "onlyfans",
            "pratete mi", "пратете ми подарък", "пратете подарък", "дай подарък", "send gift",
            "takip et", "takip edin"
        )
        extraBlocked.forEach { put(it, Verdict.INSULT) }
    }

    private fun add(v: Verdict, vararg words: String) = words.forEach { put(it, v) }

    private fun put(raw: String, v: Verdict) {
        val star = raw.trim().endsWith("*")
        // речникът минава през същото нормализиране като текста от чата
        val norm = tokenize(raw.trim().removeSuffix("*")).joinToString(" ")
        if (norm.isEmpty()) return
        val w = if (star) "$norm*" else norm
        when {
            w.contains(' ') -> phrases += Entry(w.removeSuffix("*"), v)
            w.endsWith("*") -> prefixes += Entry(w.dropLast(1), v)
            else -> exact[w] = Entry(w, v)
        }
    }

    fun check(text: String): Hit? {
        // 1) връзки и телефони = реклама/измама
        if (URL_REGEX.containsMatchIn(text)) return Hit(Verdict.SPAM, "връзка", "Съдържа връзка")
        if (PHONE_REGEX.containsMatchIn(text)) return Hit(Verdict.SPAM, "телефон", "Съдържа телефонен номер")

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        val padded = " " + tokens.joinToString(" ") + " "

        var best: Hit? = null
        fun consider(e: Entry) {
            if (best == null || e.verdict.severity > best!!.verdict.severity) {
                best = Hit(e.verdict, e.word, reasonFor(e.verdict, e.word))
            }
        }
        for (p in phrases) if (padded.contains(" ${p.word} ")) consider(p)
        for (t in tokens) lookup(t)?.let { consider(it) }
        return best
    }

    private fun lookup(token: String): Entry? {
        exact[token]?.let { return it }
        var best: Entry? = null
        for (p in prefixes) {
            if (token.startsWith(p.word) && (best == null || p.word.length > best.word.length)) best = p
        }
        return best
    }

    private fun reasonFor(v: Verdict, word: String): String = when (v) {
        Verdict.THREAT -> "Заплаха („$word“)"
        Verdict.HELP -> "Може да има нужда от подкрепа"
        Verdict.SEXUAL -> "Сексуално съдържание"
        Verdict.HATE -> "Обида на етническа/друга основа"
        Verdict.INSULT -> "Обидна дума („$word“)"
        Verdict.SPAM -> "Реклама или просене"
        else -> v.label
    }

    companion object {
        private val URL_REGEX = Regex(
            "(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|bg|io|me|ly|gg|xyz|ru|link|site|top)\\b|t\\.me/)",
            RegexOption.IGNORE_CASE
        )
        private val PHONE_REGEX = Regex("(\\+?\\d[\\d \\-]{8,}\\d)")

        /** Малки букви, без повторени букви („идиоооот“ → „идиот“), лийт („1d10t“ → „idiot“). */
        fun tokenize(text: String): List<String> {
            val lower = text.lowercase()
            val collapsed = StringBuilder()
            var run = 0
            for (i in lower.indices) {
                val c = lower[i]
                run = if (i > 0 && lower[i - 1] == c) run + 1 else 1
                if (run <= 2) collapsed.append(c)
            }
            return collapsed.split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() }
                .map { tok ->
                    // лийт само в латински думи с поне една буква
                    if (tok.any { it in 'a'..'z' } && tok.none { it in 'а'..'я' }) deLeet(tok) else tok
                }
                .map { squeeze(it) }
        }

        private fun deLeet(t: String) = t.map {
            when (it) {
                '0' -> 'o'; '1' -> 'i'; '3' -> 'e'; '4' -> 'a'; '5' -> 's'; '7' -> 't'
                else -> it
            }
        }.joinToString("")

        /** „идиоот“ → „идиот“ за думи, които не съществуват с двойна буква в речника. */
        private fun squeeze(t: String): String {
            val sb = StringBuilder()
            for (c in t) if (sb.isEmpty() || sb.last() != c) sb.append(c)
            return sb.toString()
        }
    }
}
