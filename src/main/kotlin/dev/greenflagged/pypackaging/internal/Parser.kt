package dev.greenflagged.pypackaging.internal

import dev.greenflagged.pypackaging.Limits

/*
 * A port of packaging 26.3's `_tokenizer.py` and `_parser.py`: a recursive-descent
 * parser for PEP 508 requirement strings and environment markers.
 *
 * Python's tokenizer matches a regular expression per token at the current position.
 * Here each token rule is a hand-written matcher returning what `re.match` would,
 * including Python's Unicode-aware `\b` (via a word-character table pinned to Python's
 * Unicode data). Positions are UTF-16 indices internally and converted to code point
 * offsets when an error message is rendered, so messages match Python's exactly.
 */

internal enum class Rule {
    LEFT_PARENTHESIS, RIGHT_PARENTHESIS, LEFT_BRACKET, RIGHT_BRACKET, SEMICOLON, COMMA,
    QUOTED_STRING, OP, BOOLOP, IN, NOT, VARIABLE, SPECIFIER, AT, URL, IDENTIFIER,
    VERSION_PREFIX_TRAIL, VERSION_LOCAL_LABEL_TRAIL, WS, END,
}

internal class Token(val rule: Rule, val text: String, val position: Int)

/** `ParserSyntaxError`. [toString]-style rendering is in [render]. */
internal class ParserSyntaxError(
    override val message: String,
    val source: String,
    val spanStart: Int,
    val spanEnd: Int,
) : Exception(message) {
    /** Python's `str(ParserSyntaxError)`: message, source, and a `~~~^` marker line. */
    fun render(): String {
        val start = codePointOffset(source, spanStart)
        val end = codePointOffset(source, spanEnd)
        val marker = " ".repeat(start) + "~".repeat(maxOf(0, end - start)) + "^"
        return "$message\n    $source\n    $marker"
    }
}

private val OPS = arrayOf("===", "==", "~=", "!=", "<=", ">=", "<", ">")

private val VARIABLES = arrayOf(
    "python_version", "python_full_version", "os_name", "os.name", "sys_platform", "sys.platform",
    "platform_release", "platform_system", "platform_version", "platform.version",
    "platform_machine", "platform.machine", "platform_python_implementation",
    "platform.python_implementation", "python_implementation", "implementation_name",
    "implementation_version", "extras", "extra", "dependency_groups",
)

/** `\b(word)\b` at [pos]: returns the end index, or -1. */
private fun matchWord(s: String, pos: Int, word: String): Int {
    if (!s.startsWith(word, pos)) return -1
    if (!isWordBoundary(s, pos) || !isWordBoundary(s, pos + word.length)) return -1
    return pos + word.length
}

private fun isIdentChar(c: Char): Boolean = isAsciiAlnumIgnoreCase(c) || c == '.' || c == '_' || c == '-'

private fun isIdentTail(c: Char): Boolean = isAsciiAlnumIgnoreCase(c) || c == '.' || c == '-'

