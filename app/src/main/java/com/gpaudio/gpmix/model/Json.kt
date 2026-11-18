package com.gpaudio.gpmix.model

/**
 * JSON minimal tanpa dependensi (supaya model dapat diuji di JVM biasa).
 * Nilai: Map<String, Any?>, List<Any?>, String, Double, Boolean, null.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("JSON: sisa teks di posisi ${p.i}")
        return v
    }

    fun stringify(v: Any?): String {
        val sb = StringBuilder()
        write(sb, v)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Float -> sb.append(num(v.toDouble()))
            is Double -> sb.append(num(v))
            is String -> quote(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                for (i in v.indices) {
                    if (i > 0) sb.append(',')
                    write(sb, v[i])
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun num(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "0"
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return d.toString()
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg di posisi $i")

        fun value(depth: Int): Any? {
            if (depth > 64) fail("terlalu dalam")
            if (i >= s.length) fail("data berakhir")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || (c in '0'..'9')) number() else fail("karakter tak terduga '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("literal salah")
            i += word.length
            return v
        }

        fun number(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(st, i).toDoubleOrNull() ?: fail("angka salah")
        }

        fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("string tidak ditutup")
                val c = s[i++]
                if (c == '"') break
                if (c != '\\') { sb.append(c); continue }
                if (i >= s.length) fail("escape terpotong")
                when (val e = s[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'u' -> {
                        if (i + 4 > s.length) fail("unicode terpotong")
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> fail("escape tidak dikenal '$e'")
                }
            }
            return sb.toString()
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws()
                out.add(value(depth + 1))
                ws()
                if (i >= s.length) fail("array tidak ditutup")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; break }
                fail("diharapkan ',' atau ']'")
            }
            return out
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("kunci harus string")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("diharapkan ':'")
                i++
                ws()
                out[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail("objek tidak ditutup")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; break }
                fail("diharapkan ',' atau '}'")
            }
            return out
        }
    }
}

// Pembaca aman bertipe untuk Map hasil parse.
internal fun Map<String, Any?>.dbl(k: String, d: Double): Double = (this[k] as? Number)?.toDouble() ?: d
internal fun Map<String, Any?>.flt(k: String, d: Float): Float = (this[k] as? Number)?.toFloat() ?: d
internal fun Map<String, Any?>.int(k: String, d: Int): Int = (this[k] as? Number)?.toInt() ?: d
internal fun Map<String, Any?>.bool(k: String, d: Boolean): Boolean = (this[k] as? Boolean) ?: d
internal fun Map<String, Any?>.str(k: String, d: String): String = (this[k] as? String) ?: d
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(k: String): List<Any?> = (this[k] as? List<Any?>) ?: emptyList()
