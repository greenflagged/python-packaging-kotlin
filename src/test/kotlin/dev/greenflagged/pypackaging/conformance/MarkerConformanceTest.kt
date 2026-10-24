/*
 * Conformance tests ported from pypa/packaging 26.3, tests/test_markers.py:
 * https://github.com/pypa/packaging/blob/26.3/tests/test_markers.py
 *
 * Test vectors and test logic: pypa/packaging (Apache-2.0 OR BSD-2-Clause),
 * Copyright (c) Donald Stufft and individual contributors. See NOTICE.
 *
 * Adaptation: there is no default marker environment on the JVM. Where upstream calls
 * `evaluate()` or `evaluate(overrides)` (which merges overrides into
 * `default_environment()`), these tests use the fixed CPython-on-Linux environment
 * [BASE_ENV] with the test's overrides applied on top. Python's `extra=None` is
 * expressed by leaving `extra` out of the environment.
 *
 * Not ported:
 *   TestNode.test_accepts_value (non-str cases None/3/5/[]) — non-str inputs; Kotlin nodes take String.
 *   TestNode.test_str — Kotlin nodes have no str()/toString contract; serialize() is the analog (ported below).
 *   TestNode.test_repr — repr().
 *   TestNode.test_base_class — Node.serialize() raising NotImplementedError; Kotlin's MarkerVar is a sealed
 *     abstract class, so an un-overridden serialize() cannot exist.
 *   TestDefaultEnvironment.* (test_matches_expected, test_multidigit_minor_version,
 *     tests_when_releaselevel_final, tests_when_releaselevel_not_final) — default_environment() /
 *     _format_full_version describe a Python interpreter; none on the JVM.
 *   TestMarker.test_str_repr_eq_hash — the repr() assertion only (rest is ported).
 *   TestMarker.test_missing_environment_key_raises_undefined_environment_name — the
 *     `issubclass(UndefinedEnvironmentName, KeyError)` part only (Python exception hierarchy).
 *   TestMarker.test_evaluate_does_not_mutate_cached_environment — the `_cached_default_environment()`
 *     cache inspection; the observable behaviour (overrides do not leak into later evaluations) is ported.
 *   test_operator_rejects_non_marker — NotImplemented for non-Marker operands.
 *   test_right_hand_ops_and_typeerror — NotImplemented / TypeError for non-Marker operands.
 *   test_pickle_marker_roundtrip, test_pickle_marker_setstate_rejects_invalid_state,
 *     test_pickle_marker_old_format_loads, test_pickle_marker_26_0_format_loads,
 *     test_pickle_marker_25_0_format_loads, test_pickle_node_roundtrip,
 *     test_pickle_node_setstate_rejects_invalid_state,
 *     test_pickle_marker_setstate_legacy_slot_dict_without_markers_key,
 *     test_pickle_marker_setstate_rejects_invalid_markers_type,
 *     test_pickle_marker_setstate_rejects_invalid_marker_string — pickling / __setstate__.
 */
package dev.greenflagged.pypackaging.conformance