/** Returns the end of the match of [rule] at [pos], or -1 if it does not match. */
internal fun matchRule(rule: Rule, s: String, pos: Int): Int {
    val n = s.length
    fun single(c: Char) = if (pos < n && s[pos] == c) pos + 1 else -1
    return when (rule) {
        Rule.LEFT_PARENTHESIS -> single('(')
        Rule.RIGHT_PARENTHESIS -> single(')')
        Rule.LEFT_BRACKET -> single('[')
        Rule.RIGHT_BRACKET -> single(']')
        Rule.SEMICOLON -> single(';')
        Rule.COMMA -> single(',')
        Rule.AT -> single('@')
        Rule.QUOTED_STRING -> {
            if (pos >= n || (s[pos] != '\'' && s[pos] != '"')) return -1
            val close = s.indexOf(s[pos], pos + 1)
            if (close < 0) -1 else close + 1
        }
        Rule.OP -> OPS.firstOrNull { s.startsWith(it, pos) }?.let { pos + it.length } ?: -1
        Rule.BOOLOP -> matchWord(s, pos, "or").takeIf { it >= 0 } ?: matchWord(s, pos, "and")
        Rule.IN -> matchWord(s, pos, "in")
        Rule.NOT -> matchWord(s, pos, "not")
        Rule.VARIABLE -> {
            for (v in VARIABLES) {
                val end = matchWord(s, pos, v)
                if (end >= 0) return end
            }
            -1
        }
        Rule.SPECIFIER -> SpecifierScanner.scan(s, pos)
        Rule.URL -> {
            var k = pos
            while (k < n && s[k] != ' ' && s[k] != '\t') k++
            if (k > pos) k else -1
        }
        Rule.IDENTIFIER -> {
            // \b[a-zA-Z0-9][a-zA-Z0-9._-]*\b, backtracking from the longest run.
            if (pos >= n || !isAsciiAlnumIgnoreCase(s[pos]) || isWordBefore(s, pos)) return -1
            var end = pos + 1
            while (end < n && isIdentChar(s[end])) end++
            while (end > pos) {
                if (isWordBoundary(s, end)) return end
                end--
            }
            -1
        }
        Rule.VERSION_PREFIX_TRAIL -> if (s.startsWith(".*", pos)) pos + 2 else -1
        Rule.VERSION_LOCAL_LABEL_TRAIL -> {
            // \+[a-z0-9]+(?:[-_.][a-z0-9]+)*, case-sensitive.
            fun lowerAlnum(c: Char) = c in 'a'..'z' || c in '0'..'9'
            if (pos + 1 >= n || s[pos] != '+' || !lowerAlnum(s[pos + 1])) return -1
            var k = pos + 1
            while (k < n && lowerAlnum(s[k])) k++
            while (k + 1 < n && (s[k] == '-' || s[k] == '_' || s[k] == '.') && lowerAlnum(s[k + 1])) {
                k++
                while (k < n && lowerAlnum(s[k])) k++
            }
            k
        }
        Rule.WS -> {
            var k = pos
            while (k < n && (s[k] == ' ' || s[k] == '\t')) k++
            if (k > pos) k else -1
        }
        Rule.END -> if (pos == n) pos else -1
    }
}

internal class Tokenizer(val source: String) {
    var position: Int = 0
        private set
    private var nextToken: Token? = null

    fun consume(rule: Rule) {
        if (check(rule)) read()
    }

    fun check(rule: Rule, peek: Boolean = false): Boolean {
        check(nextToken == null) { "Cannot check for $rule, already have ${nextToken?.rule}" }
        val end = matchRule(rule, source, position)
        if (end < 0) return false
        if (!peek) nextToken = Token(rule, source.substring(position, end), position)
        return true
    }

    fun expect(rule: Rule, expected: String): Token {
        if (!check(rule)) raiseSyntaxError("Expected $expected")
        return read()
    }

    fun read(): Token {
        val token = checkNotNull(nextToken)
        position += token.text.length
        nextToken = null
        return token
    }

    fun raiseSyntaxError(message: String, spanStart: Int? = null, spanEnd: Int? = null): Nothing =
        throw ParserSyntaxError(message, source, spanStart ?: position, spanEnd ?: position)

    inline fun <R> enclosingTokens(open: Rule, close: Rule, around: String, body: () -> R): R {
        val openPosition: Int?
        if (check(open)) {
            openPosition = position
            read()
        } else {
            openPosition = null
        }
        val result = body()
        if (openPosition == null) return result
        if (!check(close)) {
            raiseSyntaxError("Expected matching $close for $open, after $around", spanStart = openPosition)
        }
        read()
        return result
    }
}

// Marker AST, mirroring packaging's nested lists: a MarkerList holds atoms and the
// strings "and"/"or"; an atom is a MarkerItem or a nested (parenthesised) MarkerList.

internal sealed class MarkerVar(val value: String) {
    abstract fun serialize(): String
}

internal class Variable(value: String) : MarkerVar(value) {
    override fun serialize(): String = value
}

internal class Value(value: String) : MarkerVar(value) {
    override fun serialize(): String = when {
        '"' !in value -> "\"$value\""
        '\'' !in value -> "'$value'"
        else -> throw IllegalStateException("Cannot serialize marker value containing both quote characters")
    }
}

