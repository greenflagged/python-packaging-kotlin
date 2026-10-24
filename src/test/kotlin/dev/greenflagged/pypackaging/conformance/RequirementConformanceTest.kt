/*
 * Ported from pypa/packaging 26.3, tests/test_requirements.py
 * https://github.com/pypa/packaging/blob/26.3/tests/test_requirements.py
 *
 * Test vectors and test logic: Copyright (c) Donald Stufft and individual contributors.
 * Licensed under Apache-2.0 OR BSD-2-Clause. See NOTICE.
 *
 * Python's `ctx.exconly()` is "packaging.requirements.InvalidRequirement: <message>"; the
 * Kotlin exception message is asserted to equal <message>.
 *
 * Not ported:
 *   test_requirement_slots — Python-only (__slots__ / __dict__).
 *   test_pickle_requirement_roundtrip — pickling.
 *   test_pickle_requirement_preserves_prereleases — pickling.
 *   test_pickle_requirement_setstate_rejects_invalid_state — pickling (__setstate__).
 *   test_pickle_requirement_setstate_rejects_invalid_string — pickling (__setstate__).
 *   test_pickle_requirement_old_format_preserves_prereleases — pickling.
 *   test_pickle_requirement_26_2_format_loads — pickling.
 *   test_pickle_requirement_new_format_preserves_prereleases — pickling.
 *   test_pickle_requirement_old_format_loads — pickling.
 *   test_pickle_requirement_26_0_format_loads — pickling.
 *   test_pickle_requirement_25_0_format_loads — pickling.
 * Partially ported:
 *   TestRequirementBehaviour.test_str_and_repr — the repr() assertion is out of scope; str() is ported.
 *   TestRequirementBehaviour.test_types_* — isinstance checks are enforced by Kotlin's static
 *     types; the None/not-None assertions are ported.
 */
package dev.greenflagged.pypackaging.conformance

