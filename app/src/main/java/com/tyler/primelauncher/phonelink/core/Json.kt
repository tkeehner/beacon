package com.tyler.primelauncher.phonelink.core

/**
 * Minimal JSON for the Phone Link wire: objects, arrays, strings, numbers (Long/Double), booleans, null.
 * Used so the wire protocol has zero Android / org.json dependency and can be unit-tested on a plain JVM.
 * Not a general-purpose parser: numbers that fit in a Long are Longs (matching Python's int), others Doubles.
 */
object Json {
    class JsonError(msg: String) : Exception(msg)
    sealed class Value { abstract fun encode(): String }
    object Null : Value() { override fun encode() = "null" }
    data class Bool(val v: Boolean) : Value() { override fun encode() = if (v) "true" else "false" }
    data class Num(val v: Number) : Value() { override fun encode() = if (v is Double || v is Float) v.toString() else v.toLong().toString() }
    data class Str(val v: String) : Value() {
        override fun encode(): String {
            val sb = StringBuilder("\""); for (c in v) when (c) {
                '\\' -> sb.append("\\\\"); '"' -> sb.append("\\\""); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }; return sb.append('"').toString()
        }
    }
    data class Arr(val items: List<Value>) : Value() { override fun encode() = items.joinToString(",", "[", "]") { it.encode() } }
    data class Obj(val map: LinkedHashMap<String, Value>) : Value() {
        override fun encode() = map.entries.joinToString(",", "{", "}") { Str(it.key).encode() + ":" + it.value.encode() }
        fun raw(k: String): Any? = when (val v = map[k]) { is Num -> v.v; is Str -> v.v; is Bool -> v.v; is Null -> null; is Arr -> v; is Obj -> v; null -> null }
        fun stringOrNull(k: String) = (map[k] as? Str)?.v
        fun longOrNull(k: String) = (map[k] as? Num)?.v?.toLong()
        fun intOrNull(k: String) = longOrNull(k)?.toInt()
        fun boolOrNull(k: String) = (map[k] as? Bool)?.v
        fun objOrNull(k: String) = map[k] as? Obj
        fun arrOrNull(k: String) = (map[k] as? Arr)?.items
        operator fun get(k: String) = map[k]
        fun with(k: String, v: Value): Obj { val m = LinkedHashMap(map); m[k] = v; return Obj(m) }
    }

    fun of(vararg pairs: Pair<String, Any?>): Obj {
        val m = LinkedHashMap<String, Value>()
        for ((k, v) in pairs) m[k] = wrap(v)
        return Obj(m)
    }
    fun wrap(v: Any?): Value = when (v) {
        null -> Null; is Value -> v; is Boolean -> Bool(v); is Int -> Num(v.toLong()); is Long -> Num(v); is Float -> Num(v.toDouble())
        is Double -> Num(v); is String -> Str(v); is Map<*, *> -> of(*v.entries.map { it.key as String to it.value }.toTypedArray())
        is List<*> -> Arr(v.map { wrap(it) }); else -> Str(v.toString())
    }

    /** Insert seq/ts/nonce into a JSON object body that was built without them. Body must start with '{'. */
    fun addEnvelope(body: String, seq: Long, ts: Long, nonce: String): String {
        require(body.startsWith("{") && body.endsWith("}"))
        val inner = body.substring(1, body.length - 1)
        val env = "\"seq\":$seq,\"ts\":$ts,\"nonce\":\"$nonce\""
        return if (inner.isBlank()) "{$env}" else "{$inner,$env}"
    }

    fun parse(s: String): Value { val p = Parser(s); val v = p.parseValue(); if (!p.done()) throw JsonError("trailing junk"); return v }

    private class Parser(val s: String) {
        var i = 0
        fun done() = i >= s.length
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char { skip(); if (i >= s.length) throw JsonError("eof"); return s[i] }
        fun take(c: Char) { if (peek() != c) throw JsonError("expected $c"); i++ }
        fun parseValue(): Value = when (peek()) {
            '{' -> parseObj(); '[' -> parseArr(); '"' -> Str(parseStr()); 't', 'f' -> parseBool(); 'n' -> { expect("null"); Null }
            '-', in '0'..'9' -> parseNum(); else -> throw JsonError("bad value at $i")
        }
        fun parseObj(): Obj {
            take('{'); val m = LinkedHashMap<String, Value>(); skip()
            if (peek() == '}') { take('}'); return Obj(m) }
            while (true) {
                val k = parseStr(); take(':'); m[k] = parseValue(); skip()
                if (peek() == '}') { take('}'); return Obj(m) }
                take(',')
            }
        }
        fun parseArr(): Arr {
            take('['); val items = ArrayList<Value>(); skip()
            if (peek() == ']') { take(']'); return Arr(items) }
            while (true) {
                items.add(parseValue()); skip()
                if (peek() == ']') { take(']'); return Arr(items) }
                take(',')
            }
        }
        fun parseStr(): String {
            take('"'); val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw JsonError("bad escape")
                    }
                    else -> sb.append(c)
                }
            }
            throw JsonError("unterminated string")
        }
        fun parseBool(): Bool = when {
            s.startsWith("true", i) -> { i += 4; Bool(true) }; s.startsWith("false", i) -> { i += 5; Bool(false) }
            else -> throw JsonError("bad bool")
        }
        fun expect(w: String) { if (!s.startsWith(w, i)) throw JsonError("expected $w"); i += w.length }
        fun parseNum(): Num {
            val start = i; if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isFloat = false
            if (i < s.length && s[i] == '.') { isFloat = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true; i++; if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            return if (isFloat) Num(t.toDouble()) else Num(t.toLong())
        }
    }
}
