package com.jayant.spendsense.json

/**
 * Small multiplatform stand-in for the subset of org.json the app uses (AI request/response bodies, tool
 * arguments and results). Same names and lenient accessor semantics as org.json, so the code that builds and
 * reads provider payloads is shared verbatim between Android and iOS. Keys keep insertion order.
 */
class JSONException(message: String) : RuntimeException(message)

private object JsonNull {
    override fun toString() = "null"
    override fun equals(other: Any?) = other == null || other === this
    override fun hashCode() = 0
}

class JSONObject() {
    private val map = LinkedHashMap<String, Any>()

    constructor(source: String) : this() {
        val v = JsonReader(source).readTop()
        if (v !is JSONObject) throw JSONException("A JSONObject text must begin with '{'")
        map.putAll(v.map)
    }

    constructor(source: Map<*, *>) : this() {
        for ((k, v) in source) if (v != null) map[k.toString()] = wrap(v)
    }

    fun put(key: String, value: Any?): JSONObject {
        if (value == null) map.remove(key) else map[key] = wrap(value)
        return this
    }

    fun opt(key: String): Any? = map[key]
    fun has(key: String): Boolean = map.containsKey(key)
    fun isNull(key: String): Boolean = map[key].let { it == null || it === NULL }
    fun remove(key: String): Any? = map.remove(key)
    fun length(): Int = map.size
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun keySet(): Set<String> = map.keys

    fun get(key: String): Any = map[key] ?: throw JSONException("JSONObject[\"$key\"] not found.")

    fun getString(key: String): String = get(key) as? String ?: throw JSONException("JSONObject[\"$key\"] is not a string.")
    fun getInt(key: String): Int = toNumber(get(key))?.toInt() ?: throw JSONException("JSONObject[\"$key\"] is not an int.")
    fun getLong(key: String): Long = toNumber(get(key))?.toLong() ?: throw JSONException("JSONObject[\"$key\"] is not a long.")
    fun getDouble(key: String): Double = toNumber(get(key))?.toDouble() ?: throw JSONException("JSONObject[\"$key\"] is not a double.")
    fun getBoolean(key: String): Boolean = toBool(get(key)) ?: throw JSONException("JSONObject[\"$key\"] is not a Boolean.")
    fun getJSONObject(key: String): JSONObject = get(key) as? JSONObject ?: throw JSONException("JSONObject[\"$key\"] is not a JSONObject.")
    fun getJSONArray(key: String): JSONArray = get(key) as? JSONArray ?: throw JSONException("JSONObject[\"$key\"] is not a JSONArray.")

    fun optString(key: String): String = optString(key, "")
    fun optString(key: String, fallback: String): String {
        val v = map[key]
        return if (v == null || v === NULL) fallback else stringOf(v)
    }
    fun optInt(key: String): Int = optInt(key, 0)
    fun optInt(key: String, fallback: Int): Int = toNumber(map[key])?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = toNumber(map[key])?.toLong() ?: fallback
    fun optDouble(key: String): Double = optDouble(key, Double.NaN)
    fun optDouble(key: String, fallback: Double): Double = toNumber(map[key])?.toDouble() ?: fallback
    fun optBoolean(key: String): Boolean = optBoolean(key, false)
    fun optBoolean(key: String, fallback: Boolean): Boolean = toBool(map[key]) ?: fallback
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray

    override fun toString(): String = StringBuilder().also { writeTo(it) }.toString()

    internal fun writeTo(sb: StringBuilder) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            quote(k, sb)
            sb.append(':')
            writeValue(v, sb)
        }
        sb.append('}')
    }

    override fun equals(other: Any?) = other is JSONObject && other.map == map
    override fun hashCode() = map.hashCode()

    companion object {
        /** JSON null as a value (org.json's JSONObject.NULL). Hidden from Obj-C: `NULL` is a C macro there. */
        @OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
        @kotlin.native.HiddenFromObjC
        val NULL: Any = JsonNull
    }
}

class JSONArray() {
    private val list = ArrayList<Any>()

    constructor(source: String) : this() {
        val v = JsonReader(source).readTop()
        if (v !is JSONArray) throw JSONException("A JSONArray text must start with '['")
        list.addAll(v.list)
    }

    constructor(source: Collection<*>) : this() {
        for (v in source) list.add(if (v == null) JSONObject.NULL else wrap(v))
    }

    fun put(value: Any?): JSONArray { list.add(if (value == null) JSONObject.NULL else wrap(value)); return this }
    fun length(): Int = list.size
    fun opt(i: Int): Any? = list.getOrNull(i)
    fun get(i: Int): Any = list.getOrNull(i) ?: throw JSONException("JSONArray[$i] not found.")
    fun isNull(i: Int): Boolean = list.getOrNull(i).let { it == null || it === JSONObject.NULL }
    fun remove(i: Int): Any? = if (i in list.indices) list.removeAt(i) else null

