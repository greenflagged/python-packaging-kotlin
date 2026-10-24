/*
 * Ported from pypa/packaging 26.3, tests/test_utils.py
 * https://github.com/pypa/packaging/blob/26.3/tests/test_utils.py
 *
 * Test vectors and test logic: Copyright (c) Donald Stufft and individual contributors.
 * Licensed under Apache-2.0 OR BSD-2-Clause. See NOTICE.
 *
 * Every upstream test function is ported; none are out of scope.
 */
package dev.greenflagged.pypackaging.conformance

import dev.greenflagged.pypackaging.BuildTag
import dev.greenflagged.pypackaging.Filenames
import dev.greenflagged.pypackaging.InvalidNameException
import dev.greenflagged.pypackaging.InvalidSdistFilenameException
import dev.greenflagged.pypackaging.InvalidWheelFilenameException
import dev.greenflagged.pypackaging.Names
import dev.greenflagged.pypackaging.SdistFilename
import dev.greenflagged.pypackaging.Tag
import dev.greenflagged.pypackaging.Version
import dev.greenflagged.pypackaging.WheelFilename
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class UtilsConformanceTest {
    private fun dyn(test: String, body: (Case) -> Unit): List<DynamicTest> = PypaFixtures.dynamic("test_utils", test, body)

    @TestFactory
    fun canonicalizeName() = dyn("test_canonicalize_name") {
        assertEquals(it.str("expected"), Names.canonicalize(it.str("name")))
    }

    @TestFactory
    fun canonicalizeNameInvalid() = dyn("test_canonicalize_name_invalid") {
        assertThrows<InvalidNameException> { Names.canonicalize(it.str("name"), validate = true) }
        assertEquals(it.str("expected"), Names.canonicalize(it.str("name")))
    }

    @TestFactory
    fun isNormalizedName() = dyn("test_is_normalized_name") {
        val name = it.str("name")
        val expected = it.str("expected")
        assertTrue(Names.isNormalized(expected))
        if (name != expected) assertFalse(Names.isNormalized(name))
    }

    @TestFactory
    fun canonicalizeVersion() = dyn("test_canonicalize_version") {
        val actual = when (val v = it["version"]) {
            is PyObject -> Version.canonicalize(Version.parse(v.str)) // upstream passes Version("1.4.0")
            else -> Version.canonicalize(v as String)
        }
        assertEquals(it.str("expected"), actual)
    }

    @TestFactory
    fun canonicalizeVersionNoStripTrailingZero() = dyn("test_canonicalize_version_no_strip_trailing_zero") {
        assertEquals(it.str("version"), Version.canonicalize(it.str("version"), stripTrailingZero = false))
    }

    @TestFactory
    fun parseWheelFilename() = dyn("test_parse_wheel_filename") {
        val build = it.list("build")
        val buildTag = if (build.isEmpty()) null else BuildTag(bigOf(build[0]), build[1] as String)
        // Tags were harvested as their repr, e.g. "<py3-none-any @ 4595670784>".
        val tags = (it["tags"] as Set<*>).map { t ->
            val m = Regex("""^<([^-]+)-([^-]+)-([^ ]+) @ \d+>$""").matchEntire((t as PyRepr).repr)
                ?: error("unexpected tag repr $t")
            Tag(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }.toSet()
        val expected = WheelFilename(it.str("name"), Version.parse((it["version"] as PyObject).str), buildTag, tags)
        assertEquals(expected, Filenames.parseWheel(it.str("filename")))
    }

    @TestFactory
    fun parseWheelInvalidFilename() = dyn("test_parse_wheel_invalid_filename") {
        assertThrows<InvalidWheelFilenameException> { Filenames.parseWheel(it.str("filename")) }
    }

    @TestFactory
    fun parseWheelUnsortedTagsValidByDefault() = dyn("test_parse_wheel_unsorted_tags_valid_by_default") {
        Filenames.parseWheel(it.str("filename"))
    }

    @TestFactory
    fun parseWheelUnsortedTagsInvalidWithValidate() = dyn("test_parse_wheel_unsorted_tags_invalid_with_validate") {
        assertThrows<InvalidWheelFilenameException> { Filenames.parseWheel(it.str("filename"), validateOrder = true) }
    }

    @TestFactory
    fun parseSdistFilename() = dyn("test_parse_sdist_filename") {
        val expected = SdistFilename(it.str("name"), Version.parse((it["version"] as PyObject).str))
        assertEquals(expected, Filenames.parseSdist(it.str("filename")))
    }

    @TestFactory
    fun parseSdistInvalidFilename() = dyn("test_parse_sdist_invalid_filename") {
        assertThrows<InvalidSdistFilenameException> { Filenames.parseSdist(it.str("filename")) }
    }
}
