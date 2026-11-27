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
 * Английските ругатни се хващат и замаскирани: „f*ck“, „sh!t“, „$hit“, „f u c k“, „fuuuck“.
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
            "kill you", "i will find you", "i know where you live", "kys", "seni öldürürüm",
            "kill yourself", "kill urself", "kill ur self", "go die", "hope you die", "hope u die",
            "i will kill", "gonna kill you", "i will hurt you"
        )
        add(
            Verdict.HELP,
            "искам да умра", "ще се самоубия", "самоубия", "не искам да живея", "ще се обеся",
            "iskam da umra", "ne iskam da jiveq", "want to die", "kill myself", "end my life",
            "ölmek istiyorum", "suicidal", "end it all", "hurt myself", "cut myself"
        )
        add(
            Verdict.SEXUAL,
            "секс*", "порно*", "нюдс*", "голи снимки", "гола снимка", "покажи цици", "цици*",
            "свали си", "покажи се гола", "покажи краката", "seks*", "porno*", "goli snimki",
            "pokaji cici", "cici", "sex", "sexy", "nudes", "nude", "boobs", "tits", "dick*", "porn*",
            "send pics", "show feet", "onlyfans", "çıplak",
            "send nudes", "show boobs", "show tits", "show your body", "horny", "pussy*",
            "cock", "cocks", "suck my", "sit on my face", "take it off", "take off your", "booty"
        )
        add(
            Verdict.HATE,
            "мангал*", "чернилк*", "педераст*", "мръсни цигани", "мръсен циганин", "мръсни турци",
            "nigg*", "faggot*", "fag", "pederast*", "chink*", "spic", "kike*", "tranny*", "go back to your country"
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
            "fuck u", "fuck off", "fuck ur", "fuck your", "fuck this", "screw you", "motherfuck*", "fucker*",
            "cunt*", "twat*", "wanker*", "prick", "bastard*", "dickhead*", "jerk", "idiot", "pathetic",
            "you suck", "u suck", "ur trash", "you re trash", "you are trash", "ur ugly", "you re ugly",
            "you are ugly", "nobody likes you", "no one likes you", "nobody cares", "no one cares",
            "shut the fuck up", "gtfo", "get lost", "fat pig", "fatass", "fat ass",
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
            "takip et", "takip edin",
            // английски
            "follow me", "follow back", "follow my", "check my bio", "check bio", "check my page",
            "visit my profile", "click my profile", "go to my live", "join my live", "come to my live",
            "dm me", "message me", "add me", "sub4sub", "s4s", "gift me", "send me a gift", "send me a rose",
            "send me coins", "give me coins", "snap me", "snapchat", "cashapp", "cash app", "paypal me",
            "free followers", "free likes", "earn money", "make money fast", "crypto investment"
        )
        add(
            Verdict.CHECK,
            // ругатни, които не са насочени към човек – провери (AI ще реши)
            "fuck*", "fck", "fk", "shit*", "bullshit", "bitchy", "trash", "garbage", "go away"
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
        Verdict.CHECK -> "Ругатня или груб тон („$word“)"
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

        /** Ругатни, които често се пишат със звездички: „f*ck“, „sh**“, „b***h“. Първото съвпадение печели. */
        private val MASK_ROOTS = listOf(
            "fuck", "fucking", "fucker", "shit", "bitch", "cunt", "dick", "slut", "whore",
            "asshole", "bastard", "pussy", "nigger", "faggot", "retard"
        )
        private val MASKED = Regex("[a-z]+[*#][a-z*#]*")
        private val SYMBOL_IN_WORD = Regex("(?<=[a-z0-9])[!@$|]+(?=[a-z0-9])")
        private val DOLLAR_START = Regex("(?<![\\p{L}\\p{N}])\\$(?=[a-z])")
        private val DOLLAR_END = Regex("(?<=[a-z])\\$+(?![\\p{L}\\p{N}])")

        /** „f*ck“ → „fuck“, „sh!t“ → „shit“, „a$$hole“ → „asshole“, „$hit“ → „shit“. */
        fun unmask(lower: String): String {
            var t = SYMBOL_IN_WORD.replace(lower) { m ->
                m.value.map { c -> when (c) { '!', '|' -> 'i'; '@' -> 'a'; else -> 's' } }.joinToString("")
            }
            t = DOLLAR_START.replace(t, "s")
            t = DOLLAR_END.replace(t) { m -> "s".repeat(m.value.length) }
            t = MASKED.replace(t) { m -> maskToRoot(m.value) ?: m.value }
            return t
        }

        private fun maskToRoot(token: String): String? {
            val letters = token.count { it in 'a'..'z' }
            if (letters < 1 || token.length < 3) return null
            // всяка поредица от * = 1..(брой+1) букви
            val pattern = Regex(
                "^" + Regex("[*#]+|[a-z]+").findAll(token).joinToString("") { part ->
                    if (part.value[0] == '*' || part.value[0] == '#') "[a-z]{1,${part.value.length + 1}}" else part.value
                } + "$"
            )
            return MASK_ROOTS.firstOrNull { pattern.matches(it) }
        }

        /** Малки букви, без повторени букви („идиоооот“ → „идиот“), лийт („1d10t“ → „idiot“), маски („f*ck“). */
        fun tokenize(text: String): List<String> {
            val lower = unmask(text.lowercase())
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
                .let { withSpelledOut(it) }
        }

        /** „f u c k“ / „f.u.c.k“ → добавя и „fuck“ (оригиналните думи остават). */
        private fun withSpelledOut(tokens: List<String>): List<String> {
            val extra = ArrayList<String>()
            var run = StringBuilder()
            fun flush() {
                if (run.length >= 3) extra += squeeze(run.toString())
                run = StringBuilder()
            }
            for (t in tokens) if (t.length == 1 && t[0] in 'a'..'z') run.append(t[0]) else flush()
            flush()
            return if (extra.isEmpty()) tokens else tokens + extra
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
