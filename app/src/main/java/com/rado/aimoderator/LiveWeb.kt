package com.rado.aimoderator

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Режим „По username“: вграден браузър отваря tiktok.com/@username/live
 * и чете чата от страницата (както в Welcome Bot). Не изисква TikTok да е на екрана.
 *
 * Един WebView за цялото приложение – не се губи при смяна на раздели.
 */
@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
object LiveWeb {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null

    @Volatile var status: String = "Не е свързан"
        private set
    @Volatile var connectedUser: String = ""
        private set
    @Volatile var diagnosis: String = ""
        private set
    @Volatile var itemsOnPage = 0
        private set

    fun get(ctx: Context): WebView {
        web?.let { return it }
        val w = WebView(ctx.applicationContext)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            mediaPlaybackRequiresUserGesture = true // без автоматично видео – пести батерия и данни
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        // да не се „приспива“, когато приложението е на заден план
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        w.addJavascriptInterface(Bridge(), "RadoBridge")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.contains("/live")) {
                    setStatus("Страницата е заредена, търся чата…")
                    main.postDelayed({ inject() }, 2_000)
                } else if (url.contains("login")) {
                    setStatus("Влез в TikTok тук, после натисни „Свържи“")
                }
            }
        }
        web = w
        startWatchdog()
        return w
    }

    /** Махни WebView от стария екран, преди да го сложиш в нов. */
    fun detach() {
        (web?.parent as? ViewGroup)?.removeView(web)
    }

    fun connect(ctx: Context, username: String) {
        val u = username.trim().removePrefix("@")
        if (u.isEmpty()) { setStatus("Въведи username"); return }
        connectedUser = u
        itemsOnPage = 0
        setStatus("Отварям @$u/live…")
        get(ctx).loadUrl("https://www.tiktok.com/@$u/live")
    }

    fun openLogin(ctx: Context) {
        setStatus("Влез в TikTok тук, после натисни „Свържи“")
        get(ctx).loadUrl("https://www.tiktok.com/login")
    }

    fun disconnect() {
        web?.loadUrl("about:blank")
        connectedUser = ""
        itemsOnPage = 0
        setStatus("Не е свързан")
    }

    private fun inject() {
        web?.evaluateJavascript(LiveScripts.SCRAPER) { res ->
            val r = res?.trim('"').orEmpty()
            when {
                r == "ok" || r == "already" -> Unit
                else -> setStatus("Грешка при четене: $r")
            }
        }
    }

    /** На всеки 5 сек.: скриптът жив ли е и вижда ли съобщения. TikTok понякога презарежда части от страницата. */
    private fun startWatchdog() {
        main.postDelayed(object : Runnable {
            override fun run() {
                val w = web
                if (w != null && connectedUser.isNotEmpty() && w.url?.contains("/live") == true) {
                    w.evaluateJavascript(LiveScripts.HEALTH) { res ->
                        val n = res?.trim('"')?.toIntOrNull()
                        if (n == null || n < 0) {
                            inject()
                        } else {
                            itemsOnPage = n
                            setStatus(
                                if (n == 0) "Свързан с @$connectedUser, но не виждам чат. Лайвът пуснат ли е? Ако да – натисни „Диагностика“."
                                else "Чете чата на @$connectedUser ✓"
                            )
                        }
                    }
                }
                main.postDelayed(this, 5_000)
            }
        }, 5_000)
    }

    fun send(text: String, done: (Boolean, String) -> Unit) {
        val w = web
        if (w == null || connectedUser.isEmpty()) { done(false, "Браузърът не е свързан"); return }
        w.evaluateJavascript(LiveScripts.send(text)) { res ->
            when (res?.trim('"')) {
                "ok" -> done(true, "Изпратено")
                "no-input" -> done(false, "Няма поле за коментар – влез в TikTok в браузъра")
                "busy" -> done(false, "В полето вече има текст – само предложение")
                else -> done(false, "Не успя да изпрати")
            }
        }
    }

    fun diagnose(done: () -> Unit) {
        val w = web ?: return
        w.evaluateJavascript(LiveScripts.DIAGNOSE) { res ->
            diagnosis = try {
                (MiniJson.parse(res ?: "\"\"") as? String) ?: res.orEmpty()
            } catch (e: Exception) {
                res.orEmpty()
            }
            done()
        }
    }

    private fun setStatus(s: String) {
        if (s != status) {
            status = s
            ModRepository.refresh()
        }
    }

    /** Вика се от JavaScript (в друга нишка). */
    private class Bridge {
        @JavascriptInterface
        fun onEvent(json: String) {
            val o = try { MiniJson.parse(json) } catch (e: Exception) { return }
            val type = o.str("type").orEmpty()
            val name = o.str("name").orEmpty().trim()
            val text = o.str("text").orEmpty().trim()
            main.post { ModRepository.onWebEvent(type, name, text) }
        }
    }
}

