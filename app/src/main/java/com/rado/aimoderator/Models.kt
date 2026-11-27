package com.rado.aimoderator

/** Какво е решил модераторът за едно съобщение. severity: 0 = наред, 3 = най-сериозно. */
enum class Verdict(val label: String, val severity: Int) {
    OK("Наред", 0),
    SPAM("Спам", 1),
    CHECK("Провери", 1),
    BOT("Бот", 2),
    WATCH("Следен", 2),
    INSULT("Обида", 2),
    SEXUAL("Сексуално", 3),
    HATE("Омраза", 3),
    THREAT("Заплаха", 3),
    HELP("Нужда от помощ", 3);

    companion object {
        fun parse(s: String?): Verdict = when (s?.trim()?.uppercase()) {
            "OK", "FINE", "SAFE" -> OK
            "SPAM", "AD", "SCAM" -> SPAM
            "BOT" -> BOT
            "INSULT", "HARASSMENT", "TOXIC" -> INSULT
            "SEXUAL" -> SEXUAL
            "HATE" -> HATE
            "THREAT", "VIOLENCE" -> THREAT
            "HELP", "SELF_HARM", "SELFHARM" -> HELP
            else -> CHECK
        }
    }
}

/** Кой е взел решението. */
enum class Source(val label: String) {
    AI("AI"),
    FILTER("Филтър"),
    RULE("Правило за ботове"),
    WHITELIST("Доверен"),
    YOU("Ти")
}

/** Един ред от чата, както е прочетен от екрана. */
data class ChatRow(val user: String, val text: String)

data class ChatMessage(
    val id: Long,
    val user: String,
    val text: String,
    val time: Long,
    /** null = чака AI */
    val verdict: Verdict? = null,
    val reason: String = "",
    val source: Source? = null,
    val dismissed: Boolean = false,
    /** Не е нарушение, но е важно: въпрос или споменава водещия. Празно = обикновено. */
    val important: String = "",
    /** Превод на български (за коментари на чужд език). */
    val translation: String = ""
) {
    val isFlagged: Boolean get() = verdict != null && verdict != Verdict.OK && !dismissed
    val isImportant: Boolean get() = important.isNotEmpty() && !isFlagged && !dismissed
}

/** Статистика за един зрител в текущия лайв. */
data class Viewer(
    val user: String,
    val messages: Int = 0,
    val flags: Int = 0,
    val suspiciousName: Boolean = false,
    val lastText: String = "",
    val lastTime: Long = 0L
)

enum class Strictness(val label: String, val prompt: String) {
    LENIENT(
        "Меко",
        "Be lenient: flag only clear insults, threats, hate, sexual harassment and obvious spam. Rough jokes and swearing are OK."
    ),
    NORMAL(
        "Нормално",
        "Be balanced: flag insults, harassment, hate, sexual comments, threats and spam. Friendly teasing and swearing not aimed at a person are OK."
    ),
    STRICT(
        "Строго",
        "Be strict: this is a family-friendly stream. Also flag rude language, swearing, mockery and any begging for gifts."
    )
}

data class AiSettings(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val strictness: Strictness = Strictness.NORMAL,
    val liveRules: String = ""
) {
    val enabled: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}

/** Отговор на AI за едно съобщение от пакета. */
data class AiResult(val index: Int, val verdict: Verdict, val reason: String)

enum class AiState { OFF, READY, WORKING, RATE_LIMITED, ERROR }

data class AiStatus(val state: AiState = AiState.OFF, val message: String = "")
