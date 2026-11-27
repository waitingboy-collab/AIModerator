package com.rado.aimoderator

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class AlertSettings(
    val vibrate: Boolean = true,
    val sound: Boolean = false,
    val notify: Boolean = true,
    /** 1 = и спам, 2 = обиди и ботове, 3 = само най-сериозното */
    val minSeverity: Int = 2
)

enum class ChatSource(val label: String) {
    SCREEN("Екранът на TikTok"),
    USERNAME("По username")
}

data class AppSettings(
    val source: ChatSource = ChatSource.USERNAME,
    val username: String = "",
    val engine: EngineSettings = EngineSettings(),
    val alerts: AlertSettings = AlertSettings(),
    val debug: Boolean = false,
    /** Превежда на български маркираните и важните коментари на чужд език (Google). */
    val translate: Boolean = true
)

/** Какво вижда услугата – за раздел „Проверка“. */
class Diag {
    @Volatile var lastApp = ""
    @Volatile var tiktokEvents = 0
    @Volatile var webEvents = 0
    @Volatile var scans = 0
    @Volatile var lastNodes = 0
    @Volatile var lastTexts = 0
    @Volatile var lastRows = 0
    @Volatile var note = ""
    @Volatile var dumpRequested = false
    @Volatile var dump: String? = null
}

data class GreetLogEntry(val time: Long, val text: String, val status: String)

/**
 * Връзката между услугата, AI и екрана. Пази настройките, пуска AI на пакети
 * и решава кога да поздрави. Екранът се абонира чрез addListener.
 */
object ModRepository {

    private const val AI_MIN_INTERVAL_MS = 6_000L   // ≈10 заявки в минута – в рамките на безплатното ниво
    private const val AI_PAUSE_ON_LIMIT_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var ticker: ScheduledExecutorService? = null
    private var tickFuture: ScheduledFuture<*>? = null
    private val network = Executors.newSingleThreadExecutor()
    private val translateNet = Executors.newSingleThreadExecutor()
    @Volatile private var translateInFlight = false

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private var initialized = false

    val diag = Diag()
    var ai: AiClient = GeminiClient()
    val engine = ModerationEngine()

    @Volatile var settings: AppSettings = AppSettings()
        private set
    @Volatile var scanning = false
        private set
    @Volatile var serviceConnected = false
    @Volatile var aiStatus = AiStatus()
        private set
    @Volatile var lastCapture = 0L
        private set
    @Volatile var rawRows: List<String> = emptyList()
        private set
    @Volatile var suggestedGreeting: String? = null
        private set
    @Volatile var greetLog: List<GreetLogEntry> = emptyList()
        private set

    @Volatile private var aiInFlight = false
    @Volatile private var greetInFlight = false
    @Volatile private var lastAiCall = 0L
    @Volatile private var aiPausedUntil = 0L

