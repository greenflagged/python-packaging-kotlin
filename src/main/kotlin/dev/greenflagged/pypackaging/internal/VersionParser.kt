package dev.greenflagged.pypackaging.internal

import dev.greenflagged.pypackaging.Limits
import dev.greenflagged.pypackaging.PreRelease
import dev.greenflagged.pypackaging.PreReleaseKind
import dev.greenflagged.pypackaging.Version
import java.math.BigInteger

/**
 * A single-pass parser equivalent to packaging's `VERSION_PATTERN` (the possessive
 * variant used on Python 3.11.5+), matched with `re.VERBOSE | re.IGNORECASE` and
 * surrounded by `\s*`. Every quantifier in that pattern is either possessive or
 * cannot usefully give characters back, so a greedy left-to-right scan accepts
 * exactly the strings the regex fully matches and splits them into the same groups.
 * There is no backtracking, so parsing is linear in the input length.
 */
internal object VersionParser {

    // Order matters: it is the regex alternation order, so `alpha` is tried before `a`.
    private val PRE_LETTERS = arrayOf("alpha", "a", "beta", "b", "preview", "pre", "c", "rc")
    private val POST_LETTERS = arrayOf("post", "rev", "r")

    fun parse(s: String): Version? {
        if (s.length > Limits.MAX_VERSION_LENGTH) return null
        val n = s.length
        var i = skipPyWhitespace(s, 0)
        if (i < n && (s[i] == 'v' || s[i] == 'V')) i++

        // Epoch: digits followed by '!'.
        var epoch = BigInteger.ZERO
        var j = scanDigits(s, i)
        if (j == i) return null
        if (j < n && s[j] == '!') {
            epoch = BigInteger(s.substring(i, j))
            i = j + 1
            j = scanDigits(s, i)
            if (j == i) return null
        }

        // Release: digits ('.' digits)*
        val release = ArrayList<BigInteger>(4)
        release.add(BigInteger(s.substring(i, j)))
        i = j
        while (i + 1 < n && s[i] == '.' && isAsciiDigit(s[i + 1])) {
            j = scanDigits(s, i + 1)
            release.add(BigInteger(s.substring(i + 1, j)))
            i = j
        }

        // Pre-release: [._-]? letter [._-]? digits?
        var pre: PreRelease? = null
        run {
            var k = i
            if (k < n && isSep(s[k])) k++
            val letter = PRE_LETTERS.firstOrNull { matchKeyword(s, k, it, unicode = false) >= 0 } ?: return@run
            k += letter.length
            if (k < n && isSep(s[k])) k++
            val d = scanDigits(s, k)
            val number = if (d > k) BigInteger(s.substring(k, d)) else BigInteger.ZERO
            pre = PreRelease(normalizePre(letter), number)
            i = d
        }

        // Post-release: '-' digits | [._-]? (post|rev|r) [._-]? digits?
        var post: BigInteger? = null
        if (i + 1 < n && s[i] == '-' && isAsciiDigit(s[i + 1])) {
            j = scanDigits(s, i + 1)
            post = BigInteger(s.substring(i + 1, j))
            i = j
        } else {
            run {
                var k = i
                if (k < n && isSep(s[k])) k++
                val letter = POST_LETTERS.firstOrNull { matchKeyword(s, k, it, unicode = false) >= 0 } ?: return@run
                k += letter.length
                if (k < n && isSep(s[k])) k++
                val d = scanDigits(s, k)
                post = if (d > k) BigInteger(s.substring(k, d)) else BigInteger.ZERO
                i = d
            }
        }

        // Dev release: [._-]? dev [._-]? digits?
        var dev: BigInteger? = null
        run {
            var k = i
            if (k < n && isSep(s[k])) k++
            if (matchKeyword(s, k, "dev", unicode = false) < 0) return@run
            k += 3
            if (k < n && isSep(s[k])) k++
            val d = scanDigits(s, k)
            dev = if (d > k) BigInteger(s.substring(k, d)) else BigInteger.ZERO
            i = d
        }

        // Local: '+' alnum+ ([._-] alnum+)*
        var local: List<Any>? = null
        if (i + 1 < n && s[i] == '+' && isAsciiAlnumIgnoreCase(s[i + 1])) {
            val end = scanLocal(s, i + 1)
            local = splitLocal(s.substring(i + 1, end))
            i = end
        }

        i = skipPyWhitespace(s, i)
        if (i != n) return null
        return Version(epoch, release, pre, post, dev, local)
    }

    /** Parses a bare local label (`ubuntu-1`), as `Version.from_parts(local=...)` validates it. */
    fun parseLocalLabel(label: String): List<Any>? {
        if (label.isEmpty() || label.length > Limits.MAX_VERSION_LENGTH || !isAsciiAlnumIgnoreCase(label[0])) return null
        if (scanLocal(label, 0) != label.length) return null
        return splitLocal(label)
    }

    // alnum+ ([._-] alnum+)*, starting at an alnum. Returns the end index.
    private fun scanLocal(s: String, from: Int): Int {
        var i = from
        while (i < s.length && isAsciiAlnumIgnoreCase(s[i])) i++
        while (i + 1 < s.length && isSep(s[i]) && isAsciiAlnumIgnoreCase(s[i + 1])) {
            i++
            while (i < s.length && isAsciiAlnumIgnoreCase(s[i])) i++
        }
        return i
    }

