package dev.greenflagged.pypackaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Untrusted metadata must not be able to make parsing slow, crash the JVM, or quietly
 * change a result. These are the worst shapes for the classic failure modes: regex
 * backtracking (ReDoS), deep recursion, huge numbers and combinatorial expansion, and
 * inputs just past each limit. Every parser is a linear scanner, so each case
 * finishes in milliseconds; the timeouts only catch regressions.
 */
class AdversarialInputTest {

    private fun fast(body: () -> Unit) = assertTimeoutPreemptively(Duration.ofSeconds(5), body)

    private inline fun <reified T : Throwable> throws(noinline body: () -> Unit): T = assertThrows(T::class.java, body)

    private val env = MarkerEnvironment.builder()
        .pythonVersion("3.12").pythonFullVersion("3.12.4").implementationName("cpython")
        .implementationVersion("3.12.4").platformPythonImplementation("CPython").osName("posix")
        .sysPlatform("linux").platformSystem("Linux").platformMachine("x86_64")
        .platformRelease("6.8.0").platformVersion("")
        .build()

    @Test
    fun overLimitInputsThrowLimitExceeded() {
        throws<LimitExceededException> { Version.parse("1".repeat(Limits.MAX_VERSION_LENGTH + 1)) }
        throws<LimitExceededException> { Version.parseOrNull("1".repeat(Limits.MAX_VERSION_LENGTH + 1)) }
        throws<LimitExceededException> { Version.isValid("1".repeat(Limits.MAX_VERSION_LENGTH + 1)) }
        throws<LimitExceededException> { SpecifierSet.parse(">=1,".repeat(Limits.MAX_SPECIFIER_LENGTH)) }
        throws<LimitExceededException> { Specifier.parse("==" + "1".repeat(Limits.MAX_VERSION_LENGTH + 1)) }
        throws<LimitExceededException> { Marker.parse("x".repeat(Limits.MAX_MARKER_LENGTH + 1)) }
        throws<LimitExceededException> { Requirement.parse("a".repeat(Limits.MAX_REQUIREMENT_LENGTH + 1)) }
        throws<LimitExceededException> { Filenames.parseWheel("a".repeat(Limits.MAX_FILENAME_LENGTH) + ".whl") }
        throws<LimitExceededException> { Filenames.parseSdist("a".repeat(Limits.MAX_FILENAME_LENGTH) + ".zip") }
        // The limit is a PackagingException but not an "invalid input" exception.
        val e = throws<PackagingException> { Version.parse("1".repeat(Limits.MAX_VERSION_LENGTH + 1)) }
        assertFalse(e is InvalidVersionException)
    }

    @Test
    fun overLimitValuesNeverSilentlyChangeAResult() {
        // Python evaluates these as version comparisons (true). A size limit must not turn
        // them into string comparisons (false), or a dependency would escape vetting.
        val longVersion = "0".repeat(1100) + "1"
        throws<LimitExceededException> { Marker.parse("python_version >= \"$longVersion\"").evaluate(env) }
        throws<LimitExceededException> { Marker.parse("python_version < \"9${"0".repeat(1100)}\"").evaluate(env) }
        throws<LimitExceededException> { Marker.parse("python_version ~= \"$longVersion.1\"").evaluate(env) }
        // A long candidate version is not silently dropped from contains/filter.
        val set = SpecifierSet.parse(">=1")
        throws<LimitExceededException> { set.contains("9".repeat(1100)) }
        throws<LimitExceededException> { set.filter(listOf("1.0", "9".repeat(1100))) }
        throws<LimitExceededException> { Specifier.parse(">=1").contains("9".repeat(1100)) }
        throws<LimitExceededException> { SpecifierSet.EMPTY.filter(listOf("9".repeat(1100))) }
        // Long non-version strings still compare as strings, exactly as in Python.
        assertFalse(Marker.parse("python_version == \"${"x".repeat(2000)}\"").evaluate(env))
    }

    @Test
    fun versionsAtTheLengthLimit() = fast {
        val n = Limits.MAX_VERSION_LENGTH
        // Classic backtracking traps for the PEP 440 regex: long runs that almost match.
        val traps = listOf(
            "1".repeat(n),
            "1.".repeat(n / 2 - 1) + "1",
            "1" + ".0".repeat((n - 1) / 2),
            "1" + "a".repeat(n - 1),
            "1" + ".a".repeat((n - 1) / 2),
            "1" + "-".repeat(n - 2) + "x",
            "1.0+" + "a.".repeat((n - 5) / 2) + "!",
            "1.0+" + "a".repeat(n - 5) + ".",
            " ".repeat(n - 2) + "1x",
            "1!" + "1.".repeat((n - 3) / 2),
            "1.0" + "post".repeat((n - 3) / 4),
            "1.0" + ".dev".repeat((n - 3) / 4),
        )
        for (t in traps) {
            assertTrue(t.length <= n, "trap longer than the limit")
            Version.parseOrNull(t)
            Version.canonicalize(t)
        }
        // Very large numbers compare correctly and quickly.
        assertTrue(Version.parse("9".repeat(n)) > Version.parse("9".repeat(n - 1)))
    }