internal class MarkerItem(val lhs: MarkerVar, val op: String, val rhs: MarkerVar)

internal class MarkerList(val elements: List<Any>)

internal class ParsedRequirement(
    val name: String,
    val url: String,
    val extras: List<String>,
    val specifier: String,
    val marker: MarkerList?,
)

internal object Parser {

    fun parseRequirement(source: String): ParsedRequirement {
        val t = Tokenizer(source)
        t.consume(Rule.WS)
        val name = t.expect(Rule.IDENTIFIER, "package name at the start of dependency specifier").text
        t.consume(Rule.WS)
        val extras = parseExtras(t)
        t.consume(Rule.WS)
        var url = ""
        var specifier = ""
        var marker: MarkerList? = null
        run details@{
            if (t.check(Rule.AT)) {
                t.read()
                t.consume(Rule.WS)
                val urlStart = t.position
                url = t.expect(Rule.URL, "URL after @").text
                if (t.check(Rule.END, peek = true)) return@details
                t.expect(Rule.WS, "whitespace after URL")
                if (t.check(Rule.END, peek = true)) return@details
                marker = parseRequirementMarker(t, urlStart, "semicolon (after URL and whitespace)")
            } else {
                val specifierStart = t.position
                specifier = parseSpecifier(t)
                t.consume(Rule.WS)
                if (t.check(Rule.END, peek = true)) return@details
                val expected = if (specifier.isNotEmpty()) {
                    "comma (within version specifier), semicolon (after version specifier)"
                } else {
                    "semicolon (after name with no version specifier)"
                }
                marker = parseRequirementMarker(t, specifierStart, expected)
            }
        }
        t.expect(Rule.END, "end of dependency specifier")
        return ParsedRequirement(name, url, extras, specifier, marker)
    }

    private fun parseRequirementMarker(t: Tokenizer, spanStart: Int, expected: String): MarkerList {
        if (!t.check(Rule.SEMICOLON)) t.raiseSyntaxError("Expected $expected or end", spanStart = spanStart)
        t.read()
        val marker = parseMarker(t, 0)
        t.consume(Rule.WS)
        return marker
    }

    private fun parseExtras(t: Tokenizer): List<String> {
        if (!t.check(Rule.LEFT_BRACKET, peek = true)) return emptyList()
        return t.enclosingTokens(Rule.LEFT_BRACKET, Rule.RIGHT_BRACKET, "extras") {
            t.consume(Rule.WS)
            val extras = parseExtrasList(t)
            t.consume(Rule.WS)
            extras
        }
    }

    private fun parseExtrasList(t: Tokenizer): List<String> {
        val extras = ArrayList<String>()
        if (!t.check(Rule.IDENTIFIER)) return extras
        extras.add(t.read().text)
        while (true) {
            t.consume(Rule.WS)
            if (t.check(Rule.IDENTIFIER, peek = true)) {
                t.raiseSyntaxError("Expected comma between extra names")
            } else if (!t.check(Rule.COMMA)) {
                break
            }
            t.read()
            t.consume(Rule.WS)
            extras.add(t.expect(Rule.IDENTIFIER, "extra name after comma").text)
        }
        return extras
    }

    private fun parseSpecifier(t: Tokenizer): String =
        t.enclosingTokens(Rule.LEFT_PARENTHESIS, Rule.RIGHT_PARENTHESIS, "version specifier") {
            t.consume(Rule.WS)
            val specs = parseVersionMany(t)
            t.consume(Rule.WS)
            specs
        }

