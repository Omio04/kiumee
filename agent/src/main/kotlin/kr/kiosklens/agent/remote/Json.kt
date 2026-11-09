package kr.kiosklens.agent.remote

// 외부 라이브러리 없이 쓰는 작은 JSON 도구 (파싱 결과: Map / List / String / Double / Boolean / null).
// 계약 v1의 요청 · 응답은 필드가 적어서 직접 다룬다. 나중에 kotlinx.serialization으로 바꿔도 DtoMapper만 고치면 된다.

/** 외부 라이브러리 없이 쓰는 아주 작은 JSON 도구. (파싱 결과: Map / List / String / Double / Boolean / null) */
object Json {
    class JsonException(msg: String) : Exception(msg)

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) throw JsonException("뒤에 남은 글자")
        return v
    }

    fun parseOrNull(s: String): Any? = try { parse(s) } catch (e: Exception) { null }

    /** 문자열 안에서 처음 나오는 JSON 객체({...})를 꺼내 파싱한다. 앞뒤 설명이 붙어도 된다. */
    @Suppress("UNCHECKED_CAST")
    fun firstObject(raw: String): Map<String, Any?>? {
        var start = raw.indexOf('{')
        while (start != -1) {
            var depth = 0; var inStr = false; var esc = false
            for (i in start until raw.length) {
                val c = raw[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                if (c == '"') inStr = true
                else if (c == '{') depth++
                else if (c == '}') { depth--; if (depth == 0) {
                    val v = parseOrNull(raw.substring(start, i + 1))
                    if (v is Map<*, *>) return v as Map<String, Any?>
                    break
                } }
            }
            start = raw.indexOf('{', start + 1)
        }
        return null
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) throw JsonException("끝")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("예상 밖 글자 $c")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw JsonException("글자 오류"); i += w.length; return v }
        fun num(): Double { val st = i; if (s[i] == '-') i++; while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++; return s.substring(st, i).toDoubleOrNull() ?: throw JsonException("숫자 오류") }
        fun str(): String {
            i++; val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> { if (i >= s.length) throw JsonException("끝"); when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { if (i + 4 > s.length) throw JsonException("유니코드"); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e) } }
                    else -> sb.append(c)
                }
            }
            throw JsonException("문자열이 안 닫힘")
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) { out.add(value()); ws(); if (i >= s.length) throw JsonException("끝"); if (s[i] == ',') { i++; continue }; if (s[i] == ']') { i++; return out }; throw JsonException("배열 오류") }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') throw JsonException("키 오류"); val k = str(); ws()
                if (i >= s.length || s[i] != ':') throw JsonException("콜론"); i++
                out[k] = value(); ws(); if (i >= s.length) throw JsonException("끝")
                if (s[i] == ',') { i++; continue }; if (s[i] == '}') { i++; return out }; throw JsonException("객체 오류")
            }
        }
    }
}
