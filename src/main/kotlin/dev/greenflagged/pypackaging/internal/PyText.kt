package dev.greenflagged.pypackaging.internal

/*
 * Python string semantics that packaging relies on. Python strings are sequences of
 * code points; Kotlin strings are UTF-16. Every helper here works on UTF-16 indices
 * but classifies by code point, so a surrogate pair is never split.
 */

/** Python's `\s` and `str.isspace()` (identical sets; verified against CPython 3.14). */
internal fun isPyWhitespace(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, in 0x1C..0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    -> true
    else -> false
}

/** Python's `str.strip()` with no arguments. */
internal fun pyStrip(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isPyWhitespace(s[start])) start++
    while (end > start && isPyWhitespace(s[end - 1])) end--
    return s.substring(start, end)
}

internal fun skipPyWhitespace(s: String, from: Int): Int {
    var i = from
    while (i < s.length && isPyWhitespace(s[i])) i++
    return i
}

private fun inRanges(table: IntArray, cp: Int): Boolean {
    var lo = 0
    var hi = table.size / 2 - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val start = table[mid * 2]
        val end = table[mid * 2 + 1]
        when {
            cp < start -> hi = mid - 1
            cp > end -> lo = mid + 1
            else -> return true
        }
    }
    return false
}

/** Python's `\w`: `str.isalnum()` or `_`, from a table pinned to Python's Unicode data. */
internal fun isPyWordCodePoint(cp: Int): Boolean = inRanges(PY_WORD_RANGES, cp)

internal fun isPyPrintable(cp: Int): Boolean = inRanges(PY_PRINTABLE_RANGES, cp)

/** Whether the code point ending just before [index] is a Python word character. */
internal fun isWordBefore(s: String, index: Int): Boolean =
    index > 0 && isPyWordCodePoint(s.codePointBefore(index))

/** Whether the code point starting at [index] is a Python word character. */
internal fun isWordAt(s: String, index: Int): Boolean =
    index < s.length && isPyWordCodePoint(s.codePointAt(index))

/** Python's `\b` at [index]. */
internal fun isWordBoundary(s: String, index: Int): Boolean = isWordBefore(s, index) != isWordAt(s, index)

internal fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

internal fun isAsciiAlnumIgnoreCase(c: Char): Boolean = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'

/**
 * Matches the lowercase ASCII keyword [kw] at [pos] the way Python's `re.IGNORECASE`
 * does. With [unicode] false the pattern was in an `(?a:...)` group, so only ASCII
 * case folds apply. With [unicode] true Python also folds U+0130 and U+0131 to `i`,
 * and U+017F to `s` (the only extra folds for the letters packaging's keywords use).
 * Returns the end index, or -1.
 */
internal fun matchKeyword(s: String, pos: Int, kw: String, unicode: Boolean): Int {
    if (pos + kw.length > s.length) return -1
    for (k in kw.indices) {
        val c = s[pos + k]
        val want = kw[k]
        val ok = c == want || c == want.uppercaseChar() ||
            (unicode && ((want == 'i' && (c == 'İ' || c == 'ı')) || (want == 's' && c == 'ſ')))
        if (!ok) return -1
    }
    return pos + kw.length
}

private val LOWER_MAP: Map<Int, Int> = HashMap<Int, Int>(PY_LOWER_PAIRS.size).also { m ->
    for (k in PY_LOWER_PAIRS.indices step 2) m[PY_LOWER_PAIRS[k]] = PY_LOWER_PAIRS[k + 1]
}

private fun isCaseIgnorable(cp: Int): Boolean = inRanges(PY_CASE_IGNORABLE_RANGES, cp)

private fun isCased(cp: Int): Boolean = inRanges(PY_CASED_RANGES, cp)

/**
 * Python's `str.lower()`, from tables pinned to Python's Unicode data rather than the
 * JVM's: full lowercase mapping (U+0130 becomes "i\u0307"), and U+03A3 becomes final
 * sigma when preceded by a cased letter and not followed by one, ignoring
 * case-ignorable characters in between (CPython's `handle_capital_sigma`).
 */
internal fun pyLower(s: String): String {
    var needsWork = false
    for (c in s) {
        if (c.code >= 0x80 || c in 'A'..'Z') {
            needsWork = true
            break
        }
    }
    if (!needsWork) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        when (cp) {
            0x130 -> sb.append("i\u0307")
            0x3A3 -> sb.append(if (isFinalSigma(s, i)) '\u03C2' else '\u03C3')
            else -> sb.appendCodePoint(LOWER_MAP[cp] ?: cp)
        }
        i += Character.charCount(cp)
    }
    return sb.toString()
}

private fun isFinalSigma(s: String, index: Int): Boolean {
    var j = index
    var before = -1
    while (j > 0) {
        val cp = s.codePointBefore(j)
        j -= Character.charCount(cp)
        if (!isCaseIgnorable(cp)) {
            before = cp
            break
        }
    }
    if (before < 0 || !isCased(before)) return false
    j = index + 1
    while (j < s.length) {
        val cp = s.codePointAt(j)
        if (!isCaseIgnorable(cp)) return !isCased(cp)
        j += Character.charCount(cp)
    }
    return true
}