    private fun splitLocal(label: String): List<Any> {
        val parts = ArrayList<Any>()
        var start = 0
        for (k in 0..label.length) {
            if (k == label.length || isSep(label[k])) {
                val part = label.substring(start, k)
                parts.add(if (part.all(::isAsciiDigit)) BigInteger(part) else pyLower(part))
                start = k + 1
            }
        }
        return parts
    }

    fun normalizePre(letter: String): PreReleaseKind = when (letter.lowercase()) {
        "a", "alpha" -> PreReleaseKind.ALPHA
        "b", "beta" -> PreReleaseKind.BETA
        else -> PreReleaseKind.RC // c, rc, pre, preview
    }

    private fun isSep(c: Char): Boolean = c == '.' || c == '_' || c == '-'

    private fun scanDigits(s: String, from: Int): Int {
        var i = from
        while (i < s.length && isAsciiDigit(s[i])) i++
        return i
    }
}

/**
 * Equivalent to packaging's `Specifier._specifier_regex_str` (compiled with
 * `re.VERBOSE | re.IGNORECASE`). [scan] returns the end of the match starting at
 * `pos`, or -1. It returns what `re.match` would: for this pattern the first match
 * in backtracking order is the greedy one, and whenever any path reaches a given
 * end of input the greedy path does too, so the same scan serves both
 * `Specifier(...)`'s full match and the requirement tokenizer's prefix match.
 */
internal object SpecifierScanner {

    private val PRE_LETTERS = arrayOf("alpha", "beta", "preview", "pre", "a", "b", "c", "rc")
    private val POST_LETTERS = arrayOf("post", "rev", "r")

    fun scan(s: String, pos: Int): Int {
        val n = s.length
        if (s.startsWith("===", pos)) {
            var k = skipPyWhitespace(s, pos + 3)
            while (k < n && !isPyWhitespace(s[k]) && s[k] != ';' && s[k] != ')') k++
            return k
        }
        return when {
            s.startsWith("==", pos) || s.startsWith("!=", pos) -> scanVersion(s, pos + 2, Branch.EQUALITY)
            s.startsWith("~=", pos) -> scanVersion(s, pos + 2, Branch.COMPATIBLE)
            s.startsWith("<=", pos) || s.startsWith(">=", pos) -> scanVersion(s, pos + 2, Branch.ORDERED)
            pos < n && (s[pos] == '<' || s[pos] == '>') -> scanVersion(s, pos + 1, Branch.ORDERED)
            else -> -1
        }
    }

    private enum class Branch { EQUALITY, COMPATIBLE, ORDERED }

    private fun scanVersion(s: String, from: Int, branch: Branch): Int {
        val n = s.length
        // In the ~= branch the pre/post/dev groups are not inside (?a:...), so Python's
        // Unicode case folding applies to their letters.
        val unicode = branch == Branch.COMPATIBLE
        var i = skipPyWhitespace(s, from)
        if (i < n && (s[i] == 'v' || s[i] == 'V')) i++
        var j = scanDigits(s, i)
        if (j == i) return -1
        if (j < n && s[j] == '!') {
            i = j + 1
            j = scanDigits(s, i)
            if (j == i) return -1
        }
        i = j
        var components = 1
        while (i + 1 < n && s[i] == '.' && isAsciiDigit(s[i + 1])) {
            i = scanDigits(s, i + 1)
            components++
        }
        if (branch == Branch.COMPATIBLE && components < 2) return -1

        if (branch == Branch.EQUALITY && i + 1 < n && s[i] == '.' && s[i + 1] == '*') return i + 2

        // pre: [-_.]? letter [-_.]? [0-9]*
        run {
            var k = i
            if (k < n && isSep(s[k])) k++
            val letter = PRE_LETTERS.firstOrNull { matchKeyword(s, k, it, unicode) >= 0 } ?: return@run
            k += letter.length
            if (k < n && isSep(s[k])) k++
            i = scanDigits(s, k)
        }
        // post: -[0-9]+ | [-_.]? (post|rev|r) [-_.]? [0-9]*
        if (i + 1 < n && s[i] == '-' && isAsciiDigit(s[i + 1])) {
            i = scanDigits(s, i + 1)
        } else {
            run {
                var k = i
                if (k < n && isSep(s[k])) k++
                val letter = POST_LETTERS.firstOrNull { matchKeyword(s, k, it, unicode) >= 0 } ?: return@run
                k += letter.length
                if (k < n && isSep(s[k])) k++
                i = scanDigits(s, k)
            }
        }
        // dev: [-_.]? dev [-_.]? [0-9]*
        run {
            var k = i
            if (k < n && isSep(s[k])) k++
            if (matchKeyword(s, k, "dev", unicode) < 0) return@run
            k += 3
            if (k < n && isSep(s[k])) k++
            i = scanDigits(s, k)
        }
        // local (== and != only): \+[a-z0-9]+(?:[-_.][a-z0-9]+)*
        if (branch == Branch.EQUALITY && i + 1 < n && s[i] == '+' && isAsciiAlnumIgnoreCase(s[i + 1])) {
            i += 1
            while (i < n && isAsciiAlnumIgnoreCase(s[i])) i++
            while (i + 1 < n && isSep(s[i]) && isAsciiAlnumIgnoreCase(s[i + 1])) {
                i++
                while (i < n && isAsciiAlnumIgnoreCase(s[i])) i++
            }
        }
        return i
    }

    private fun isSep(c: Char): Boolean = c == '.' || c == '_' || c == '-'

    private fun scanDigits(s: String, from: Int): Int {
        var i = from
        while (i < s.length && isAsciiDigit(s[i])) i++
        return i
    }
}