    fun getString(i: Int): String = get(i) as? String ?: throw JSONException("JSONArray[$i] is not a string.")
    fun getInt(i: Int): Int = toNumber(get(i))?.toInt() ?: throw JSONException("JSONArray[$i] is not a number.")
    fun getLong(i: Int): Long = toNumber(get(i))?.toLong() ?: throw JSONException("JSONArray[$i] is not a number.")
    fun getDouble(i: Int): Double = toNumber(get(i))?.toDouble() ?: throw JSONException("JSONArray[$i] is not a number.")
    fun getJSONObject(i: Int): JSONObject = get(i) as? JSONObject ?: throw JSONException("JSONArray[$i] is not a JSONObject.")
    fun getJSONArray(i: Int): JSONArray = get(i) as? JSONArray ?: throw JSONException("JSONArray[$i] is not a JSONArray.")
    fun optJSONObject(i: Int): JSONObject? = list.getOrNull(i) as? JSONObject
    fun optJSONArray(i: Int): JSONArray? = list.getOrNull(i) as? JSONArray
    fun optString(i: Int, fallback: String = ""): String = list.getOrNull(i).let { if (it == null || it === JSONObject.NULL) fallback else stringOf(it) }
    fun optDouble(i: Int, fallback: Double = Double.NaN): Double = toNumber(list.getOrNull(i))?.toDouble() ?: fallback

    override fun toString(): String = StringBuilder().also { writeTo(it) }.toString()

    internal fun writeTo(sb: StringBuilder) {
        sb.append('[')
        list.forEachIndexed { i, v -> if (i > 0) sb.append(','); writeValue(v, sb) }
        sb.append(']')
    }

    override fun equals(other: Any?) = other is JSONArray && other.list == list
    override fun hashCode() = list.hashCode()
}

// ---- helpers ------------------------------------------------------------------------------------------

private fun wrap(v: Any): Any = when (v) {
    is JSONObject, is JSONArray, is String, is Boolean, is Number -> v
    JSONObject.NULL -> JSONObject.NULL
    is Map<*, *> -> JSONObject(v)
    is Collection<*> -> JSONArray(v)
    is Array<*> -> JSONArray(v.toList())
    is Char -> v.toString()
    is Enum<*> -> v.name
    else -> v.toString()
}

private fun toNumber(v: Any?): Number? = when (v) {
    is Number -> v
    is String -> v.trim().let { s -> s.toLongOrNull() ?: s.toDoubleOrNull() }
    else -> null
}

private fun toBool(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    else -> null
}

private fun stringOf(v: Any): String = when (v) {
    is String -> v
    else -> v.toString()
}

/** org.json style: integral doubles drop the ".0" ("254.0" -> "254"). */
private fun numberToString(n: Number): String {
    if (n is Double || n is Float) {
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite()) throw JSONException("JSON does not allow non-finite numbers.")
        var s = d.toString()
        if (s.indexOf('.') > 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
            s = s.trimEnd('0')
            if (s.endsWith('.')) s = s.dropLast(1)
        }
        return s
    }
    return n.toString()
}

private fun writeValue(v: Any, sb: StringBuilder) {
    when (v) {
        is JSONObject -> v.writeTo(sb)
        is JSONArray -> v.writeTo(sb)
        is String -> quote(v, sb)
        is Number -> sb.append(numberToString(v))
        is Boolean -> sb.append(v.toString())
        else -> if (v === JSONObject.NULL) sb.append("null") else quote(v.toString(), sb)
    }
}

private fun quote(s: String, sb: StringBuilder) {
    sb.append('"')
    var prev = ' '
    for (c in s) {
        when (c) {
            '\\', '"' -> { sb.append('\\'); sb.append(c) }
            '/' -> { if (prev == '<') sb.append('\\'); sb.append(c) }
            '\b' -> sb.append("\\b")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\u000C' -> sb.append("\\f")
            '\r' -> sb.append("\\r")
            else -> if (c < ' ' || c in '\u0080'..' ' || c in ' '..'℀') {
                sb.append("\\u")
                sb.append(c.code.toString(16).padStart(4, '0'))
            } else sb.append(c)
        }
        prev = c
    }
    sb.append('"')
}

/** Strict-enough recursive-descent reader for API payloads. */
private class JsonReader(private val s: String) {
    private var i = 0

    fun readTop(): Any {
        val v = readValue()
        skipWs()
        if (i < s.length) throw err("Unexpected trailing characters")
        return v
    }

    private fun err(msg: String) = JSONException("$msg at character $i")

    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun readValue(): Any {
        skipWs()
        if (i >= s.length) throw err("Unexpected end of input")
        return when (val c = s[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JSONObject.NULL)
            else -> if (c == '-' || c.isDigit()) readNumber() else throw err("Unexpected character '$c'")
        }
    }

    private fun literal(word: String, value: Any): Any {
        if (!s.startsWith(word, i)) throw err("Invalid literal")
        i += word.length
        return value
    }

    private fun readObject(): JSONObject {
        val o = JSONObject()
        i++ // {
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return o }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') throw err("Expected a key")
            val key = readString()
            skipWs()
            if (i >= s.length || s[i] != ':') throw err("Expected ':'")
            i++
            val v = readValue()
            o.put(key, v)
            skipWs()
            if (i >= s.length) throw err("Unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return o }
                else -> throw err("Expected ',' or '}'")
            }
        }
    }

    private fun readArray(): JSONArray {
        val a = JSONArray()
        i++ // [
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            a.put(readValue())
            skipWs()
            if (i >= s.length) throw err("Unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return a }
                else -> throw err("Expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        val sb = StringBuilder()
        i++ // opening quote
        while (true) {
            if (i >= s.length) throw err("Unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) throw err("Unterminated escape")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw err("Bad unicode escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw err("Illegal escape")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): Number {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val t = s.substring(start, i)
        val isDecimal = t.any { it == '.' || it == 'e' || it == 'E' } || t == "-0"
        if (!isDecimal) {
            t.toIntOrNull()?.let { return it }
            t.toLongOrNull()?.let { return it }
        }
        return t.toDoubleOrNull() ?: throw err("Bad number '$t'")
    }
}