/** JavaScript, който се пуска в страницата на лайва. Без знака долар – за да не се бърка с Kotlin. */
object LiveScripts {

    val SCRAPER = """
(function () {
  if (window.__radoMod) return 'already';
  window.__radoMod = true;

  var CFG = {
    item: [
      '[data-e2e="chat-message"]',
      '[data-e2e="enter-message"]',
      '[data-e2e="social-message"]',
      '[data-e2e="gift-message"]'
    ].join(','),
    name: '[data-e2e="message-owner-name"]',
    join: /joined|присъедини/i
  };

  var seen = new WeakSet();

  function kindOf(el, text) {
    var k = el.getAttribute('data-e2e') || '';
    if (k.indexOf('enter') >= 0) return 'join';
    if (k.indexOf('gift') >= 0 || k.indexOf('social') >= 0) return 'other';
    if (k.indexOf('chat') >= 0) return 'chat';
    if (CFG.join.test(text)) return 'join';
    return 'chat';
  }

  function handle(el) {
    if (seen.has(el)) return;
    seen.add(el);
    var nameEl = el.querySelector(CFG.name);
    var name = nameEl ? (nameEl.getAttribute('title') || nameEl.textContent || '').trim() : '';
    var text = (el.innerText || el.textContent || '').trim();
    if (name && text.indexOf(name) === 0) text = text.slice(name.length).replace(/^[\s:：]+/, '');
    if (!name && !text) return;
    var kind = kindOf(el, text);
    if (kind === 'other') return;
    RadoBridge.onEvent(JSON.stringify({ type: kind, name: name, text: text }));
  }

  function scan(node) {
    if (!(node instanceof Element)) return;
    if (node.matches(CFG.item)) handle(node);
    node.querySelectorAll(CFG.item).forEach(handle);
  }

  // старите съобщения не се броят
  document.querySelectorAll(CFG.item).forEach(function (e) { seen.add(e); });

  new MutationObserver(function (ms) {
    ms.forEach(function (m) { m.addedNodes.forEach(scan); });
  }).observe(document.body, { childList: true, subtree: true });

  return 'ok';
})();
""".trimIndent()

    /** Колко съобщения има в чата на страницата; -1 ако скриптът не е пуснат. */
    val HEALTH = """
(function () {
  if (!window.__radoMod) return '-1';
  return String(document.querySelectorAll('[data-e2e="chat-message"],[data-e2e="enter-message"]').length);
})();
""".trimIndent()

    private val SEND_TEMPLATE = """
(function (text) {
  var INPUT = [
    '[data-e2e="comment-input"] [contenteditable]',
    'div[contenteditable="plaintext-only"]',
    'div[contenteditable="true"]'
  ];
  var SEND = ['[data-e2e="comment-post"]', '[data-e2e="comment-send"]'];
  var el = null;
  for (var i = 0; i < INPUT.length; i++) { el = document.querySelector(INPUT[i]); if (el) break; }
  if (!el) return 'no-input';
  if ((el.innerText || el.textContent || '').trim().length > 0) return 'busy';
  el.focus();
  document.execCommand('selectAll', false, null);
  document.execCommand('insertText', false, text);
  setTimeout(function () {
    for (var j = 0; j < SEND.length; j++) {
      var b = document.querySelector(SEND[j]);
      if (b) { b.click(); return; }
    }
    var o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    el.dispatchEvent(new KeyboardEvent('keydown', o));
    el.dispatchEvent(new KeyboardEvent('keypress', o));
    el.dispatchEvent(new KeyboardEvent('keyup', o));
  }, 300);
  return 'ok';
})(__TEXT__);
""".trimIndent()

    fun send(text: String): String = SEND_TEMPLATE.replace("__TEXT__", MiniJson.write(text))

    /** Кои data-e2e атрибути има на страницата – за да намерим новите, ако TikTok смени сайта. */
    val DIAGNOSE = """
(function () {
  var counts = {};
  document.querySelectorAll('[data-e2e]').forEach(function (e) {
    var k = e.getAttribute('data-e2e');
    counts[k] = (counts[k] || 0) + 1;
  });
  var keys = Object.keys(counts).sort();
  var out = 'Адрес: ' + location.href + '\n';
  keys.forEach(function (k) { out += k + ': ' + counts[k] + '\n'; });
  return out;
})();
""".trimIndent()
}
