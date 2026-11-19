@file:OptIn(ExperimentalMaterial3Api::class)

package com.rado.aimoderator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModRepository.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        setContent { ModeratorTheme { App() } }
    }
}

// ---------- тема (светла) ----------

private val Rose = Color(0xFFE11D48)
private val Cyan = Color(0xFF0891B2)
private val Ink = Color(0xFF1C1917)
private val Muted = Color(0xFF78716C)
private val Bg = Color(0xFFFAFAF9)
private val Ok = Color(0xFF16A34A)
private val Amber = Color(0xFFD97706)

@Composable
fun ModeratorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Rose, onPrimary = Color.White,
            secondary = Cyan, onSecondary = Color.White,
            background = Bg, onBackground = Ink,
            surface = Color.White, onSurface = Ink,
            surfaceVariant = Color(0xFFF5F5F4), onSurfaceVariant = Muted,
            secondaryContainer = Color(0xFFFFE4E6), onSecondaryContainer = Ink
        ),
        content = content
    )
}

private fun verdictColor(v: Verdict?): Color = when (v?.severity) {
    null -> Muted
    0 -> Ok
    1 -> Amber
    2 -> Color(0xFFEA580C)
    else -> Rose
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun time(t: Long) = timeFmt.format(Date(t))

private enum class Tab(val title: String) { LIVE("Лайв"), FLAGS("Сигнали"), CHAT("Чат"), GREET("Поздрави"), SETTINGS("Настройки") }

// ---------- приложение ----------

@Composable
fun App() {
    // всяко обновяване от ModRepository → ново рисуване
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { tick++ }
        ModRepository.addListener(l)
        onDispose { ModRepository.removeListener(l) }
    }
    // диагностиката се променя постоянно – опресняваме веднъж в секунда
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    var tab by remember { mutableStateOf(Tab.LIVE) }
    var showBrowser by remember { mutableStateOf(false) }
    val byUser = ModRepository.settings.source == ChatSource.USERNAME

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("AI Модератор", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                Tab.entries.forEach { t ->
                    val flags = if (t == Tab.FLAGS) remember(tick) { ModRepository.engine.flagged().size } else 0
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.LIVE -> Icons.Filled.Home
                                    Tab.FLAGS -> Icons.Filled.Warning
                                    Tab.CHAT -> Icons.Filled.List
                                    Tab.GREET -> Icons.Filled.Face
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = t.title
                            )
                        },
                        label = { Text(if (flags > 0) "${t.title} ($flags)" else t.title, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusBar(tick)
            // разделът получава цялото останало място
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.LIVE -> LiveTab(tick, showBrowser) { showBrowser = it }
                    Tab.FLAGS -> FlagsTab(tick)
                    Tab.CHAT -> ChatTab(tick)
                    Tab.GREET -> GreetTab(tick)
                    Tab.SETTINGS -> SettingsTab(tick)
                }
            }
            // Вграденият браузър е винаги прикачен (иначе спира да чете).
            // Вижда се само в раздел „Лайв“, когато е отворен; иначе е висок 1 пиксел.
            if (byUser) {
                AndroidView(
                    factory = { c -> LiveWeb.detach(); LiveWeb.get(c) },
                    modifier = Modifier.fillMaxWidth().height(if (tab == Tab.LIVE && showBrowser) 380.dp else 1.dp)
                )
            }
        }
    }
}