/** Python's `repr()` of a `str`. */
internal fun pyRepr(s: String): String {
    val quote = if (s.contains('\'') && !s.contains('"')) '"' else '\''
    val sb = StringBuilder(s.length + 2)
    sb.append(quote)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == quote.code || cp == '\\'.code -> sb.append('\\').appendCodePoint(cp)
            cp == '\t'.code -> sb.append("\\t")
            cp == '\n'.code -> sb.append("\\n")
            cp == '\r'.code -> sb.append("\\r")
            cp < 0x20 || cp == 0x7F -> sb.append("\\x").append(hex(cp, 2))
            cp < 0x7F -> sb.appendCodePoint(cp)
            isPyPrintable(cp) && !(cp in 0xD800..0xDFFF) -> sb.appendCodePoint(cp)
            cp <= 0xFF -> sb.append("\\x").append(hex(cp, 2))
            cp <= 0xFFFF -> sb.append("\\u").append(hex(cp, 4))
            else -> sb.append("\\U").append(hex(cp, 8))
        }
    }
    sb.append(quote)
    return sb.toString()
}

private fun hex(value: Int, width: Int): String = Integer.toHexString(value).padStart(width, '0')

/** Number of code points before UTF-16 index [index]; Python positions are code point offsets. */
internal fun codePointOffset(s: String, index: Int): Int = s.codePointCount(0, index.coerceIn(0, s.length))

/** Thrown when a quoted marker string is not a valid Python string literal. */
internal class PyLiteralException : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Decodes a Python string literal such as `'a\x41'`, as `ast.literal_eval` would.
 * [literal] starts and ends with the same quote character, and the quote does not
 * appear unescaped inside. Throws [PyLiteralException] where Python raises
 * `SyntaxError` or `ValueError`.
 *
 * `\N{name}` escapes are resolved with the JVM's Unicode name table. Name aliases,
 * and characters the JVM's Unicode version does not know, are rejected, so this
 * can reject a literal Python accepts but never the reverse.
 */
internal fun decodePyStringLiteral(literal: String): String {
    val quote = literal[0]
    // Python's tokenizer translates \r\n and \r to \n before tokenizing.
    val body = literal.substring(1, literal.length - 1)
    val sb = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\u0000' -> throw PyLiteralException() // "source code string cannot contain null bytes"
            c == '\n' || c == '\r' -> throw PyLiteralException() // unterminated string literal
            Character.isSurrogate(c) -> {
                // A lone surrogate cannot be encoded to UTF-8 for compilation.
                if (Character.isHighSurrogate(c) && i + 1 < body.length && Character.isLowSurrogate(body[i + 1])) {
                    sb.append(c).append(body[i + 1])
                    i += 2
                    continue
                }
                throw PyLiteralException()
            }
            c != '\\' -> sb.append(c)
            else -> {
                if (i + 1 >= body.length) throw PyLiteralException() // escapes the closing quote
                val e = body[i + 1]
                i += 2
                when (e) {
                    '\n' -> {}
                    '\r' -> { if (i < body.length && body[i] == '\n') i++ }
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    'a' -> sb.append('\u0007')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'v' -> sb.append('\u000B')
                    in '0'..'7' -> {
                        var value = e - '0'
                        var n = 1
                        while (n < 3 && i < body.length && body[i] in '0'..'7') {
                            value = value * 8 + (body[i] - '0')
                            i++
                            n++
                        }
                        sb.appendCodePoint(value)
                    }
                    'x' -> { sb.appendCodePoint(readHex(body, i, 2)); i += 2 }
                    'u' -> { sb.append(readHex(body, i, 4).toChar()); i += 4 }
                    'U' -> {
                        val cp = readHex(body, i, 8)
                        if (cp > 0x10FFFF) throw PyLiteralException()
                        sb.appendCodePoint(cp)
                        i += 8
                    }
                    'N' -> {
                        if (i >= body.length || body[i] != '{') throw PyLiteralException()
                        val close = body.indexOf('}', i + 1)
                        if (close < 0) throw PyLiteralException()
                        val name = body.substring(i + 1, close)
                        sb.appendCodePoint(lookupUnicodeName(name))
                        i = close + 1
                    }
                    else -> {
                        // Unknown escapes are kept verbatim (Python only warns).
                        sb.append('\\')
                        i -= 1
                    }
                }
            }
        }
        if (c != '\\') i++
    }
    // The opening quote is not part of the body but must be consistent; nothing else to check.
    check(quote == '\'' || quote == '"')
    return sb.toString()
}

private fun readHex(s: String, from: Int, count: Int): Int {
    if (from + count > s.length) throw PyLiteralException()
    var value = 0
    for (k in 0 until count) {
        val d = Character.digit(s[from + k], 16)
        if (d < 0 || s[from + k].code > 0x7F) throw PyLiteralException()
        value = value * 16 + d
    }
    return value
}

private fun lookupUnicodeName(name: String): Int {
    if (name.isEmpty() || name != name.trim() || name.any { it.code > 0x7F }) throw PyLiteralException()
    val cp = try {
        Character.codePointOf(name)
    } catch (_: IllegalArgumentException) {
        throw PyLiteralException()
    }
    // Character.codePointOf also accepts "<block name> <hex>" for unnamed code points;
    // Python does not, so require the canonical name to round-trip.
    val canonical = Character.getName(cp) ?: throw PyLiteralException()
    if (!canonical.equals(name, ignoreCase = true)) throw PyLiteralException()
    return cp
}

/** Python's `str` ordering: by code point, which differs from UTF-16 order above U+FFFF. */
internal fun pyCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        if (x != y) return x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    return when {
        i < a.length -> 1
        j < b.length -> -1
        else -> 0
    }
}