    private fun parseVersionMany(t: Tokenizer): String {
        val sb = StringBuilder()
        while (t.check(Rule.SPECIFIER)) {
            val spanStart = t.position
            val specifier = t.read().text
            sb.append(specifier)
            if (t.check(Rule.VERSION_PREFIX_TRAIL, peek = true)) {
                val message = if (specifier.startsWith("!=") ||
                    (specifier.startsWith("==") && !specifier.startsWith("==="))
                ) {
                    ".* suffix cannot be used with pre-release, post-release, dev or local versions"
                } else {
                    ".* suffix can only be used with `==` or `!=` operators"
                }
                t.raiseSyntaxError(message, spanStart = spanStart, spanEnd = t.position + 1)
            }
            if (t.check(Rule.VERSION_LOCAL_LABEL_TRAIL, peek = true)) {
                t.raiseSyntaxError(
                    "Local version label can only be used with `==` or `!=` operators",
                    spanStart = spanStart,
                    spanEnd = t.position,
                )
            }
            t.consume(Rule.WS)
            if (!t.check(Rule.COMMA)) break
            sb.append(t.read().text)
            t.consume(Rule.WS)
        }
        return sb.toString()
    }

    fun parseFullMarker(source: String): MarkerList {
        val t = Tokenizer(source)
        val result = parseMarker(t, 0)
        t.expect(Rule.END, "end of marker expression")
        return result
    }

    private fun parseMarker(t: Tokenizer, depth: Int): MarkerList {
        val expression = ArrayList<Any>()
        expression.add(parseMarkerAtom(t, depth))
        while (t.check(Rule.BOOLOP)) {
            val token = t.read()
            val right = parseMarkerAtom(t, depth)
            expression.add(token.text)
            expression.add(right)
        }
        return MarkerList(expression)
    }

    private fun parseMarkerAtom(t: Tokenizer, depth: Int): Any {
        t.consume(Rule.WS)
        val marker: Any
        if (t.check(Rule.LEFT_PARENTHESIS, peek = true)) {
            if (depth >= Limits.MAX_MARKER_NESTING) {
                t.raiseSyntaxError("Marker expression nested more than ${Limits.MAX_MARKER_NESTING} levels deep")
            }
            marker = t.enclosingTokens(Rule.LEFT_PARENTHESIS, Rule.RIGHT_PARENTHESIS, "marker expression") {
                t.consume(Rule.WS)
                val inner = parseMarker(t, depth + 1)
                t.consume(Rule.WS)
                inner
            }
        } else {
            marker = parseMarkerItem(t)
        }
        t.consume(Rule.WS)
        return marker
    }

    private fun parseMarkerItem(t: Tokenizer): MarkerItem {
        t.consume(Rule.WS)
        val left = parseMarkerVar(t)
        t.consume(Rule.WS)
        val op = parseMarkerOp(t)
        t.consume(Rule.WS)
        val right = parseMarkerVar(t)
        t.consume(Rule.WS)
        return MarkerItem(left, op, right)
    }

    private fun parseMarkerVar(t: Tokenizer): MarkerVar {
        if (t.check(Rule.VARIABLE)) return processEnvVar(t.read().text.replace('.', '_'))
        if (t.check(Rule.QUOTED_STRING)) {
            val token = t.read()
            val value = try {
                decodePyStringLiteral(token.text)
            } catch (_: PyLiteralException) {
                throw ParserSyntaxError("Invalid quoted string", t.source, token.position, token.position + token.text.length)
            }
            if ('"' in value && '\'' in value) {
                // Python accepts this but cannot turn the marker back into a string; reject it here.
                throw ParserSyntaxError(
                    "Marker value contains both quote characters",
                    t.source,
                    token.position,
                    token.position + token.text.length,
                )
            }
            return Value(value)
        }
        t.raiseSyntaxError("Expected a marker variable or quoted string")
    }

    private fun processEnvVar(name: String): Variable =
        if (name == "platform_python_implementation" || name == "python_implementation") {
            Variable("platform_python_implementation")
        } else {
            Variable(name)
        }

    private fun parseMarkerOp(t: Tokenizer): String {
        if (t.check(Rule.IN)) {
            t.read()
            return "in"
        }
        if (t.check(Rule.NOT)) {
            t.read()
            t.expect(Rule.WS, "whitespace after 'not'")
            t.expect(Rule.IN, "'in' after 'not'")
            return "not in"
        }
        if (t.check(Rule.OP)) return t.read().text
        t.raiseSyntaxError("Expected marker operator, one of <=, <, !=, ==, >=, >, ~=, ===, in, not in")
    }
}