/** Кратък статус най-горе – два реда, за да остане място за разделите. */
@Composable
private fun StatusBar(tick: Int) {
    val scanning = ModRepository.scanning
    val byUser = ModRepository.settings.source == ChatSource.USERNAME
    val connected = ModRepository.serviceConnected
    val ai = ModRepository.aiStatus

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (byUser) {
                StatusLine(LiveWeb.itemsOnPage > 0, LiveWeb.status, warn = LiveWeb.connectedUser.isEmpty())
            } else {
                StatusLine(connected, if (connected) "Чете екрана на TikTok" else "Достъпът за четене е изключен")
            }
            StatusLine(
                ai.state == AiState.READY || ai.state == AiState.WORKING,
                when (ai.state) {
                    AiState.OFF -> "Без AI – само филтър"
                    AiState.READY -> "AI е готов"
                    else -> ai.message
                },
                warn = ai.state == AiState.RATE_LIMITED || ai.state == AiState.OFF
            )
        }
        if (scanning) {
            OutlinedButton(onClick = { ModRepository.stopScanning() }) { Text("Пауза") }
        } else {
            Button(onClick = { ModRepository.startScanning() }) { Text("Старт") }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String, warn: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(9.dp).clip(CircleShape)
                .background(if (ok) Ok else if (warn) Amber else Rose)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Лайв: връзка по username или проверка на екрана ----------

@Composable
private fun LiveTab(tick: Int, showBrowser: Boolean, setShowBrowser: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val s = ModRepository.settings
    var user by remember { mutableStateOf(s.username) }
    var diag by remember { mutableStateOf("") }
    val total = remember(tick) { ModRepository.engine.snapshot().size }
    val flagged = remember(tick) { ModRepository.engine.flagged().size }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (s.source == ChatSource.USERNAME) {
            Section("Свържи се с лайва") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = {
                            user = it.trim()
                            ModRepository.update(ModRepository.settings.copy(username = it.trim().removePrefix("@")))
                        },
                        label = { Text("Твоят TikTok username") },
                        prefix = { Text("@") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        LiveWeb.connect(ctx, user)
                        if (!ModRepository.scanning) ModRepository.startScanning()
                    }) { Text("Свържи") }
                }
                FlowRowCompat {
                    TextButton(onClick = { LiveWeb.openLogin(ctx); setShowBrowser(true) }) { Text("Вход в TikTok") }
                    TextButton(onClick = { setShowBrowser(!showBrowser) }) {
                        Text(if (showBrowser) "Скрий браузъра" else "Покажи браузъра")
                    }
                    TextButton(onClick = { LiveWeb.diagnose { diag = LiveWeb.diagnosis } }) { Text("Диагностика") }
                }
                Text(
                    "Получени: ${ModRepository.diag.webEvents} · в чата на страницата: ${LiveWeb.itemsOnPage}",
                    color = Muted, fontSize = 12.sp
                )
                if (showBrowser) Text("Браузърът е най-долу ↓", color = Cyan, fontSize = 12.sp)
                if (diag.isNotEmpty()) {
                    Text(diag, fontSize = 11.sp, color = Muted, maxLines = 14, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(onClick = { CopyReceiver.copyToClipboard(ctx, diag) }) { Text("Копирай диагностиката") }
                        TextButton(onClick = { diag = "" }) { Text("Скрий") }
                    }
                }
            }
        } else {
            if (!ModRepository.serviceConnected) {
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("Включи достъпа")
                }
            }
            DiagCard()
        }

        Section("Този лайв") {
            Text("$total съобщения · $flagged сигнала", fontSize = 15.sp)
            val g = s.engine.greet
            Text(
                if (g.mode == GreetMode.OFF) "Поздрави: изключени"
                else "Поздрави: ${g.mode.label.lowercase()}, на всеки ${g.minIntervalSec} сек.",
                color = Muted, fontSize = 13.sp
            )
            TextButton(onClick = { ModRepository.clearSession() }) { Text("Нов лайв (изчисти всичко)") }
        }
    }
}

// ---------- Сигнали ----------

@Composable
private fun FlagsTab(tick: Int) {
    val flagged = remember(tick) { ModRepository.engine.flagged() }
    if (flagged.isEmpty()) {
        Empty(
            if (ModRepository.scanning) "Засега всичко е наред 👌\nМаркираните съобщения ще се появят тук.\n\nАко в раздел Чат не се появяват коментари, виж „Проверка“ там."
            else "Отвори своя лайв в TikTok и натисни „Старт“.\nМожеш да пробваш с примерни коментари от Настройки."
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(flagged, key = { it.id }) { FlagCard(it) }
    }
}

@Composable
private fun FlagCard(m: ChatMessage) {
    val ctx = LocalContext.current
    val c = verdictColor(m.verdict)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = c.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        m.verdict?.label ?: "…", color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(m.user, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(time(m.time), color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("„${m.text}“", fontSize = 16.sp)
            if (m.reason.isNotBlank()) {
                Text("${m.reason} · ${m.source?.label ?: ""}", color = Muted, fontSize = 13.sp)
            }
            if (m.verdict == Verdict.HELP) {
                Text(
                    "Отговори му спокойно и с внимание. Ако е сериозно, насочи го към близък човек или към 112.",
                    color = Cyan, fontSize = 13.sp
                )
            }
            FlowRowCompat {
                TextButton(onClick = { CopyReceiver.copyToClipboard(ctx, m.user) }) { Text("Копирай името") }
                TextButton(onClick = { ModRepository.markOk(m.id) }) { Text("Грешка") }
                TextButton(onClick = { ModRepository.trust(m.user) }) { Text("Доверен") }
                TextButton(onClick = { ModRepository.dismiss(m.id) }) { Text("Скрий") }
            }
            Text(
                "Блокиране: натисни името в чата на TikTok → Заглуши или Блокирай.",
                color = Muted, fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) { content() }
}

// ---------- Чат ----------

@Composable
private fun ChatTab(tick: Int) {
    var viewers by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !viewers, onClick = { viewers = false }, label = { Text("Съобщения") })
            FilterChip(selected = viewers, onClick = { viewers = true }, label = { Text("Зрители") })
        }
        Box(Modifier.weight(1f)) {
            if (viewers) ViewersTab(tick) else MessagesList(tick)
        }
    }
}

