package com.rado.aimoderator

data class EngineSettings(
    val ai: AiSettings = AiSettings(),
    /** Модератори, приятели – никога не се маркират. */
    val whitelist: Set<String> = emptySet(),
    /** Твоето име в TikTok – твоите съобщения не се проверяват и не се поздравяваш. */
    val myName: String = "",
    val extraBlocked: List<String> = emptyList(),
    val greet: GreetSettings = GreetSettings(),
    /** Следени профили – всяко тяхно съобщение се маркира. */
    val watchlist: Set<String> = emptySet(),
    /** Водещият на лайва (username от раздел Лайв) – неговите съобщения не се маркират. */
    val hostName: String = "",
    /** Показва въпросите и споменаванията на водещия в отделен списък. */
    val highlightImportant: Boolean = true
)

/**
 * Сърцето на модератора, без нищо от Android – за да се тества на компютър.
 * Всички методи са synchronized: викат се от услугата, от AI нишката и от екрана.
 */
class ModerationEngine(
    settings: EngineSettings = EngineSettings(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    var settings: EngineSettings = settings
        @Synchronized set(value) {
            field = value
            filter = LocalFilter(value.extraBlocked)
        }

    private var filter = LocalFilter(settings.extraBlocked)
    private val bots = BotDetector()
    val greeter = Greeter()

    private val messages = LinkedHashMap<Long, ChatMessage>()
    private val viewers = LinkedHashMap<String, Viewer>()
    private val aiQueue = ArrayDeque<Long>()
    private var nextId = 1L

    // защита от двойно броене: чатът се прерисува и същите редове идват пак и пак
    private class Seen(var count: Int, var missingSince: Long = 0L, var missedScans: Int = 0)
    private val seen = HashMap<String, Seen>()
    private val recentlySent = ArrayDeque<Pair<String, Long>>()

    /** Резултат от един прочит на екрана: какво ново е маркирано (за известия). */
    data class Update(val newMessages: Int, val newFlags: List<ChatMessage>)

    @Synchronized
    fun onVisible(rows: List<ChatRow>, joins: List<String>): Update {
        val now = clock()
        return process(dedup(rows, now), joins, now)
    }

    /** Режим „По username“: страницата праща само новите съобщения – без защита от двойно броене. */
    @Synchronized
    fun onNewMessages(rows: List<ChatRow>, joins: List<String>): Update = process(rows, joins, clock())

    private fun process(fresh: List<ChatRow>, joins: List<String>, now: Long): Update {
        val flags = ArrayList<ChatMessage>()
        var added = 0

        if (settings.greet.onJoin) joins.forEach { if (!isMe(it)) greeter.offer(it, now) }

        for (row in fresh) {
            if (isMe(row.user) || isOwnEcho(row.text, now)) continue
            val m = addMessage(row, now)
            added++
            if (m.isFlagged) flags += m
        }
        return Update(added, flags)
    }

    private fun addMessage(row: ChatRow, now: Long): ChatMessage {
        val s = settings
        val id = nextId++
        var m = ChatMessage(id, row.user, row.text, now)

        val trusted = row.user != "?" &&
            (s.whitelist.any { Greeter.key(it) == Greeter.key(row.user) } || isHost(row.user))
        if (trusted) {
            m = m.copy(verdict = Verdict.OK, source = Source.WHITELIST)
        } else {
            val bot = bots.observe(row.user, row.text, now)
            val hit = filter.check(row.text)
            when {
                bot != null && (hit == null || hit.verdict.severity <= bot.verdict.severity) -> {
                    m = m.copy(verdict = bot.verdict, reason = bot.reason, source = Source.RULE)
                    bot.alsoUsers.forEach { u -> flagUserRecent(u, bot) }
                }
                hit != null -> {
                    m = m.copy(verdict = hit.verdict, reason = hit.reason, source = Source.FILTER)
                    if (s.ai.enabled) aiQueue.addLast(id) // AI потвърждава или отменя
                }
                s.ai.enabled -> aiQueue.addLast(id) // verdict = null → чака AI
                else -> m = m.copy(verdict = Verdict.OK, source = Source.FILTER)
            }
            if (isWatched(row.user)) {
                val cur = m.verdict
                if (cur == null || cur.severity < Verdict.WATCH.severity) {
                    m = m.copy(verdict = Verdict.WATCH, reason = "Следен профил", source = Source.RULE)
                    aiQueue.remove(id)
                } else {
                    m = m.copy(reason = m.reason + " · следен профил")
                }
            }
        }
        if (!m.isFlagged && s.highlightImportant && !isHost(row.user)) {
            importantReason(row.text, s.hostName)?.let { m = m.copy(important = it) }
        }
        messages[id] = m
        trim()
        updateViewer(m, now)

        if (m.isFlagged) greeter.block(m.user)
        else if (settings.greet.onFirstMessage && (viewers[m.user]?.messages ?: 0) == 1) greeter.offer(m.user, now)
        return m
    }

    /** При нападение от ботове маркира и предишните съобщения на останалите акаунти. */
    private fun flagUserRecent(user: String, signal: BotDetector.Signal) {
        val last = messages.values.lastOrNull { it.user == user && !it.isFlagged } ?: return
        messages[last.id] = last.copy(verdict = signal.verdict, reason = signal.reason, source = Source.RULE)
        greeter.block(user)
    }

    private fun updateViewer(m: ChatMessage, now: Long) {
        if (m.user == "?") return
        val v = viewers[m.user] ?: Viewer(m.user, suspiciousName = BotDetector.looksRandom(m.user))
        viewers[m.user] = v.copy(
            messages = v.messages + 1,
            flags = v.flags + if (m.isFlagged) 1 else 0,
            lastText = m.text,
            lastTime = now
        )
    }

    private fun dedup(rows: List<ChatRow>, now: Long): List<ChatRow> {
        val counts = rows.groupingBy { key(it) }.eachCount()
        val out = ArrayList<ChatRow>()
        val emitted = HashMap<String, Int>()
        for (row in rows) {
            val k = key(row)
            val st = seen[k]
            val known = st?.count ?: 0
            val visible = counts[k] ?: 0
            val already = emitted[k] ?: 0
            if (already < visible - known) {
                out += row
                emitted[k] = already + 1
            }
        }
        for ((k, c) in counts) {
            val st = seen.getOrPut(k) { Seen(0) }
            st.count = maxOf(st.count, c)
            st.missingSince = 0L
            st.missedScans = 0
        }
        // ред, който го няма в 3+ поредни прочита и 8+ секунди, е излязъл от екрана
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val (k, st) = it.next()
            if (k in counts) continue
            if (st.missingSince == 0L) st.missingSince = now
            st.missedScans++
            if (st.missedScans >= 3 && now - st.missingSince >= 8_000) it.remove()
        }
        return out
    }

    private fun key(r: ChatRow) = r.user + "\u0001" + r.text

    private fun isHost(user: String) =
        settings.hostName.isNotBlank() && Greeter.key(user.removePrefix("@")) == Greeter.key(settings.hostName.removePrefix("@"))

    private fun isWatched(user: String) =
        user != "?" && settings.watchlist.any { Greeter.key(it.removePrefix("@")) == Greeter.key(user.removePrefix("@")) }

    private fun isMe(user: String) =
        settings.myName.isNotBlank() && Greeter.key(user.removePrefix("@")) == Greeter.key(settings.myName.removePrefix("@"))

    private fun isOwnEcho(text: String, now: Long): Boolean {
        while (recentlySent.isNotEmpty() && now - recentlySent.first().second > 120_000) recentlySent.removeFirst()
        val t = text.trim()
        return recentlySent.any { it.first == t }
    }

    /** Запомня изпратен поздрав, за да не го проверява, когато се появи в чата. */
    @Synchronized
    fun noteSent(text: String) {
        recentlySent.addLast(text.trim() to clock())
    }

    private fun trim() {
        while (messages.size > MAX_MESSAGES) {
            val first = messages.keys.first()
            messages.remove(first)
            aiQueue.remove(first)
        }
        // при наплив AI не смогва – най-старите остават с решението на филтъра
        while (aiQueue.size > MAX_QUEUE) resolveLocally(aiQueue.removeFirst(), "AI не смогва – наплив")
    }

    // ---------- AI ----------

    @Synchronized
    fun pendingAi(): Int = aiQueue.size

    @Synchronized
    fun takeAiBatch(max: Int = 25): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        while (aiQueue.isNotEmpty() && out.size < max) {
            messages[aiQueue.removeFirst()]?.let { out += it }
        }
        return out
    }

    /** Прилага отговора на AI. Връща новите сигнали (за известия). */
    @Synchronized
    fun applyAi(batch: List<ChatMessage>, results: List<AiResult>): List<ChatMessage> {
        val newFlags = ArrayList<ChatMessage>()
        val byIndex = results.associateBy { it.index }
        batch.forEachIndexed { i, original ->
            val cur = messages[original.id] ?: return@forEachIndexed
            if (cur.source == Source.YOU) return@forEachIndexed // ти си решил – AI не пипа
            val r = byIndex[i]
            if (r == null) {
                if (cur.verdict == null) messages[cur.id] = cur.copy(verdict = Verdict.OK, source = Source.FILTER)
                return@forEachIndexed
            }
            val wasFlagged = cur.isFlagged
            var updated = when {
                r.verdict == Verdict.OK && cur.source == Source.FILTER && cur.verdict != null && cur.verdict != Verdict.OK ->
                    cur.copy(verdict = Verdict.OK, reason = "AI: наред (филтърът сгреши)", source = Source.AI)
                else -> cur.copy(verdict = r.verdict, reason = r.reason, source = Source.AI)
            }
            if (isWatched(updated.user) && (updated.verdict?.severity ?: 0) < Verdict.WATCH.severity) {
                updated = updated.copy(verdict = Verdict.WATCH, reason = "Следен профил", source = Source.RULE)
            }
            if (!updated.isFlagged && updated.important.isEmpty() && settings.highlightImportant && !isHost(updated.user)) {
                importantReason(updated.text, settings.hostName)?.let { updated = updated.copy(important = it) }
            }
            messages[cur.id] = updated
            if (updated.isFlagged) {
                greeter.block(updated.user)
                if (!wasFlagged || (cur.verdict?.severity ?: 0) < updated.verdict!!.severity) newFlags += updated
                if (!wasFlagged) viewers[updated.user]?.let { viewers[it.user] = it.copy(flags = it.flags + 1) }
            } else if (wasFlagged) {
                viewers[updated.user]?.let { viewers[it.user] = it.copy(flags = (it.flags - 1).coerceAtLeast(0)) }
            }
        }
        return newFlags
    }

    /** AI не отговори – решава филтърът. */
    @Synchronized
    fun aiFailed(batch: List<ChatMessage>, error: AiException) {
        val note = if (error.kind == AiException.Kind.BLOCKED) "AI отказа – провери" else ""
        for (m in batch) {
            val cur = messages[m.id] ?: continue
            if (cur.verdict != null) continue
            messages[cur.id] = if (error.kind == AiException.Kind.BLOCKED) {
                cur.copy(verdict = Verdict.CHECK, reason = note, source = Source.AI)
            } else {
                cur.copy(verdict = Verdict.OK, source = Source.FILTER)
            }
        }
    }

    private fun resolveLocally(id: Long, note: String) {
        val cur = messages[id] ?: return
        if (cur.verdict == null) messages[id] = cur.copy(verdict = Verdict.OK, source = Source.FILTER, reason = note)
    }

    // ---------- действия от екрана ----------

    @Synchronized
    fun dismiss(id: Long) {
        messages[id]?.let { messages[id] = it.copy(dismissed = true) }
    }

    /** „Грешка“ – съобщението е наред. */
    @Synchronized
    fun markOk(id: Long) {
        messages[id]?.let { messages[id] = it.copy(verdict = Verdict.OK, source = Source.YOU, reason = "Отбелязано от теб") }
    }

    // ---------- поздрави ----------

    @Synchronized
    fun greetDue(): List<String>? = greeter.due(clock(), settings.greet)

    @Synchronized
    fun greetingFromTemplate(names: List<String>): String = greeter.fromTemplate(names, settings.greet)

    // ---------- четене ----------

    @Synchronized
    fun snapshot(): List<ChatMessage> = messages.values.toList().asReversed()

    @Synchronized
    fun flagged(): List<ChatMessage> = messages.values.filter { it.isFlagged }.asReversed()

    /** Въпроси и споменавания на водещия (без маркираните). */
    @Synchronized
    fun important(): List<ChatMessage> = messages.values.filter { it.isImportant }.asReversed()

    // ---------- превод ----------
    private val translateTried = HashSet<Long>()

    /** Маркирани и важни съобщения, които още не са пращани за превод. */
    @Synchronized
    fun takeTranslationJobs(max: Int = 5): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        for (m in messages.values.reversed()) {
            if (out.size >= max) break
            if ((m.isFlagged || m.isImportant) && m.id !in translateTried && Translator.worthTranslating(m.text)) {
                translateTried += m.id
                out += m
            }
        }
        return out
    }

    @Synchronized
    fun setTranslation(id: Long, text: String) {
        messages[id]?.let { messages[id] = it.copy(translation = text) }
    }

    @Synchronized
    fun viewerList(): List<Viewer> = viewers.values.sortedWith(
        compareByDescending<Viewer> { it.flags }.thenByDescending { it.suspiciousName }.thenByDescending { it.messages }
    )

    @Synchronized
    fun clear() {
        messages.clear(); viewers.clear(); aiQueue.clear(); seen.clear(); recentlySent.clear(); translateTried.clear()
        bots.reset(); greeter.reset()
    }

    companion object {
        const val MAX_MESSAGES = 500
        const val MAX_QUEUE = 80

        private val QUESTION_START: Set<String> = listOf(
            // английски
            "how", "what", "whats", "when", "where", "wheres", "why", "who", "whos", "which",
            "can you", "could you", "do you", "did you", "are you", "will you", "would you", "have you",
            "can i", "is it", "is this", "are u", "do u", "can u",
            // български
            "как", "какво", "кога", "къде", "защо", "колко", "кой", "коя", "кое", "кои", "откъде",
            "може ли", "можеш ли", "имаш ли", "дали",
            // турски
            "ne", "nasıl", "neden", "nerede", "kim"
        ).map { LocalFilter.tokenize(it).joinToString(" ") }.toSet()

        /** Въпрос или споменаване на водещия → кратко обяснение; иначе null. */
        fun importantReason(text: String, hostName: String): String? {
            val host = hostName.trim().removePrefix("@").lowercase()
            if (host.length >= 3) {
                val lower = text.lowercase()
                val hostWords = LocalFilter.tokenize(host)
                val tokens = LocalFilter.tokenize(text)
                if (lower.contains("@$host") || lower.contains(host) ||
                    (hostWords.isNotEmpty() && hostWords.all { it in tokens })
                ) return "Споменава водещия"
            }
            if (text.any { it == '?' || it == '¿' || it == '؟' }) return "Въпрос"
            val tokens = LocalFilter.tokenize(text)
            if (tokens.size >= 2) {
                val one = tokens[0]
                val two = tokens[0] + " " + tokens[1]
                if (two in QUESTION_START || one in QUESTION_START) return "Въпрос"
            }
            return null
        }
    }
}