import dev.greenflagged.pypackaging.InvalidRequirementException
import dev.greenflagged.pypackaging.Marker
import dev.greenflagged.pypackaging.MarkerEnvironment
import dev.greenflagged.pypackaging.Requirement
import dev.greenflagged.pypackaging.SpecifierSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class RequirementConformanceTest {
    private fun dyn(test: String, body: (Case) -> Unit): List<DynamicTest> = PypaFixtures.dynamic("test_requirements", test, body)

    /** Asserts that parsing [toParse] fails with exactly [message] (exconly() minus the class prefix). */
    private fun assertInvalid(toParse: String, message: String) {
        val e = assertThrows<InvalidRequirementException> { Requirement.parse(toParse) }
        assertEquals(message, e.message)
    }

    private fun pyFormat(template: String, ws: String): String = template.replace("{ws}", ws)

    // test_basic_valid_requirement_parsing: ~5k cases, run as one test that collects failures.
    @Test
    fun basicValidRequirementParsing() {
        val cases = PypaFixtures.cases("test_requirements", "test_basic_valid_requirement_parsing")
        val failures = mutableListOf<String>()
        for (case in cases) {
            val name = case.str("name")
            @Suppress("UNCHECKED_CAST")
            val extras = case["extras"] as Set<String>
            val specifier = case.str("specifier")
            val url = case.strOrNull("url")
            val marker = case.strOrNull("marker")
            val ws = case.str("whitespace")

            val parts = mutableListOf(name)
            if (extras.isNotEmpty()) {
                parts.add("[")
                parts.add(extras.sorted().joinToString(pyFormat("{ws},{ws}", ws)))
                parts.add("]")
            }
            if (specifier.isNotEmpty()) parts.add(pyFormat(specifier, ws))
            if (url != null) {
                parts.add("@")
                parts.add(pyFormat(url, ws))
            }
            if (marker != null) {
                parts.add(if (url != null) " ;" else ";")
                parts.add(pyFormat(marker, ws))
            }
            val toParse = parts.joinToString(ws)

            try {
                val req = Requirement.parse(toParse)
                assertEquals(name, req.name, "name")
                assertEquals(extras, req.extras, "extras")
                assertEquals(url, req.url, "url")
                assertEquals(SpecifierSet.parse(pyFormat(specifier, "").trim('(', ')')), req.specifier, "specifier")
                assertEquals(if (!marker.isNullOrEmpty()) Marker.parse(pyFormat(marker, "")) else null, req.marker, "marker")
            } catch (t: Throwable) {
                failures.add("'${toParse.replace("\t", "\\t")}': $t")
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size}/${cases.size} cases failed:\n" + failures.take(50).joinToString("\n"))
    }

    @TestFactory
    fun normalizedRequirements() = dyn("test_normalized_requirements") {
        assertEquals(it.str("norm_req"), Requirement.parse(it.str("input_req")).toString())
    }

    @Test
    fun requirementMarkerWithEmbeddedDoubleQuoteRoundTrips() {
        val reqString = "demo; 'a\" == os_name or python_version >= \"0\" or \"b' == os_name"
        val req = Requirement.parse(reqString)

        assertEquals(reqString, req.toString())
        assertNotNull(req.marker)
        // Upstream evaluates against default_environment(); use a fixed CPython 3.12 Linux one.
        assertFalse(req.marker!!.evaluate(DEFAULT_ENV))
        val roundTripped = Requirement.parse(req.toString()).marker
        assertNotNull(roundTripped)
        assertFalse(roundTripped!!.evaluate(DEFAULT_ENV))
    }

    // ---- TestRequirementParsing ----

    @TestFactory
    fun validMarker() = dyn("TestRequirementParsing.test_valid_marker") {
        Requirement.parse("name; ${it.str("marker")}")
    }

    @TestFactory
    fun fileUrl() = dyn("TestRequirementParsing.test_file_url") {
        val url = it.str("url")
        assertEquals(url, Requirement.parse("name @ $url").url)
    }

    @TestFactory
    fun errorWhenSuffixedWithLineBreak() = dyn("TestRequirementParsing.test_error_when_suffixed_with_line_break") {
        assertThrows<InvalidRequirementException> { Requirement.parse(it.str("requirement") + it.str("line_break")) }
    }

    @TestFactory
    fun trailingHorizontalWhitespace() = dyn("TestRequirementParsing.test_trailing_horizontal_whitespace") {
        assertEquals(Requirement.parse("name>=1"), Requirement.parse("name>=1" + it.str("whitespace")))
    }

    @Test
    fun emptyExtras() {
        val req = Requirement.parse("name[]")
        assertEquals("name", req.name)
        assertEquals(emptySet<String>(), req.extras)
    }

    @Test
    fun emptySpecifier() {
        val req = Requirement.parse("name()")
        assertEquals("name", req.name)
        assertEquals(SpecifierSet.parse(""), req.specifier)
    }

    @Test
    fun errorWhenSpecifierSetRejectsParsedSpecifier() {
        val e = assertThrows<InvalidRequirementException> { Requirement.parse("demo===x,y") }
        // pytest.raises(match=...) is a regex search.
        assertTrue(e.message!!.contains("Invalid specifier: 'y'"), e.message)
    }

    @Test
    fun errorWhenEmptyString() = assertInvalid(
        "",
        "Expected package name at the start of dependency specifier\n" +
            "    \n" +
            "    ^",
    )

    @Test
    fun errorNoName() = assertInvalid(
        "==0.0",
        "Expected package name at the start of dependency specifier\n" +
            "    ==0.0\n" +
            "    ^",
    )

    @Test
    fun errorWhenMissingCommaInExtras() = assertInvalid(
        "name[bar baz]",
        "Expected comma between extra names\n" +
            "    name[bar baz]\n" +
            "             ^",
    )

    @Test
    fun errorWhenTrailingCommaInExtras() = assertInvalid(
        "name[bar, baz,]",
        "Expected extra name after comma\n" +
            "    name[bar, baz,]\n" +
            "                  ^",
    )

    @Test
    fun errorWhenParensNotClosedCorrectly() = assertInvalid(
        "name (>= 1.0",
        "Expected matching RIGHT_PARENTHESIS for LEFT_PARENTHESIS, " +
            "after version specifier\n" +
            "    name (>= 1.0\n" +
            "         ~~~~~~~^",
    )

    @Test
    fun errorWhenPrefixMatchIsUsedIncorrectly() = assertInvalid(
        "black (>=20.*) ; extra == 'format'",
        ".* suffix can only be used with `==` or `!=` operators\n" +
            "    black (>=20.*) ; extra == 'format'\n" +
            "           ~~~~~^",
    )

    @TestFactory
    fun errorWhenPrefixMatchUsesPostRelease() = dyn("TestRequirementParsing.test_error_when_prefix_match_uses_post_release") {
        val operator = it.str("operator")
        val opTilde = "~".repeat(operator.length)
        assertInvalid(
            "name $operator 1.2.3.post4.*",
            ".* suffix cannot be used with pre-release, post-release, " +
                "dev or local versions\n" +
                "    name $operator 1.2.3.post4.*\n" +
                "         $opTilde~~~~~~~~~~~~~^",
        )
    }

    @Test
    fun errorWhenPrefixMatchUsesPostReleaseWithoutSpaces() = assertInvalid(
        "name==1.2.3.post4.*",
        ".* suffix cannot be used with pre-release, post-release, " +
            "dev or local versions\n" +
            "    name==1.2.3.post4.*\n" +
            "        ~~~~~~~~~~~~~~^",
    )

    @TestFactory
    fun errorWhenLocalVersionLabelIsUsedIncorrectly() =
        dyn("TestRequirementParsing.test_error_when_local_version_label_is_used_incorrectly") {
            val operator = it.str("operator")
            val opTilde = "~".repeat(operator.length)
            assertInvalid(
                "name $operator 1.0+local.version.label",
                "Local version label can only be used with `==` or `!=` operators\n" +
                    "    name $operator 1.0+local.version.label\n" +
                    "         $opTilde~~~~^",
            )
        }

    @Test
    fun errorWhenBracketNotClosedCorrectly() = assertInvalid(
        "name[bar, baz >= 1.0",
        "Expected matching RIGHT_BRACKET for LEFT_BRACKET, " +
            "after extras\n" +
            "    name[bar, baz >= 1.0\n" +
            "        ~~~~~~~~~~^",
    )

    @Test
    fun errorWhenExtrasBracketLeftUnclosed() = assertInvalid(
        "name[bar, baz",
        "Expected matching RIGHT_BRACKET for LEFT_BRACKET, " +
            "after extras\n" +
            "    name[bar, baz\n" +
            "        ~~~~~~~~~^",
    )

    @Test
    fun errorNoSpaceAfterUrl() = assertInvalid(
        "name @ https://example.com/; extra == 'example'",
        "Expected semicolon (after URL and whitespace) or end\n" +
            "    name @ https://example.com/; extra == 'example'\n" +
            "           ~~~~~~~~~~~~~~~~~~~~~~^",
    )

    @Test
    fun errorMarkerBracketUnclosed() = assertInvalid(
        "name; (extra == 'example'",
        "Expected matching RIGHT_PARENTHESIS for LEFT_PARENTHESIS, " +
            "after marker expression\n" +
            "    name; (extra == 'example'\n" +
            "          ~~~~~~~~~~~~~~~~~~~^",
    )

    @Test
    fun errorNoUrlAfterAt() = assertInvalid(
        "name @ ",
        "Expected URL after @\n" +
            "    name @ \n" +
            "           ^",
    )

    @Test
    fun errorInvalidMarkerLvalue() = assertInvalid(
        "name; invalid_name",
        "Expected a marker variable or quoted string\n" +
            "    name; invalid_name\n" +
            "          ^",
    )

    @Test
    fun errorInvalidMarkerRvalue() = assertInvalid(
        "name; '3.7' <= invalid_name",
        "Expected a marker variable or quoted string\n" +
            "    name; '3.7' <= invalid_name\n" +
            "                   ^",
    )

    @TestFactory
    fun errorInvalidMarkerMalformedQuotedString() =
        dyn("TestRequirementParsing.test_error_invalid_marker_malformed_quoted_string") {
            val prefix = "packaging.requirements.InvalidRequirement: "
            val expected = it.str("expected")
            assertTrue(expected.startsWith(prefix))
            assertInvalid(it.str("to_parse"), expected.removePrefix(prefix))
        }

    @Test
    fun errorInvalidMarkerNotinWithoutWhitespace() = assertInvalid(
        "name; '3.7' notin python_version",
        "Expected marker operator, one of <=, <, !=, ==, >=, >, ~=, ===, " +
            "in, not in\n" +
            "    name; '3.7' notin python_version\n" +
            "                ^",
    )

    @Test
    fun errorWhenNoWordBoundary() = assertInvalid(
        "name; '3.6'inpython_version",
        "Expected marker operator, one of <=, <, !=, ==, >=, >, ~=, ===, " +
            "in, not in\n" +
            "    name; '3.6'inpython_version\n" +
            "               ^",
    )

    @Test
    fun errorInvalidMarkerNotWithoutIn() = assertInvalid(
        "name; '3.7' not python_version",
        "Expected 'in' after 'not'\n" +
            "    name; '3.7' not python_version\n" +
            "                    ^",
    )

    @Test
    fun errorInvalidMarkerWithInvalidOp() = assertInvalid(
        "name; '3.7' ~ python_version",
        "Expected marker operator, one of <=, <, !=, ==, >=, >, ~=, ===, " +
            "in, not in\n" +
            "    name; '3.7' ~ python_version\n" +
            "                ^",
    )

    @Test
    fun errorOnLegacyVersionOutsideTripleEquals() = assertInvalid(
        "name==1.0.org1",
        "Expected comma (within version specifier), " +
            "semicolon (after version specifier) or end\n" +
            "    name==1.0.org1\n" +
            "        ~~~~~^",
    )

    @Test
    fun errorOnMissingVersionAfterOp() = assertInvalid(
        "name==",
        "Expected semicolon (after name with no version specifier) or end\n" +
            "    name==\n" +
            "        ^",
    )

    @Test
    fun errorOnMissingOpAfterName() = assertInvalid(
        "name 1.0",
        "Expected semicolon (after name with no version specifier) or end\n" +
            "    name 1.0\n" +
            "         ^",
    )

    @Test
    fun errorOnRandomCharAfterSpecifier() = assertInvalid(
        "name >= 1.0 #",
        "Expected comma (within version specifier), " +
            "semicolon (after version specifier) or end\n" +
            "    name >= 1.0 #\n" +
            "         ~~~~~~~^",
    )

    @Test
    fun errorOnMissingCommaInSpecifier() = assertInvalid(
        "name >= 1.0 <= 2.0",
        "Expected comma (within version specifier), " +
            "semicolon (after version specifier) or end\n" +
            "    name >= 1.0 <= 2.0\n" +
            "         ~~~~~~~^",
    )

    // ---- TestRequirementBehaviour ----

    @Test
    fun typesWithNothing() {
        val req = Requirement.parse("foobar")
        assertNull(req.url)
        assertNull(req.marker)
    }

    @Test
    fun typesWithSpecifierAndMarker() {
        val req = Requirement.parse("foobar[quux]<2,>=3; os_name=='a'")
        assertNull(req.url)
        assertNotNull(req.marker)
    }

    @Test
    fun typesWithUrl() {
        val req = Requirement.parse("foobar @ http://foo.com")
        assertNotNull(req.url)
        assertNull(req.marker)
    }

    // test_str_and_repr: str() only; repr() is out of scope.
    @TestFactory
    fun strAndRepr() = dyn("TestRequirementBehaviour.test_str_and_repr") {
        val toParse = "name${it.str("extras")}${it.str("url_or_specifier")}${it.str("marker")}"
        assertEquals(toParse.trim(), Requirement.parse(toParse).toString())
    }

    @TestFactory
    fun equalReqsEqualHashes() = dyn("TestRequirementBehaviour.test_equal_reqs_equal_hashes") {
        val req1 = Requirement.parse(it.str("dep1"))
        val req2 = Requirement.parse(it.str("dep2"))
        assertEquals(req1, req2)
        assertEquals(req1.hashCode(), req2.hashCode())
    }

    @TestFactory
    fun equivalentReqsEqualHashesUnequalStrings() = dyn("TestRequirementBehaviour.test_equivalent_reqs_equal_hashes_unequal_strings") {
        val req1 = Requirement.parse(it.str("dep1"))
        val req2 = Requirement.parse(it.str("dep2"))
        assertEquals(req1, req2)
        assertEquals(req1.hashCode(), req2.hashCode())
        assertNotEquals(req1.toString(), req2.toString())
    }

    @TestFactory
    fun differentReqsDifferentHashes() = dyn("TestRequirementBehaviour.test_different_reqs_different_hashes") {
        val req1 = Requirement.parse(it.str("dep1"))
        val req2 = Requirement.parse(it.str("dep2"))
        assertNotEquals(req1, req2)
        assertNotEquals(req1.hashCode(), req2.hashCode())
    }

    @Test
    fun compareWithString() {
        assertFalse((Requirement.parse("packaging>=21.3") as Any) == "packaging>=21.3")
    }

    companion object {
        /** Stand-in for Python's default_environment(): CPython 3.12 on Linux x86_64. */
        val DEFAULT_ENV: MarkerEnvironment = MarkerEnvironment.builder()
            .implementationName("cpython").implementationVersion("3.12.4")
            .osName("posix").platformMachine("x86_64").platformRelease("6.8.0-45-generic")
            .platformSystem("Linux").platformVersion("#45-Ubuntu SMP PREEMPT_DYNAMIC Fri Aug 30 12:02:04 UTC 2024")
            .pythonFullVersion("3.12.4").platformPythonImplementation("CPython")
            .pythonVersion("3.12").sysPlatform("linux")
            .build()
    }
}