@Composable
private fun MessagesList(tick: Int) {
    val all = remember(tick) { ModRepository.engine.snapshot() }
    val raw = ModRepository.rawRows
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ModRepository.settings.debug && raw.isNotEmpty()) {
            item { Text("Сурови редове от екрана:", fontWeight = FontWeight.Bold, color = Cyan) }
            items(raw.take(20)) { Text(it, fontSize = 12.sp, color = Muted) }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        }
        if (all.isEmpty()) item { Text("Още няма прочетени съобщения.", color = Muted, modifier = Modifier.padding(8.dp)) }
        items(all, key = { it.id }) { m ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(verdictColor(m.verdict)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.user, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(m.text, fontSize = 15.sp)
                    val status = when {
                        m.verdict == null -> "AI проверява…"
                        m.verdict == Verdict.OK -> m.reason
                        else -> "${m.verdict?.label}: ${m.reason}"
                    }
                    if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = verdictColor(m.verdict))
                }
                Text(time(m.time), fontSize = 11.sp, color = Muted)
            }
        }
    }
}

/** Показва на коя стъпка спира четенето. */
@Composable
private fun DiagCard() {
    val ctx = LocalContext.current
    val d = ModRepository.diag
    val connected = ModRepository.serviceConnected
    val scanning = ModRepository.scanning
    val lastRead = ModRepository.lastCapture
    val ago = if (lastRead == 0L) "никога" else "преди ${(System.currentTimeMillis() - lastRead) / 1000} сек."

    // първата стъпка, която не е минала
    val problem = when {
        !connected -> "1. Достъпът не е включен. Натисни „Включи достъпа“ горе."
        !scanning -> "2. Не е натиснат „Старт“."
        d.tiktokEvents == 0 -> "3. Не идват събития от TikTok. Отвори лайва в TikTok. Ако пак е 0: изключи и включи достъпа на AI Модератор (Настройки → Достъпност)."
        d.scans == 0 -> "4. Има събития, но екранът не е прочетен. ${d.note}"
        d.lastTexts == 0 -> "5. TikTok не показва текст на услугата (0 текста на екрана)."
        d.lastRows == 0 -> "6. Екранът се чете (${d.lastTexts} текста), но чатът не е разпознат. Направи снимка на екрана отдолу и ми я прати."
        else -> null
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Проверка", fontWeight = FontWeight.Bold)
            Text(
                "Събития от TikTok: ${d.tiktokEvents} · прочита: ${d.scans} · текстове: ${d.lastTexts} · редове в чата: ${d.lastRows}",
                fontSize = 12.sp, color = Muted
            )
            Text("Последно прочетен чат: $ago", fontSize = 12.sp, color = Muted)
            if (d.lastApp.isNotEmpty()) Text("Последно приложение на екрана: ${d.lastApp}", fontSize = 12.sp, color = Muted)
            if (problem != null) Text(problem, fontSize = 14.sp, color = Rose)
            else Text("Всичко работи ✓", fontSize = 14.sp, color = Ok)

            when {
                d.dumpRequested -> Text(
                    "Чака… Отвори лайва в TikTok за 2–3 сек. и се върни тук.",
                    fontSize = 13.sp, color = Amber
                )
                d.dump != null -> Row {
                    Button(onClick = { ModRepository.shareDump(ctx) }) { Text("Сподели снимката") }
                    TextButton(onClick = { ModRepository.requestDump() }) { Text("Нова") }
                }
                else -> OutlinedButton(onClick = { ModRepository.requestDump() }, enabled = connected) {
                    Text("Снимка на екрана на TikTok")
                }
            }
        }
    }
}

// ---------- Зрители ----------

