package com.rado.aimoderator

/**
 * Малък JSON четец/писач без външни библиотеки.
 * Обекти → Map<String, Any?>, масиви → List<Any?>, числа → Double/Long, true/false, null.
 */
object MiniJson {

    class JsonException(msg: String) : Exception(msg)

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) throw JsonException("Излишен текст на позиция ${p.i}")
        return v
    }

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(item, sb)
                }
                sb.append(']')
            }
            is Enum<*> -> quote(v.name, sb)
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw JsonException("Неочакван край")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("Неочакван знак '$c' на $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("Очаквах $word на $i")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws()
                if (peek() != '"') throw JsonException("Очаквах ключ на $i")
                val k = str()
                ws()
                expect(':')
                ws()
                m[k] = value()
                ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw JsonException("Очаквах , или } на $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            ws()
            if (peek() == ']') { i++; return l }
            while (true) {
                ws()
                l += value()
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw JsonException("Очаквах , или ] на $i")
                }
            }
        }

        private fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("Незатворен низ")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonException("Незатворен низ")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("Лош \\u")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonException("Лош escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("Очаквах '$c' на $i")
            i++
        }
    }
}

// Удобни помощници за навигиране в разчетен JSON
@Suppress("UNCHECKED_CAST")
fun Any?.obj(key: String): Map<String, Any?>? = (this as? Map<String, Any?>)?.get(key) as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.list(key: String): List<Any?>? = (this as? Map<String, Any?>)?.get(key) as? List<Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.str(key: String): String? = (this as? Map<String, Any?>)?.get(key)?.let { if (it is String) it else it.toString() }