import dev.greenflagged.pypackaging.EvaluateContext
import dev.greenflagged.pypackaging.InvalidMarkerException
import dev.greenflagged.pypackaging.Marker
import dev.greenflagged.pypackaging.MarkerEnvironment
import dev.greenflagged.pypackaging.UndefinedComparisonException
import dev.greenflagged.pypackaging.UndefinedEnvironmentNameException
import dev.greenflagged.pypackaging.internal.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class MarkerConformanceTest {

    private companion object {
        /** Stand-in for `default_environment()`: a typical CPython 3.12 on Linux (os_name "posix"). */
        val BASE_ENV: MarkerEnvironment = MarkerEnvironment.builder()
            .implementationName("cpython")
            .implementationVersion("3.12.4")
            .osName("posix")
            .platformMachine("x86_64")
            .platformRelease("6.8.0")
            .platformSystem("Linux")
            .platformVersion("#1 SMP PREEMPT_DYNAMIC")
            .pythonFullVersion("3.12.4")
            .platformPythonImplementation("CPython")
            .pythonVersion("3.12")
            .sysPlatform("linux")
            .build()

        /** `default_environment()` updated with [overrides], as Python's `Marker.evaluate(environment)` does. */
        fun env(overrides: Map<*, *>? = null): MarkerEnvironment {
            val b = BASE_ENV.toBuilder()
            overrides?.forEach { (k, v) ->
                when (v) {
                    null -> {} // Python's extra=None: treated as no extra.
                    is String -> b.set(k as String, v)
                    is Set<*> -> b.setValues(k as String, v.mapTo(LinkedHashSet()) { it as String })
                    else -> error("unsupported environment value $v")
                }
            }
            return b.build()
        }

        fun env(vararg overrides: Pair<String, Any?>): MarkerEnvironment = env(mapOf(*overrides))

        fun evaluate(marker: String, environment: Map<*, *>?): Boolean = Marker.parse(marker).evaluate(env(environment))
    }

    // ---- TestNode ----

    // TestNode.test_accepts_value (string cases only; see header).
    @TestFactory
    fun nodeAcceptsValue(): List<DynamicTest> =
        PypaFixtures.cases("test_markers", "TestNode.test_accepts_value").filter { it["value"] is String }.map { case ->
            DynamicTest.dynamicTest("TestNode.test_accepts_value ${case["value"]}") {
                assertEquals(case.str("value"), Value(case.str("value")).value)
            }
        }

    @TestFactory
    fun valueSerializeChoosesQuoteDelimiter(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestNode.test_value_serialize_chooses_quote_delimiter") { case ->
            assertEquals(case.str("expected"), Value(case.str("value")).serialize())
        }

    @Test
    fun valueSerializeRejectsBothQuoteDelimiters() {
        // Python raises ValueError; the internal Kotlin node throws IllegalStateException.
        val e = assertThrows(IllegalStateException::class.java) { Value("a\"b'c").serialize() }
        assertTrue(e.message!!.contains("Cannot serialize marker value containing both quote characters"))
    }

    // ---- TestOperatorEvaluation ----

    @Test
    fun prefersPep440() {
        assertTrue(Marker.parse("\"2.7.9\" < python_full_version").evaluate(env("python_full_version" to "2.7.10")))
        assertFalse(Marker.parse("\"2.7.9\" < python_full_version").evaluate(env("python_full_version" to "2.7.8")))
    }

    @Test
    fun newStringRules() {
        assertFalse(Marker.parse("\"b\" < python_full_version").evaluate(env("python_full_version" to "c")))
        assertFalse(Marker.parse("\"b\" < python_full_version").evaluate(env("python_full_version" to "a")))
        assertFalse(Marker.parse("\"b\" > \"a\"").evaluate(env("a" to "a")))
        assertFalse(Marker.parse("\"b\" < \"a\"").evaluate(env("a" to "a")))
        assertFalse(Marker.parse("\"b\" >= \"a\"").evaluate(env("a" to "a")))
        assertFalse(Marker.parse("\"b\" <= \"a\"").evaluate(env("a" to "a")))
        assertTrue(Marker.parse("\"a\" <= \"a\"").evaluate(env("a" to "a")))
    }

    @Test
    fun failsWhenUndefined() {
        assertThrows(UndefinedComparisonException::class.java) { Marker.parse("'2.7.0' ~= os_name").evaluate(env()) }
    }

    @Test
    fun arbitraryEqualityOnNonVersionKeyIsUndefined() {
        for (marker in listOf("os_name === 'posix'", "sys_platform === 'linux'")) {
            assertThrows(UndefinedComparisonException::class.java, { Marker.parse(marker).evaluate(env()) }, marker)
        }
    }

    @Test
    fun allowsPrerelease() {
        assertTrue(Marker.parse("python_full_version > \"3.6.2\"").evaluate(env("python_full_version" to "3.11.0a5")))
    }

    // ---- TestMarker ----

    @TestFactory
    fun parsesValid(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_parses_valid") { Marker.parse(it.str("marker_string")) }

    @TestFactory
    fun parsesInvalid(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_parses_invalid") { case ->
            assertThrows(InvalidMarkerException::class.java) { Marker.parse(case.str("marker_string")) }
        }

    @TestFactory
    fun parsesInvalidTrailingLineBreak(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_parses_invalid_trailing_line_break") { case ->
            assertThrows(InvalidMarkerException::class.java) { Marker.parse("python_version >= \"3\"" + case.str("line_break")) }
        }

    @TestFactory
    fun parsesTrailingHorizontalWhitespace(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_parses_trailing_horizontal_whitespace") { case ->
            assertEquals(Marker.parse("python_version >= \"3\""), Marker.parse("python_version >= \"3\"" + case.str("whitespace")))
        }

    @TestFactory
    fun parsesInvalidMalformedQuotedString(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_parses_invalid_malformed_quoted_string") { case ->
            val e = assertThrows(InvalidMarkerException::class.java) { Marker.parse(case.str("marker_string")) }
            // pytest's ExceptionInfo.exconly() is "<qualified type>: <str(exc)>".
            assertEquals(case.str("expected"), "packaging.markers.InvalidMarker: ${e.message}")
        }

    // test_str_repr_eq_hash (repr assertion not ported).
    @TestFactory
    fun strEqHash(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_str_repr_eq_hash") { case ->
            val markerString = case.str("marker_string")
            val expected = case.str("expected")
            val m = Marker.parse(markerString)
            assertEquals(expected, m.toString())
            assertEquals(Marker.parse(markerString), m)
            assertEquals(Marker.parse(expected), m)
            assertEquals(Marker.parse(markerString).hashCode(), Marker.parse(markerString).hashCode())
            assertEquals(Marker.parse(markerString).hashCode(), Marker.parse(expected).hashCode())
        }

    @TestFactory
    fun differentMarkersDifferentHashes(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_different_markers_different_hashes") { case ->
            val marker1 = Marker.parse(case.str("example1"))
            val marker2 = Marker.parse(case.str("example2"))
            assertNotEquals(marker1, marker2)
            assertNotEquals(marker1.hashCode(), marker2.hashCode())
        }

    @Test
    fun compareMarkersToOtherObjects() {
        assertNotEquals("os_name == 'nt'" as Any, Marker.parse("os_name == 'nt'") as Any)
        assertFalse(Marker.parse("os_name == 'nt'").equals("os_name == 'nt'"))
    }

    @Test
    fun environmentAssumesEmptyExtra() {
        assertFalse(Marker.parse("extra == \"im_valid\"").evaluate(env()))
    }

    @Test
    fun environmentWithExtraNone() {
        // Python passes {"extra": None}; here that is an environment without `extra`.
        assertFalse(Marker.parse("extra == \"im_valid\"").evaluate(env("extra" to null)))
    }

    @Test
    fun environmentWithNoExtras() {
        val marker = Marker.parse("os_name == 'foo'")
        assertTrue(marker.evaluate(env("os_name" to "foo"), EvaluateContext.REQUIREMENT))
        assertFalse(marker.evaluate(env("os_name" to "bar"), EvaluateContext.REQUIREMENT))
    }

    @TestFactory
    fun evaluates(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_evaluates") { case ->
            assertEquals(case.bool("expected"), evaluate(case.str("marker_string"), case["environment"] as Map<*, *>?))
        }

    @TestFactory
    fun parsesPep345Valid(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_parses_pep345_valid") { Marker.parse(it.str("marker_string")) }

    @TestFactory
    fun evaluatePep345Markers(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_evaluate_pep345_markers") { case ->
            assertEquals(case.bool("expected"), evaluate(case.str("marker_string"), case["environment"] as Map<*, *>?))
        }

    @TestFactory
    fun parsesSetuptoolsLegacyValid(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_parses_setuptools_legacy_valid") { Marker.parse(it.str("marker_string")) }

    @Test
    fun evaluateSetuptoolsLegacyMarkers() {
        assertFalse(Marker.parse("python_implementation=='Jython'").evaluate(env("platform_python_implementation" to "CPython")))
    }

    @Test
    fun extraStrNormalization() {
        assertEquals("\"s-p-a-m\" == extra", Marker.parse("'S_P__A_M' == extra").toString())
        assertEquals("extra == \"s-p-a-m\"", Marker.parse("extra == 'S_P__A_M'").toString())
    }

    @Test
    fun extraComparedToVariableNotNormalized() {
        assertEquals("extra == os_name", Marker.parse("extra == os_name").toString())
        assertEquals("os_name == extra", Marker.parse("os_name == extra").toString())
    }

    @Test
    fun nestedExtraStrNormalization() {
        val marker = Marker.parse("(extra == \"Foo_Bar\" or extra == \"Baz\") and python_version >= \"3\"")
        assertEquals("(extra == \"foo-bar\" or extra == \"baz\") and python_version >= \"3\"", marker.toString())
        assertTrue(marker.evaluate(env("extra" to "foo-bar", "python_version" to "3.12")))
    }

    @TestFactory
    fun membershipValueStrNormalization(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_membership_value_str_normalization") { case ->
            val variable = case.str("variable")
            val raw = Marker.parse("\"S_P__A_M\" in $variable")
            val normalized = Marker.parse("\"s-p-a-m\" in $variable")
            assertEquals("\"s-p-a-m\" in $variable", raw.toString())
            assertEquals(normalized, raw)
            assertEquals(normalized.hashCode(), raw.hashCode())
        }

    @TestFactory
    fun membershipValueStrNormalizationNegated(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_membership_value_str_normalization_negated") { case ->
            val variable = case.str("variable")
            val raw = Marker.parse("\"Foo_Bar\" not in $variable")
            assertEquals("\"foo-bar\" not in $variable", raw.toString())
            assertEquals(Marker.parse("\"foo-bar\" not in $variable"), raw)
            assertEquals(Marker.parse("\"foo-bar\" not in $variable").hashCode(), raw.hashCode())
        }

    @Test
    fun pythonFullVersionUntaggedUserProvided() {
        assertTrue(Marker.parse("python_full_version < '3.12'").evaluate(env("python_full_version" to "3.11.1+")))
    }

    @Test
    fun pythonFullVersionUntagged() {
        // Upstream mocks platform.python_version() to "3.11.1+" for the default environment;
        // here the base environment itself carries that value.
        val base = BASE_ENV.toBuilder().pythonFullVersion("3.11.1+").build()
        assertTrue(Marker.parse("python_full_version < '3.12'").evaluate(base))
    }

    // test_evaluate_does_not_mutate_cached_environment (cache inspection not ported).
    @Test
    fun evaluateDoesNotMutateEnvironment() {
        val marker = Marker.parse("os_name == 'magic'")
        assertTrue(marker.evaluate(env("os_name" to "magic")))
        assertEquals("posix", BASE_ENV.values["os_name"])
        assertFalse("extra" in BASE_ENV.values)
        assertFalse(marker.evaluate(BASE_ENV))
    }

    @TestFactory
    fun extrasAndDependencyGroups(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_extras_and_dependency_groups") { case ->
            val variable = case.str("variable")
            val environment = env(variable to setOf("foo", "bar"))
            assertEquals(case.bool("result"), Marker.parse(case.str("expression").replace("{0}", variable)).evaluate(environment))
        }

    @TestFactory
    fun extrasAndDependencyGroupsDisallowed(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_extras_and_dependency_groups_disallowed") { case ->
            val marker = Marker.parse("\"foo\" in ${case.str("variable")}")
            assertFalse(marker.evaluate(env(), EvaluateContext.LOCK_FILE))
            assertThrows(UndefinedEnvironmentNameException::class.java) { marker.evaluate(env()) }
            assertThrows(UndefinedEnvironmentNameException::class.java) { marker.evaluate(env(), EvaluateContext.REQUIREMENT) }
        }

    @Test
    fun missingEnvironmentKeyRaisesUndefinedEnvironmentName() {
        val marker = Marker.parse("\"foo\" in extras")
        val e = assertThrows(UndefinedEnvironmentNameException::class.java) { marker.evaluate(env()) }
        assertEquals("extras", e.name) // Python: ctx.value.args == ("extras",)
    }

    @TestFactory
    fun setValuedMarkerOnLhsRaisesUndefinedComparison(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_set_valued_marker_on_lhs_raises_undefined_comparison") { case ->
            val marker = Marker.parse("${case.str("variable")} ${case.str("op")} 'foo'")
            assertThrows(UndefinedComparisonException::class.java) { marker.evaluate(env(), EvaluateContext.LOCK_FILE) }
        }

    @TestFactory
    fun versionLikeEquality(): List<DynamicTest> =
        PypaFixtures.dynamic("test_markers", "TestMarker.test_version_like_equality") { case ->
            assertEquals(case.bool("expected"), evaluate(case.str("marker_string"), case["environment"] as Map<*, *>?))
        }

    // ---- module-level tests ----

    @Test
    fun andOperatorEvaluatesTrue() {
        val m = Marker.parse("python_version >= \"3.6\"").and(Marker.parse("os_name == \"posix\""))
        assertTrue(m.evaluate(env("python_version" to "3.8", "os_name" to "posix")))
    }

    @Test
    fun andOperatorStrEquality() {
        val a = Marker.parse("python_version >= \"3.6\" and os_name == \"posix\"")
        val b = Marker.parse("python_version >= \"3.6\"").and(Marker.parse("os_name == \"posix\""))
        assertEquals(a, b)
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun orOperatorEvaluatesTrue() {
        val m = Marker.parse("python_version < \"3.6\"").or(Marker.parse("os_name == \"windows\""))
        assertTrue(m.evaluate(env("python_version" to "3.7", "os_name" to "windows")))
    }

    @Test
    fun orOperatorStrEquality() {
        val a = Marker.parse("python_version < \"3.6\" or os_name == \"windows\"")
        val b = Marker.parse("python_version < \"3.6\"").or(Marker.parse("os_name == \"windows\""))
        assertEquals(a, b)
        assertEquals(a.toString(), b.toString())
    }

    // test_inplace_operators_fallback: Python's `m &= other` is `m = m.and(other)` here.
    @Test
    fun inplaceOperatorsFallback() {
        var m = Marker.parse("python_version >= \"3.6\"")
        m = m.and(Marker.parse("os_name == \"posix\""))
        assertEquals(Marker.parse("python_version >= \"3.6\"").and(Marker.parse("os_name == \"posix\"")), m)
    }

    @Test
    fun chainingAssociativityAndStr() {
        val a = Marker.parse("(python_version >= \"3.6\" and os_name == \"posix\") and platform_system == \"Linux\"")
        val b = Marker.parse("python_version >= \"3.6\"")
            .and(Marker.parse("os_name == \"posix\""))
            .and(Marker.parse("platform_system == \"Linux\""))
        assertEquals(a, b)
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun strPreservesNestedGroupPrecedence() {
        val m = Marker.parse("python_version < \"3.10\" and ((sys_platform == \"linux\" or sys_platform == \"darwin\"))")
        val reparsed = Marker.parse(m.toString())
        for (e in listOf(
            env("python_version" to "3.12", "sys_platform" to "darwin"),
            env("python_version" to "3.12", "sys_platform" to "linux"),
            env("python_version" to "3.9", "sys_platform" to "darwin"),
        )) {
            assertEquals(m.evaluate(e), reparsed.evaluate(e), e.toString())
        }
    }

    @Test
    fun hashEqForCombinedMarkers() {
        assertEquals(
            Marker.parse("python_version >= \"3.6\" and os_name == \"posix\"").hashCode(),
            Marker.parse("python_version >= \"3.6\"").and(Marker.parse("os_name == \"posix\"")).hashCode(),
        )
    }

    @Test
    fun evaluationOfCombinedMarkers() {
        val m = Marker.parse("python_version >= \"3.6\"")
            .and(Marker.parse("os_name == \"posix\""))
            .and(Marker.parse("platform_system == \"Linux\""))
        assertTrue(m.evaluate(env("python_version" to "3.8", "os_name" to "posix", "platform_system" to "Linux")))
    }
}