    @Test
    fun specifiersAtTheLengthLimit() = fast {
        val n = Limits.MAX_SPECIFIER_LENGTH
        val many = (">=1.0," + "!=1.1.*,").repeat(n / 16).dropLast(1)
        assertTrue(many.length <= n)
        val set = SpecifierSet.parse(many)
        assertTrue(set.contains("1.2"))
        assertFalse(set.isUnsatisfiable())
        set.filter(List(200) { "1.$it" })
        // Thousands of != specifiers: quadratic intersection, still well under a second.
        val exclusions = (1..2000).joinToString(",") { "!=$it" }
        assertTrue(exclusions.length <= n)
        val excluded = SpecifierSet.parse(exclusions)
        assertFalse(excluded.isUnsatisfiable())
        excluded.filter(List(1000) { "$it.5" })
        // An arbitrary-equality string is also read as a version (for pre-release detection),
        // so the version limit applies to it.
        assertTrue(SpecifierSet.parse("===" + "a".repeat(Limits.MAX_VERSION_LENGTH)).contains("a".repeat(Limits.MAX_VERSION_LENGTH)))
        throws<LimitExceededException> { SpecifierSet.parse("===" + "a".repeat(n - 3)) }
        assertFalse(Specifier.isValid("==" + "1.".repeat(n / 2 - 2) + "x"))
        assertFalse(Specifier.isValid("~=1" + "a-".repeat(n / 2 - 2)))
        assertTrue(SpecifierSet.parse(",".repeat(n)).isEmpty())
    }

    @Test
    fun markersAtTheLengthLimit() = fast {
        val n = Limits.MAX_MARKER_LENGTH
        val clause = "os_name == \"posix\" and "
        val long = clause.repeat((n - 20) / clause.length) + "os_name == \"nt\""
        assertFalse(Marker.parse(long).evaluate(env))
        // The deepest allowed nesting parses; one more level is rejected rather than overflowing the stack.
        val depth = Limits.MAX_MARKER_NESTING
        assertTrue(Marker.parse("(".repeat(depth) + "os_name == 'posix'" + ")".repeat(depth)).evaluate(env))
        throws<LimitExceededException> {
            Marker.parse("(".repeat(depth + 1) + "os_name == 'posix'" + ")".repeat(depth + 1))
        }
        // Unclosed parentheses and unterminated strings at full length.
        assertFalse(Marker.isValid("(".repeat(depth) + "x"))
        assertFalse(Marker.isValid("os_name == '" + "a".repeat(n - 20)))
        assertFalse(Marker.isValid("os_name == '" + "\\x".repeat((n - 20) / 2) + "'"))
    }

    @Test
    fun deeplyComposedMarkersDoNotOverflowTheStack() = fast {
        // Folding many dependency markers together nests one level per call.
        val leaf = Marker.parse("os_name == 'nt'")
        var left = leaf
        var right = leaf
        repeat(50_000) {
            left = left.or(leaf)
            right = leaf.and(right)
        }
        assertFalse(left.evaluate(env))
        assertFalse(right.evaluate(env))
        // Left-folded chains format with nested parentheses, as Python's do.
        assertTrue(left.toString().endsWith(") or os_name == \"nt\""))
        assertTrue(right.toString().startsWith("os_name == \"nt\" and (os_name"))
        assertEquals(left, left)
        left.hashCode()
        assertFalse(left == right)
    }

    @Test
    fun selfComposedMarkersAreBounded() = fast {
        // m.and(m) doubles the expression each time; without a cap, 40 doublings never finish.
        var m = Marker.parse("os_name == 'posix'")
        val e = throws<LimitExceededException> { repeat(40) { m = m.and(m) } }
        assertTrue(e.message!!.contains("comparisons"))
        assertTrue(m.evaluate(env))
    }

    @Test
    fun requirementsAtTheLengthLimit() = fast {
        val n = Limits.MAX_REQUIREMENT_LENGTH
        // Identifier backtracking: a long name ending in a separator.
        assertTrue(Requirement.isValid("a" + "-".repeat(n - 2) + "_")) // Python accepts this too
        assertTrue(Requirement.isValid("a" + ".a".repeat((n - 1) / 2)))
        assertFalse(Requirement.isValid("name[" + "a,".repeat((n - 10) / 2) + "]"))
        assertTrue(Requirement.isValid("name @ " + "h".repeat(n - 10)))
        // A long specifier list inside a requirement hits the specifier limit.
        throws<LimitExceededException> { Requirement.parse("name" + ">=1,".repeat((n - 8) / 4)) }
        val req = Requirement.parse("name @ https://example.com/" + "p".repeat(n - 60) + " ; os_name == 'nt'")
        assertEquals("name", req.name)
    }

    @Test
    fun wheelTagExpansionIsBounded() = fast {
        val part = (0 until 40).joinToString(".") { "p$it" }
        val e = throws<InvalidWheelFilenameException> { Filenames.parseWheel("pkg-1.0-$part-$part-$part.whl") }
        assertTrue(e.message!!.contains("too many tags"))
    }

    @Test
    fun valueTypesCannotBeMutatedThroughTheirCollections() {
        val v = Version.parse("1.2.3")
        throws<UnsupportedOperationException> { (v.release as MutableList<java.math.BigInteger>).clear() }
        val env = MarkerEnvironment.builder().osName("posix").extras(setOf("a")).build()
        throws<UnsupportedOperationException> { (env.values as MutableMap<String, String>).clear() }
        throws<UnsupportedOperationException> { (env.setValues["extras"] as MutableSet<String>).clear() }
        val it = SpecifierSet.parse(">=1,<2").iterator()
        it.next()
        throws<UnsupportedOperationException> { (it as MutableIterator<Specifier>).remove() }
    }

    @Test
    fun onlyPackagingExceptionsEscape() {
        // Combining sets with conflicting pre-release overrides is a PackagingException, not a bare IAE.
        throws<PackagingException> { SpecifierSet.parse(">=1", true).and(SpecifierSet.parse("<2", false)) }
        // Non-ASCII interpreter tags are rejected rather than judged by the JVM's Unicode tables.
        throws<InvalidWheelFilenameException> { Filenames.parseWheel("pkg-1.0-pý3-none-any.whl") }
    }
}
