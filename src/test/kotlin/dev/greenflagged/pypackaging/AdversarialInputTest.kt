package dev.greenflagged.pypackaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively

/**
 * Untrusted metadata must not be able to make parsing slow or crash the JVM. These are
 * the worst shapes for the classic failure modes: regex backtracking (ReDoS), deep
 * recursion, huge numbers and combinatorial expansion. Every parser here is a linear
 * scanner, so each case finishes in milliseconds; the timeouts only catch regressions.
 */
class AdversarialInputTest {

    private fun fast(body: () -> Unit) = assertTimeoutPreemptively(Duration.ofSeconds(2), body)

    @Test
    fun overLongInputsAreRejectedBeforeParsing() {
        assertThrows<InvalidVersionException> { Version.parse("1".repeat(Limits.MAX_VERSION_LENGTH + 1)) }
        assertThrows<InvalidSpecifierException> { SpecifierSet.parse(">=1," .repeat(Limits.MAX_SPECIFIER_LENGTH)) }
        assertThrows<InvalidMarkerException> { Marker.parse("x".repeat(Limits.MAX_MARKER_LENGTH + 1)) }
        assertThrows<InvalidRequirementException> { Requirement.parse("a".repeat(Limits.MAX_REQUIREMENT_LENGTH + 1)) }
        assertThrows<InvalidWheelFilenameException> { Filenames.parseWheel("a".repeat(Limits.MAX_FILENAME_LENGTH) + ".whl") }
    }

    @Test
    fun versionsAtTheLengthLimit() = fast {
        val n = Limits.MAX_VERSION_LENGTH
        // Classic backtracking traps for the PEP 440 regex: long separator/letter runs that almost match.
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
        val big = Version.parse("9".repeat(n))
        assertTrue(big > Version.parse("9".repeat(n - 1)))
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
        SpecifierSet.parse("===" + "a".repeat(n - 3)).contains("a")
        Specifier.isValid("==" + "1.".repeat(n / 2 - 2) + "x")
        Specifier.isValid("~=" + "1" + "a-".repeat(n / 2 - 2))
        SpecifierSet.isValid(",".repeat(n))
    }

    @Test
    fun markersAtTheLengthLimit() = fast {
        val n = Limits.MAX_MARKER_LENGTH
        val clause = "os_name == \"posix\" and "
        val long = clause.repeat((n - 20) / clause.length) + "os_name == \"nt\""
        val env = MarkerEnvironment.builder().osName("posix").build()
        assertFalse(Marker.parse(long).evaluate(env))
        // Deepest allowed nesting parses; one more level is rejected rather than overflowing the stack.
        val depth = Limits.MAX_MARKER_NESTING
        assertTrue(Marker.parse("(".repeat(depth) + "os_name == 'posix'" + ")".repeat(depth)).evaluate(env))
        val e = assertThrows<InvalidMarkerException> {
            Marker.parse("(".repeat(depth + 1) + "os_name == 'posix'" + ")".repeat(depth + 1))
        }
        assertTrue(e.message!!.contains("nested more than"))
        // Unclosed parentheses and unterminated strings at full length.
        Marker.isValid("(".repeat(n))
        Marker.isValid("os_name == '" + "a".repeat(n - 20))
        Marker.isValid("os_name == '" + "\\x".repeat((n - 20) / 2) + "'")
    }

    @Test
    fun requirementsAtTheLengthLimit() = fast {
        val n = Limits.MAX_REQUIREMENT_LENGTH
        // Identifier backtracking: a long name ending in a separator.
        Requirement.isValid("a" + "-".repeat(n - 2) + "_")
        Requirement.isValid("a" + ".a".repeat((n - 1) / 2))
        Requirement.isValid("name[" + "a,".repeat((n - 10) / 2) + "]")
        Requirement.isValid("name @ " + "h".repeat(n - 10))
        Requirement.isValid("name" + ">=1,".repeat((n - 8) / 4))
        val req = Requirement.parse("name @ https://example.com/" + "p".repeat(n - 60) + " ; os_name == 'nt'")
        assertEquals("name", req.name)
    }

    @Test
    fun wheelTagExpansionIsBounded() = fast {
        val part = (0 until 40).joinToString(".") { "p$it" }
        val e = assertThrows<InvalidWheelFilenameException> { Filenames.parseWheel("pkg-1.0-$part-$part-$part.whl") }
        assertTrue(e.message!!.contains("too many tags"))
    }
}
