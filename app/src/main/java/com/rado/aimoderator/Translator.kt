package com.rado.aimoderator

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Безплатен превод през Google Translate (същият като в преводача на коментари).
 * Превежда само маркираните и важните коментари, за да не товари мрежата.
 */
object Translator {

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    /** Превежда на български. Връща "" ако текстът вече е на български или няма превод. */
    fun toBulgarian(text: String): String {
        val url = "$ENDPOINT?client=gtx&sl=auto&tl=bg&dt=t&q=" + URLEncoder.encode(text.take(500), "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) return ""
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body, text)
        } finally {
            conn.disconnect()
        }
    }

    /** Отговорът е [[["превод","оригинал",...],...],null,"en",...]. */
    fun parse(body: String, original: String): String {
        val root = MiniJson.parse(body) as? List<*> ?: return ""
        val lang = root.getOrNull(2) as? String
        if (lang == "bg") return ""
        val parts = root.getOrNull(0) as? List<*> ?: return ""
        val out = parts.mapNotNull { (it as? List<*>)?.getOrNull(0) as? String }.joinToString("").trim()
        return if (out.equals(original.trim(), ignoreCase = true)) "" else out
    }

    /** Има ли смисъл да се превежда: поне 3 латински букви и не е само кирилица/емоджи. */
    fun worthTranslating(text: String): Boolean {
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' || it in 'À'..'ɏ' }
        val cyr = text.count { it in 'а'..'я' || it in 'А'..'Я' }
        return latin >= 3 && latin > cyr
    }
}