@Composable
private fun ViewersTab(tick: Int) {
    val viewers = remember(tick) { ModRepository.engine.viewerList() }
    if (viewers.isEmpty()) { Empty("Още няма зрители, които са писали."); return }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Най-отгоре са маркираните и тези с подозрителни имена.", color = Muted, fontSize = 13.sp)
        }
        items(viewers, key = { it.user }) { v ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.user, fontWeight = FontWeight.SemiBold)
                        Text(v.lastText, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (v.suspiciousName) Text("Име като на бот", color = Amber, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${v.messages} съобщ.", fontSize = 13.sp)
                        if (v.flags > 0) Text("${v.flags} сигнала", color = Rose, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ---------- Поздрави ----------

@Composable
private fun GreetTab(tick: Int) {
    val ctx = LocalContext.current
    val s = ModRepository.settings
    val g = s.engine.greet
    fun set(newG: GreetSettings) = ModRepository.update(s.copy(engine = s.engine.copy(greet = newG)))
    var templates by remember { mutableStateOf(g.templates.joinToString("\n")) }
    var style by remember { mutableStateOf(g.aiStyle) }
    var preview by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("Режим") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GreetMode.entries.forEach { m ->
                    FilterChip(selected = g.mode == m, onClick = { set(g.copy(mode = m)) }, label = { Text(m.label) })
                }
            }
            Text(g.mode.hint, color = Muted, fontSize = 13.sp)
            if (g.mode == GreetMode.AUTO) {
                Text(
                    "Приложението ще пише в чата от твоя акаунт. TikTok може да ограничи акаунт, който пише твърде често, затова поздравява няколко души наведнъж и не по-често от избраното. Ако в момента пишеш нещо, поздравът става само предложение.",
                    color = Amber, fontSize = 13.sp
                )
            }
        }

        ModRepository.suggestedGreeting?.let { text ->
            Section("Готов поздрав") {
                Text(text, fontSize = 16.sp)
                Row {
                    Button(onClick = { CopyReceiver.copyToClipboard(ctx, text); ModRepository.clearSuggestion() }) { Text("Копирай") }
                    TextButton(onClick = { ModRepository.clearSuggestion() }) { Text("Скрий") }
                }
            }
        }

        Section("Кого да поздравява") {
            SwitchRow("Когато влезе в лайва", g.onJoin) { set(g.copy(onJoin = it)) }
            SwitchRow("Когато напише първо съобщение", g.onFirstMessage) { set(g.copy(onFirstMessage = it)) }
            Text(
                "Не поздравява маркирани, имена като на бот и теб самия. Всеки се поздравява по веднъж на лайв.",
                color = Muted, fontSize = 13.sp
            )
            Text("Най-често: на всеки ${g.minIntervalSec} сек.", fontSize = 14.sp)
            Slider(
                value = g.minIntervalSec.toFloat(),
                onValueChange = { set(g.copy(minIntervalSec = it.toInt())) },
                valueRange = GreetSettings.MIN_INTERVAL_SEC.toFloat()..180f,
                steps = 9
            )
            Text("До ${g.maxNamesPerMessage} имена в един поздрав", fontSize = 14.sp)
            Slider(
                value = g.maxNamesPerMessage.toFloat(),
                onValueChange = { set(g.copy(maxNamesPerMessage = it.toInt())) },
                valueRange = 1f..6f,
                steps = 4
            )
        }

        Section("Текст") {
            OutlinedTextField(
                value = templates,
                onValueChange = {
                    templates = it
                    set(g.copy(templates = it.lines().filter { l -> l.isNotBlank() }.ifEmpty { GreetSettings.DEFAULT_TEMPLATES }))
                },
                label = { Text("Шаблони – по един на ред, {name} = имената") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            SwitchRow("AI да пише поздрава (по-разнообразно)", g.useAi) { set(g.copy(useAi = it)) }
            if (g.useAi) {
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it; set(g.copy(aiStyle = it)) },
                    label = { Text("Стил, напр. „весело, на ти, с шега“") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (!s.engine.ai.enabled) Text("Нужен е API ключ (Настройки). Дотогава ползва шаблоните.", color = Amber, fontSize = 13.sp)
            }
            OutlinedButton(onClick = {
                preview = Greeter(java.util.Random()).fromTemplate(listOf("Иван", "Мария"), g)
            }) { Text("Пробен поздрав") }
            if (preview.isNotEmpty()) Text(preview, fontSize = 15.sp, color = Cyan)
        }

        if (ModRepository.greetLog.isNotEmpty()) {
            Section("Последни поздрави") {
                ModRepository.greetLog.take(10).forEach {
                    Text("${time(it.time)} · ${it.status}", color = Muted, fontSize = 12.sp)
                    Text(it.text, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- Настройки ----------

@Composable
private fun SettingsTab(tick: Int) {
    val ctx = LocalContext.current
    val s = ModRepository.settings
    val e = s.engine
    fun setEngine(ne: EngineSettings) = ModRepository.update(s.copy(engine = ne))

    var key by remember { mutableStateOf(e.ai.apiKey) }
    var model by remember { mutableStateOf(e.ai.model) }
    var rules by remember { mutableStateOf(e.ai.liveRules) }
    var myName by remember { mutableStateOf(e.myName) }
    var whitelist by remember { mutableStateOf(e.whitelist.joinToString(", ")) }
    var extra by remember { mutableStateOf(e.extraBlocked.joinToString(", ")) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("Откъде да чете чата") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatSource.entries.forEach { src ->
                    FilterChip(
                        selected = s.source == src,
                        onClick = {
                            if (ModRepository.scanning) ModRepository.stopScanning()
                            ModRepository.update(ModRepository.settings.copy(source = src))
                        },
                        label = { Text(src.label) }
                    )
                }
            }
            Text(
                if (s.source == ChatSource.USERNAME)
                    "Пишеш своя username в раздел Лайв, приложението отваря лайва ти във вграден браузър и чете чата оттам. TikTok не трябва да е на екрана."
                else
                    "Чете чата от отвореното приложение TikTok на този телефон. Иска разрешение за достъпност.",
                color = Muted, fontSize = 13.sp
            )
        }

        Section("AI (Google Gemini, безплатно)") {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; setEngine(e.copy(ai = e.ai.copy(apiKey = it.trim()))) },
                label = { Text("API ключ") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")))
            }) { Text("Вземи ключ от Google AI Studio") }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; setEngine(e.copy(ai = e.ai.copy(model = it.trim()))) },
                label = { Text("Модел") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Колко строго", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Strictness.entries.forEach { st ->
                    FilterChip(
                        selected = e.ai.strictness == st,
                        onClick = { setEngine(e.copy(ai = e.ai.copy(strictness = st))) },
                        label = { Text(st.label) }
                    )
                }
            }
            OutlinedTextField(
                value = rules,
                onValueChange = { rules = it; setEngine(e.copy(ai = e.ai.copy(liveRules = it))) },
                label = { Text("Правила на лайва (по желание)") },
                placeholder = { Text("напр. без реклама на други лайвове, без политика") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Text(
                "Текстът на коментарите се праща към Google за проверка. На безплатното ниво Google може да ги ползва за подобряване на услугите си. Без ключ работи само вграденият филтър.",
                color = Muted, fontSize = 12.sp
            )
        }

        Section("Хора") {
            OutlinedTextField(
                value = myName,
                onValueChange = { myName = it; setEngine(e.copy(myName = it.trim().removePrefix("@"))) },
                label = { Text("Твоето име в TikTok (както се вижда в чата)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = whitelist,
                onValueChange = { whitelist = it; setEngine(e.copy(whitelist = ModRepository.splitList(it).toSet())) },
                label = { Text("Доверени – модератори, приятели (със запетая)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = extra,
                onValueChange = { extra = it; setEngine(e.copy(extraBlocked = ModRepository.splitList(it))) },
                label = { Text("Още забранени думи (със запетая, дума* = всички окончания)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Section("Сигнали") {
            SwitchRow("Вибрация", s.alerts.vibrate) { ModRepository.update(s.copy(alerts = s.alerts.copy(vibrate = it))) }
            SwitchRow("Звук", s.alerts.sound) { ModRepository.update(s.copy(alerts = s.alerts.copy(sound = it))) }
            SwitchRow("Известие", s.alerts.notify) { ModRepository.update(s.copy(alerts = s.alerts.copy(notify = it))) }
            Text("Предупреждавай за:", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Всичко", 2 to "Обиди и нагоре", 3 to "Само сериозно").forEach { (lvl, label) ->
                    FilterChip(
                        selected = s.alerts.minSeverity == lvl,
                        onClick = { ModRepository.update(s.copy(alerts = s.alerts.copy(minSeverity = lvl))) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        Section("Проба и отстраняване") {
            Button(onClick = { ModRepository.runDemo() }) { Text("Тест с примерни коментари") }
            SwitchRow("Покажи суровите редове от екрана", s.debug) { ModRepository.update(s.copy(debug = it)) }
            Text(
                "Ако чатът не се чете правилно, включи това, отвори лайва и виж в раздел Чат какво се прочита.",
                color = Muted, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- малки части ----------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
    }
}