    // ---------- абонати ----------
    private val listeners = LinkedHashSet<() -> Unit>()
    private var notifyPosted = false

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }

    /** Обновява екрана най-много ~5 пъти в секунда. */
    private fun changed() {
        main.post {
            if (notifyPosted) return@post
            notifyPosted = true
            main.postDelayed({
                notifyPosted = false
                val copy = synchronized(listeners) { listeners.toList() }
                copy.forEach { it() }
            }, 200)
        }
    }

    // ---------- старт ----------
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("ai_moderator", Context.MODE_PRIVATE)
        settings = load()
        engine.settings = engineSettings(settings)
        aiStatus = if (settings.engine.ai.enabled) AiStatus(AiState.READY) else AiStatus(AiState.OFF, "Няма API ключ – работи само филтърът")
        Alerts.createChannels(appContext)
        initialized = true
    }

    fun startScanning() {
        scanning = true
        if (settings.source == ChatSource.USERNAME) {
            // след „Пауза“ страницата е затворена – отваряме я отново
            if (LiveWeb.connectedUser.isEmpty() && settings.username.isNotBlank()) LiveWeb.connect(appContext, settings.username)
            KeepAliveService.start(appContext)
        }
        if (ticker == null) ticker = Executors.newSingleThreadScheduledExecutor()
        tickFuture?.cancel(false)
        tickFuture = ticker!!.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
        changed()
    }

    /** Пауза = спира всичко: четенето, AI, поздравите, известието и затваря страницата на лайва. */
    fun stopScanning() {
        scanning = false
        KeepAliveService.stop(appContext)
        tickFuture?.cancel(false)
        tickFuture = null
        greetInFlight = false
        LiveWeb.pause()
        changedNow()
    }

    fun clearSession() {
        engine.clear()
        rawRows = emptyList()
        suggestedGreeting = null
        greetLog = emptyList()
        changed()
    }

    // ---------- снимка на екрана за отстраняване ----------
    fun requestDump() {
        diag.dump = null
        diag.dumpRequested = true
        changed()
    }

    fun onDump(pkg: String, text: String) {
        diag.dump = "AI Модератор – снимка на екрана\nПакет: $pkg\nРедове в чата: ${diag.lastRows}\n\n$text"
        Alerts.dumpReady(appContext)
        changed()
    }

    fun shareDump(ctx: Context) {
        val text = diag.dump ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text.take(90_000))
        ctx.startActivity(android.content.Intent.createChooser(send, "Сподели снимката на екрана"))
    }

    /** Обновява екрана с диагностиката (вика се от екрана на всяка секунда). */
    fun refresh() = changed()

    // ---------- вход от услугата (главна нишка) ----------
    fun onVisible(rows: List<ChatRow>, raw: List<String>, joins: List<String>) {
        if (!scanning || settings.source != ChatSource.SCREEN) return
        lastCapture = System.currentTimeMillis()
        if (settings.debug && raw.isNotEmpty()) rawRows = (raw.reversed() + rawRows).distinct().take(60)
        val update = engine.onVisible(rows, joins)
        update.newFlags.forEach { alert(it) }
        if (update.newMessages > 0 || joins.isNotEmpty() || settings.debug) changed()
    }

    /** От вградения браузър (режим „По username“). */
    fun onWebEvent(type: String, name: String, text: String) {
        diag.webEvents++
        if (!scanning || settings.source != ChatSource.USERNAME) return
        lastCapture = System.currentTimeMillis()
        val update = when (type) {
            "join" -> if (name.isNotEmpty()) engine.onNewMessages(emptyList(), listOf(name)) else return
            else -> if (text.isNotEmpty()) engine.onNewMessages(listOf(ChatRow(name.ifEmpty { "?" }, text)), emptyList()) else return
        }
        update.newFlags.forEach { alert(it) }
        changed()
    }

    private fun alert(m: ChatMessage) {
        val v = m.verdict ?: return
        if (v.severity >= settings.alerts.minSeverity) Alerts.flag(appContext, m, settings.alerts)
    }

    // ---------- всяка секунда ----------
    /** Водещият = username-ът от раздел Лайв (при чужд лайв – името на водещия). */
    private fun engineSettings(s: AppSettings) = s.engine.copy(hostName = s.username.trim().removePrefix("@"))

    private fun tick() {
        try {
            tickAi()
            tickTranslate()
            tickGreet()
        } catch (_: Throwable) {
        }
    }

    private fun tickAi() {
        val s = settings.engine.ai
        val now = System.currentTimeMillis()
        if (!s.enabled || aiInFlight || now < aiPausedUntil || now - lastAiCall < AI_MIN_INTERVAL_MS) return
        if (engine.pendingAi() == 0) {
            if (aiStatus.state == AiState.RATE_LIMITED || aiStatus.state == AiState.WORKING) setAi(AiStatus(AiState.READY))
            return
        }
        val batch = engine.takeAiBatch(25)
        if (batch.isEmpty()) return
        aiInFlight = true
        lastAiCall = now
        setAi(AiStatus(AiState.WORKING, "Проверява ${batch.size} съобщения…"))
        network.execute {
            try {
                val results = ai.classify(batch, s)
                val newFlags = engine.applyAi(batch, results)
                main.post { newFlags.forEach { alert(it) } }
                setAi(AiStatus(AiState.READY))
            } catch (e: AiException) {
                engine.aiFailed(batch, e)
                when (e.kind) {
                    AiException.Kind.RATE_LIMIT -> {
                        aiPausedUntil = System.currentTimeMillis() + AI_PAUSE_ON_LIMIT_MS
                        setAi(AiStatus(AiState.RATE_LIMITED, "Лимит – пауза 1 мин., междувременно работи филтърът"))
                    }
                    AiException.Kind.AUTH -> {
                        aiPausedUntil = System.currentTimeMillis() + 5 * 60_000L
                        setAi(AiStatus(AiState.ERROR, e.message ?: "Проблем с ключа"))
                    }
                    else -> setAi(AiStatus(AiState.ERROR, e.message ?: "Грешка"))
                }
                if (e.kind == AiException.Kind.BLOCKED) main.post { batch.forEach { m -> engine.snapshot().firstOrNull { it.id == m.id }?.let { alert(it) } } }
            } catch (e: Throwable) {
                engine.aiFailed(batch, AiException(AiException.Kind.NETWORK, e.message ?: "?"))
                setAi(AiStatus(AiState.ERROR, e.message ?: "Грешка"))
            } finally {
                aiInFlight = false
                changed()
            }
        }
    }

    private fun tickTranslate() {
        if (!settings.translate || translateInFlight) return
        val jobs = engine.takeTranslationJobs(5)
        if (jobs.isEmpty()) return
        translateInFlight = true
        translateNet.execute {
            try {
                for (m in jobs) {
                    val t = try { Translator.toBulgarian(m.text) } catch (_: Exception) { "" }
                    if (t.isNotBlank()) engine.setTranslation(m.id, t)
                }
            } finally {
                translateInFlight = false
                changed()
            }
        }
    }

    private fun setAi(s: AiStatus) {
        if (s != aiStatus) {
            aiStatus = s
            changed()
        }
    }

    // ---------- поздрави ----------
    private fun tickGreet() {
        val g = settings.engine.greet
        if (g.mode == GreetMode.OFF || greetInFlight) return
        val names = engine.greetDue() ?: return
        greetInFlight = true
        val ai = settings.engine.ai
        if (g.useAi && ai.enabled && System.currentTimeMillis() >= aiPausedUntil) {
            network.execute {
                val text = try {
                    Greeter.sanitize(this.ai.greeting(names, g.aiStyle, ai)).ifBlank { engine.greetingFromTemplate(names) }
                } catch (e: Exception) {
                    engine.greetingFromTemplate(names)
                }
                main.post { deliverGreeting(text) }
            }
        } else {
            val text = engine.greetingFromTemplate(names)
            main.post { deliverGreeting(text) }
        }
    }

    private fun deliverGreeting(text: String) {
        val mode = settings.engine.greet.mode
        if (mode == GreetMode.AUTO && settings.source == ChatSource.USERNAME) {
            LiveWeb.send(text) { ok, note ->
                if (ok) {
                    engine.noteSent(text)
                    log(text, "Изпратено")
                } else {
                    suggest(text, note)
                }
                greetInFlight = false
            }
        } else if (mode == GreetMode.AUTO) {
            val service = ChatReaderService.instance
            if (service == null) {
                suggest(text, "Услугата не е включена – само предложение")
                greetInFlight = false
                return
            }
            service.sendToChat(text) { ok, note ->
                if (ok) {
                    engine.noteSent(text)
                    log(text, "Изпратено")
                } else {
                    suggest(text, note)
                }
                greetInFlight = false
            }
        } else {
            suggest(text, "Предложение")
            greetInFlight = false
        }
    }

    private fun suggest(text: String, status: String) {
        suggestedGreeting = text
        log(text, status)
        Alerts.greetingSuggestion(appContext, text)
    }

    private fun log(text: String, status: String) {
        greetLog = (listOf(GreetLogEntry(System.currentTimeMillis(), text, status)) + greetLog).take(30)
        changed()
    }

    fun clearSuggestion() {
        suggestedGreeting = null
        changed()
    }

    // ---------- действия от екрана ----------
    fun dismiss(id: Long) { engine.dismiss(id); changed() }
    fun markOk(id: Long) { engine.markOk(id); changed() }

    fun trust(user: String) {
        val e = settings.engine
        update(settings.copy(engine = e.copy(whitelist = e.whitelist + user)))
        engine.snapshot().filter { it.user == user && it.isFlagged }.forEach { engine.markOk(it.id) }
        changed()
    }

    /** „Следи“ – добавя профила към следените. */
    fun watch(user: String) {
        if (user == "?") return
        val e = settings.engine
        update(settings.copy(engine = e.copy(watchlist = e.watchlist + user.removePrefix("@"))))
    }

    fun update(newSettings: AppSettings) {
        val keyChanged = newSettings.engine.ai.apiKey != settings.engine.ai.apiKey ||
            newSettings.engine.ai.model != settings.engine.ai.model
        settings = newSettings
        engine.settings = engineSettings(newSettings)
        if (keyChanged) {
            aiPausedUntil = 0L
            aiStatus = if (newSettings.engine.ai.enabled) AiStatus(AiState.READY) else AiStatus(AiState.OFF, "Няма API ключ – работи само филтърът")
        }
        if (!newSettings.debug) rawRows = emptyList()
        save(newSettings)
        changedNow()
    }

    /** Веднага (без изчакване) – за настройките, за да не се губи въведен текст. */
    private fun changedNow() {
        val copy = synchronized(listeners) { listeners.toList() }
        if (Looper.myLooper() == Looper.getMainLooper()) copy.forEach { it() } else main.post { copy.forEach { it() } }
    }

    /** Пробва без лайв: пуска примерни коментари през целия път. */
    fun runDemo() {
        val wasScanning = scanning
        if (!wasScanning) startScanning()
        val demo = listOf(
            ChatRow("Иван", "Здравей от Крумовград! 👋"),
            ChatRow("maria_88", "много хубав лайв 😍"),
            ChatRow("troll123", "млъкни бе тъпак 🤡"),
            ChatRow("user99887766", "free coins на www.coins-gift.xyz"),
            ChatRow("Али", "Merhaba! Nasılsın?"),
            ChatRow("gosho", "покажи се гола"),
            ChatRow("petya", "ела на моя профил, ще ти пратя нещо интересно 😉"),
            ChatRow("nik", "мамка ти колко си добър в тая игра 😂"),
            ChatRow("jake_uk", "f*ck you, you're a loser"),
            ChatRow("emma.rose", "Where are you streaming from?"),
            ChatRow("promo_king", "check my bio for free followers 🔥"),
            ChatRow("tom", "this stream is s h i t")
        )
        // като истински чат: всеки път се вижда малко повече
        demo.indices.forEach { i ->
            main.postDelayed({
                val u = engine.onNewMessages(listOf(demo[i]), if (i == 2) listOf("Стефан") else emptyList())
                lastCapture = System.currentTimeMillis()
                u.newFlags.forEach { alert(it) }
                changed()
            }, i * 700L)
        }
    }

    // ---------- запис ----------
    private fun load(): AppSettings {
        val p = prefs
        val ai = AiSettings(
            apiKey = p.getString("apiKey", "") ?: "",
            model = p.getString("model", AiSettings.DEFAULT_MODEL) ?: AiSettings.DEFAULT_MODEL,
            strictness = runCatching { Strictness.valueOf(p.getString("strictness", "NORMAL")!!) }.getOrDefault(Strictness.NORMAL),
            liveRules = p.getString("liveRules", "") ?: ""
        )
        val greet = GreetSettings(
            mode = runCatching { GreetMode.valueOf(p.getString("greetMode", "OFF")!!) }.getOrDefault(GreetMode.OFF),
            onJoin = p.getBoolean("greetOnJoin", true),
            onFirstMessage = p.getBoolean("greetOnFirst", true),
            templates = (p.getString("greetTemplates", null)?.lines()?.filter { it.isNotBlank() })
                ?.ifEmpty { null } ?: GreetSettings.DEFAULT_TEMPLATES,
            useAi = p.getBoolean("greetUseAi", false),
            aiStyle = p.getString("greetAiStyle", GreetSettings().aiStyle) ?: GreetSettings().aiStyle,
            minIntervalSec = p.getInt("greetInterval", 45).coerceAtLeast(GreetSettings.MIN_INTERVAL_SEC),
            maxNamesPerMessage = p.getInt("greetMaxNames", 4)
        )
        val engine = EngineSettings(
            ai = ai,
            whitelist = splitList(p.getString("whitelist", "")).toSet(),
            myName = p.getString("myName", "") ?: "",
            extraBlocked = splitList(p.getString("extraBlocked", "")),
            greet = greet,
            watchlist = splitList(p.getString("watchlist", "")).toSet(),
            highlightImportant = p.getBoolean("highlightImportant", true)
        )
        val alerts = AlertSettings(
            vibrate = p.getBoolean("vibrate", true),
            sound = p.getBoolean("sound", false),
            notify = p.getBoolean("notify", true),
            minSeverity = p.getInt("minSeverity", 2)
        )
        return AppSettings(
            source = runCatching { ChatSource.valueOf(p.getString("source", "USERNAME")!!) }.getOrDefault(ChatSource.USERNAME),
            username = p.getString("username", "") ?: "",
            engine = engine,
            alerts = alerts,
            debug = p.getBoolean("debug", false),
            translate = p.getBoolean("translate", true)
        )
    }

    private fun save(s: AppSettings) {
        val e = s.engine
        prefs.edit()
            .putString("apiKey", e.ai.apiKey.trim())
            .putString("model", e.ai.model.trim())
            .putString("strictness", e.ai.strictness.name)
            .putString("liveRules", e.ai.liveRules)
            .putString("whitelist", e.whitelist.joinToString(", "))
            .putString("myName", e.myName)
            .putString("extraBlocked", e.extraBlocked.joinToString(", "))
            .putString("greetMode", e.greet.mode.name)
            .putBoolean("greetOnJoin", e.greet.onJoin)
            .putBoolean("greetOnFirst", e.greet.onFirstMessage)
            .putString("greetTemplates", e.greet.templates.joinToString("\n"))
            .putBoolean("greetUseAi", e.greet.useAi)
            .putString("greetAiStyle", e.greet.aiStyle)
            .putInt("greetInterval", e.greet.minIntervalSec)
            .putInt("greetMaxNames", e.greet.maxNamesPerMessage)
            .putBoolean("vibrate", s.alerts.vibrate)
            .putBoolean("sound", s.alerts.sound)
            .putBoolean("notify", s.alerts.notify)
            .putInt("minSeverity", s.alerts.minSeverity)
            .putBoolean("debug", s.debug)
            .putBoolean("translate", s.translate)
            .putString("watchlist", e.watchlist.joinToString(", "))
            .putBoolean("highlightImportant", e.highlightImportant)
            .putString("source", s.source.name)
            .putString("username", s.username)
            .apply()
    }

    fun splitList(s: String?): List<String> =
        s.orEmpty().split(',', '\n', ';').map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }
}
