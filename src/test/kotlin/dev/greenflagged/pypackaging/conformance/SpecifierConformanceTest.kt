package dev.greenflagged.pypackaging.conformance

import dev.greenflagged.pypackaging.InvalidSpecifierException
import dev.greenflagged.pypackaging.Specifier
import dev.greenflagged.pypackaging.SpecifierSet
import dev.greenflagged.pypackaging.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/*
 * Conformance tests ported from pypa/packaging 26.3, tests/test_specifiers.py:
 * https://github.com/pypa/packaging/blob/26.3/tests/test_specifiers.py
 *
 * The test vectors and test logic are from pypa/packaging (Apache-2.0 OR BSD-2-Clause,
 * Copyright (c) Donald Stufft and individual contributors). The parametrized cases are
 * harvested into src/test/resources/pypa/test_specifiers.json; see NOTICE.
 *
 * Not ported:
 * - TestSpecifier.test_match_args — `__match_args__` / `_str` (Python-only).
 * - TestSpecifier.test_specifiers_str_and_repr, TestSpecifierSet.test_specifiers_str_and_repr — the
 *   `repr()` half only; the `str()` half is ported.
 * - TestSpecifier.test_specifiers, TestSpecifier.test_invalid_version, TestSpecifier.test_specifier_filter,
 *   TestSpecifierSet.test_specifier_filter — the `to_range()` half only (not in v1 scope); the rest is ported.
 * - TestSpecifierInternal.test_spec_version_cache_consistency, test_spec_version_cache_with_wildcards,
 *   test_spec_version_cache_with_arbitrary_equality — internal `_spec_version` cache; they make no other
 *   assertions. (test_spec_version_cache_compatible_operator's one membership assertion is ported.)
 * - TestSpecifierSet.test_specifiers_combine_not_implemented — TypeError for `& 12`; Kotlin's types prevent it.
 * - TestSpecifierSet.test_is_subset, test_is_superset, test_is_disjoint,
 *   test_set_relations_raise_on_prerelease_policy_mismatch, test_set_relations_require_specifierset,
 *   test_set_relations_reject_arbitrary_equality — is_subset/is_superset/is_disjoint (not in v1 scope).
 * - TestIsUnsatisfiable.test_cache_reset_on_prereleases_change — only its first (non-cache) assertion is ported.
 * - TestIsUnsatisfiable.test_range_bounds_hashable_and_equal, test_range_bounds_repr,
 *   test_range_bounds_reject_foreign_comparison — internal `_to_ranges()` bound objects, repr, NotImplemented.
 * - test_pickle_* (15 functions) — pickling / `__setstate__`.
 * - test_specifier_construction_is_lazy, test_specifierset_construction_is_lazy — internal laziness.
 * - test_filter_keyed_none_version_is_skipped — a `key=` returning None; Kotlin's `filterBy` key is
 *   non-null `String`, so the type system prevents it.
 * - test_specifierset_contains_reads_cached_bounds — the `_ranges = ()` override half (internal cache);
 *   the first, behavioural half is ported.
 * - TestSpecifierSetToRangeEquivalence (all 4 tests) — need `to_range()` (not in v1 scope).
 * Cache-only assertions in TestIsUnsatisfiable.test_result_is_cached, test_and_reuses_interval_cache,
 * test_canonicalization_keeps_range_cache and test_canonicalization_keeps_unsatisfiable_cache are dropped;
 * their behavioural assertions are ported.
 */
class SpecifierConformanceTest {

    private companion object {
        const val FILE = "test_specifiers"

        fun dyn(test: String, body: (Case) -> Unit): List<DynamicTest> = PypaFixtures.dynamic(FILE, test, body)

        /** A decoded case value as a version item: a String, or a parsed Version for `PyObject("version")`. */
        fun item(x: Any?): Any = when (x) {
            is String -> x
            is PyObject -> {
                check(x.type == "version") { "unexpected object $x" }
                Version.parse(x.str)
            }
            else -> error("not a version item: $x")
        }

        fun items(x: Any?): List<Any> = (x as List<*>).map(::item)

        fun Specifier.containsAny(v: Any, pre: Boolean? = null): Boolean = when (v) {
            is String -> contains(v, pre)
            is Version -> contains(v, pre)
            else -> error("bad item $v")
        }

        fun SpecifierSet.containsAny(v: Any, pre: Boolean? = null, installed: Boolean? = null): Boolean = when (v) {
            is String -> contains(v, pre, installed)
            is Version -> contains(v, pre, installed)
            else -> error("bad item $v")
        }

        // Python's filter() takes a mixed iterable of str and Version. The Kotlin API has
        // filter (strings), filterVersions and filterBy; a mixed list goes through filterBy
        // with toString as the key (str(Version) is what Python matches `===` against, and it
        // re-parses to an equal Version for every other operator).
        @Suppress("UNCHECKED_CAST")
        fun Specifier.filterAny(xs: List<Any>, pre: Boolean? = null): List<Any> = when {
            xs.all { it is String } -> filter(xs as List<String>, pre)
            xs.all { it is Version } -> filterVersions(xs as List<Version>, pre)
            else -> filterBy(xs, { it.toString() }, pre)
        }

        @Suppress("UNCHECKED_CAST")
        fun SpecifierSet.filterAny(xs: List<Any>, pre: Boolean? = null): List<Any> = when {
            xs.all { it is String } -> filter(xs as List<String>, pre)
            xs.all { it is Version } -> filterVersions(xs as List<Version>, pre)
            else -> filterBy(xs, { it.toString() }, pre)
        }

        /** `operator.eq` / `operator.ne` from a harvested `PyRepr`. */
        fun op(x: Any?): (Any, Any) -> Boolean = when ((x as PyRepr).repr) {
            "<built-in function eq>" -> { a, b -> a == b }
            "<built-in function ne>" -> { a, b -> a != b }
            else -> error("unexpected operator $x")
        }

        fun indexes(x: Any?): List<Int> = (x as List<*>).map { (it as Long).toInt() }

        // _version_family / _SAMPLE_BASES / _SAMPLE_VERSIONS, transcribed.
        fun versionFamily(base: String): List<String> = listOf(
            "$base.dev0", "$base.dev1", "$base.dev0+local", "${base}a0", "${base}a0.post0.dev0", "${base}a0.post0",
            "${base}a1.dev1", "${base}a1.dev1+local", "${base}a1", "${base}a1+local", "${base}b1",
            "${base}b2.post1.dev1", "${base}b2.post1", "${base}rc1.dev1", "${base}rc1", "${base}rc2", "${base}a2.dev0",
            base, "$base.0", "$base.post0.dev0", "$base.post0", "$base.post1.dev0", "$base.post1",
            "$base.post1+local", "$base.post2", "$base+local", "$base+local1", "$base+local2", "$base+1",
            "$base+1.local", "$base.1.dev1", "$base.1a1", "$base.1", "$base.1+local", "$base.1.post1",
        )

        val SAMPLE_BASES = listOf(
            "0", "0.0", "1.0", "1.1", "1.2", "2.0", "2.1", "3.0", "1.0.1", "1.4.2", "2.0.0", "3.10.2", "3.8", "3.9",
            "3.10", "3.11", "3.12", "3.13", "3.14", "10.0", "100.0", "1!0.0", "1!1.0", "1!2.0",
        )

        val SAMPLE_VERSIONS: List<String> =
            SAMPLE_BASES.flatMap(::versionFamily).distinct() +
                listOf("foobar", "a", "b", "not-a-version", "1.01", "1.0-1", "v1.0")
    }

    @Nested
    inner class TestSpecifier {
        @TestFactory
        fun specifiersValid() = dyn("TestSpecifier.test_specifiers_valid") { Specifier.parse(it.str("specifier")) }

        @TestFactory
        fun specifiersInvalid() = dyn("TestSpecifier.test_specifiers_invalid") {
            assertThrows(InvalidSpecifierException::class.java) { Specifier.parse(it.str("specifier")) }
        }

        @TestFactory
        fun specifiersNormalized() = dyn("TestSpecifier.test_specifiers_normalized") {
            val version = it.str("version")
            val ops = if ("+" !in version) listOf("~=", "==", "!=", "<=", ">=", "<", ">") else listOf("==", "!=")
            for (op in ops) Specifier.parse(op + version)
        }

        // test_specifiers_str_and_repr: str() half only.
        @TestFactory
        fun specifiersStr() = dyn("TestSpecifier.test_specifiers_str_and_repr") {
            assertEquals(it.str("expected"), Specifier.parse(it.str("specifier")).toString())
        }

        @TestFactory
        fun specifiersHash() = dyn("TestSpecifier.test_specifiers_hash") {
            assertEquals(Specifier.parse(it.str("specifier")).hashCode(), Specifier.parse(it.str("specifier")).hashCode())
        }

        // Python's op(str, Specifier) / op(Specifier, str) parse the str; all three forms are this one.
        @TestFactory
        fun comparisonTrue() = dyn("TestSpecifier.test_comparison_true") {
            assertTrue(op(it["op"])(Specifier.parse(it.str("left")), Specifier.parse(it.str("right"))))
        }

        @TestFactory
        fun comparisonCanonicalizes() = dyn("TestSpecifier.test_comparison_canonicalizes") {
            assertEquals(Specifier.parse(it.str("left")), Specifier.parse(it.str("right")))
        }

        @TestFactory
        fun comparisonFalse() = dyn("TestSpecifier.test_comparison_false") {
            assertFalse(op(it["op"])(Specifier.parse(it.str("left")), Specifier.parse(it.str("right"))))
        }

        // Kotlin's analogue: equals(Any?) with a non-Specifier is false.
        @Test
        fun comparisonNonSpecifier() {
            val spec: Any = Specifier.parse("==1.0")
            assertNotEquals(spec, 12)
            assertFalse(spec.equals(12))
            assertNotEquals(spec, "12")
            assertFalse(spec.equals("12"))
        }

        // test_specifiers (to_range half not ported).
        @TestFactory
        fun specifiers() = dyn("TestSpecifier.test_specifiers") {
            val version = it.str("version")
            val spec = Specifier.parse(it.str("spec_str"), true)
            val expected = it.bool("expected")
            assertEquals(expected, version in spec)
            assertEquals(expected, spec.contains(version, null))
            assertEquals(expected, Version.parse(version) in spec)
            assertEquals(expected, spec.contains(Version.parse(version), null))
        }

        // test_invalid_version (to_range half not ported).
        @TestFactory
        fun invalidVersion() = dyn("TestSpecifier.test_invalid_version") {
            val spec = Specifier.parse(it.str("spec_str"), true)
            assertEquals(it.bool("expected"), spec.contains(it.str("version")))
        }

        // Python's prereleases setter becomes withPrereleases.
        @TestFactory
        fun specifierPrereleasesSet() = dyn("TestSpecifier.test_specifier_prereleases_set") {
            val version = it.str("version")
            var spec = Specifier.parse(it.str("specifier"), it.boolOrNull("initial_prereleases"))
            assertEquals(it.bool("initial_contains"), version in spec)
            assertEquals(it.bool("initial_contains"), spec.contains(version, null))
            spec = spec.withPrereleases(it.boolOrNull("set_prereleases"))
            assertEquals(it.bool("final_contains"), version in spec)
            assertEquals(it.bool("final_contains"), spec.contains(version, null))
        }

        @TestFactory
        fun arbitraryEquality() = dyn("TestSpecifier.test_arbitrary_equality") {
            assertEquals(it.bool("expected"), Specifier.parse(it.str("spec_str")).contains(it.str("version")))
        }

        @TestFactory
        fun arbitraryEqualityNormalization() = dyn("TestSpecifier.test_arbitrary_equality_normalization") {
            val spec = Specifier.parse(it.str("spec_str"), true)
            assertEquals(it.bool("expected"), spec.containsAny(item(it["version"])))
        }

        @TestFactory
        fun specifierPrereleasesDetection() = dyn("TestSpecifier.test_specifier_prereleases_detection") {
            assertEquals(it.boolOrNull("expected"), Specifier.parse(it.str("specifier")).prereleases)
        }

        @TestFactory
        fun specifiersPrereleases() = dyn("TestSpecifier.test_specifiers_prereleases") {
            val spec = Specifier.parse(it.str("specifier"), it.boolOrNull("spec_pre"))
            assertEquals(it.bool("expected"), spec.contains(it.str("version"), it.boolOrNull("contains_pre")))
        }

        // test_specifier_filter (to_range half not ported).
        @TestFactory
        fun specifierFilter() = dyn("TestSpecifier.test_specifier_filter") {
            val spec = Specifier.parse(it.str("specifier"), it.boolOrNull("specifier_prereleases"))
            assertEquals(items(it["expected"]), spec.filterAny(items(it["input"]), it.boolOrNull("prereleases")))
        }

        @TestFactory
        fun specifierFilterArbitraryEqualityNormalization() =
            dyn("TestSpecifier.test_specifier_filter_arbitrary_equality_normalization") {
                val spec = Specifier.parse(it.str("specifier"), true)
                assertEquals(items(it["expected"]), spec.filterAny(items(it["input"])))
            }

        @TestFactory
        fun specifierFilterWithKey() = dyn("TestSpecifier.test_specifier_filter_with_key") {
            val items = listOf(mapOf("version" to "1.0"), mapOf("version" to "2.1a1"))
            val spec = Specifier.parse(">=2.0")
            val result = spec.filterBy(items, { item -> item.getValue("version") }, it.boolOrNull("prereleases"))
            assertEquals(indexes(it["expected_indexes"]).map { i -> items[i] }, result)
        }

        @TestFactory
        fun specifierOperatorProperty() = dyn("TestSpecifier.test_specifier_operator_property") {
            assertEquals(it.str("op"), Specifier.parse(it.str("spec")).operator)
        }

        @TestFactory
        fun specifierVersionProperty() = dyn("TestSpecifier.test_specifier_version_property") {
            assertEquals(it.str("version"), Specifier.parse(it.str("spec")).version)
        }

        @TestFactory
        fun length() = dyn("TestSpecifier.test_length") {
            assertEquals((it["expected_length"] as Long).toInt(), SpecifierSet.parse(it.str("spec_str")).size)
        }

        @TestFactory
        fun iteration() = dyn("TestSpecifier.test_iteration") {
            val got = SpecifierSet.parse(it.str("spec_str")).map { s -> s.toString() }.toSet()
            assertEquals(it.list("expected_items").toSet(), got)
        }

        @Test
        fun specifierEqualForCompatibleOperator() {
            assertNotEquals(Specifier.parse("~=1.18.0"), Specifier.parse("~=1.18"))
        }

        @Test
        fun specifierHashForCompatibleOperator() {
            assertNotEquals(Specifier.parse("~=1.18.0").hashCode(), Specifier.parse("~=1.18").hashCode())
        }
    }

    @Nested
    inner class TestSpecifierInternal {
        // test_spec_version_cache_compatible_operator: only `assert versions[0] in spec` is in scope.
        @TestFactory
        fun specVersionCacheCompatibleOperator() = dyn("TestSpecifierInternal.test_spec_version_cache_compatible_operator") {
            val spec = Specifier.parse(it.str("specifier"), true)
            assertTrue((it.list("versions")[0] as String) in spec)
        }
    }

    @Nested
    inner class TestSpecifierSet {
        @TestFactory
        fun emptySpecifier() = dyn("TestSpecifierSet.test_empty_specifier") {
            val version = it.str("version")
            val spec = SpecifierSet.EMPTY.withPrereleases(true)
            assertTrue(version in spec)
            assertTrue(spec.contains(version, null))
            assertTrue(Version.parse(version) in spec)
            assertTrue(spec.contains(Version.parse(version), null))
        }

        @TestFactory
        fun emptySpecifierArbitraryString() = dyn("TestSpecifierSet.test_empty_specifier_arbitrary_string") {
            val spec = SpecifierSet.parse("", it.boolOrNull("prereleases"))
            assertTrue(spec.contains("foobar"))
            @Suppress("UNCHECKED_CAST")
            assertEquals(it.list("expected"), spec.filter(it.list("versions") as List<String>))
        }

        @TestFactory
        fun emptySpecifierOrderingWithArbitrary() = dyn("TestSpecifierSet.test_empty_specifier_ordering_with_arbitrary") {
            @Suppress("UNCHECKED_CAST")
            assertEquals(it.list("expected"), SpecifierSet.parse("").filter(it.list("versions") as List<String>))
        }

        @Test
        fun createFromSpecifiers() {
            val specs = listOf(">=1.0", "!=1.1", "!=1.2", "<2.0").map { Specifier.parse(it) }
            val spec = SpecifierSet.of(specs)
            assertEquals(specs.toSet(), spec.toSet())
        }

        @TestFactory
        fun specifierPrereleasesExplicit() = dyn("TestSpecifierSet.test_specifier_prereleases_explicit") {
            val version = it.str("version")
            var spec = SpecifierSet.parse(it.str("spec_str"), it.boolOrNull("initial_prereleases"))
            assertEquals(it.bool("initial_contains"), version in spec)
            assertEquals(it.bool("initial_contains"), spec.contains(version, null))
            spec = spec.withPrereleases(it.boolOrNull("set_prereleases"))
            assertEquals(it.bool("final_contains"), version in spec)
            assertEquals(it.bool("final_contains"), spec.contains(version, null))
        }

        @Test
        fun specifierContainsPrereleases() {
            var spec = SpecifierSet.EMPTY
            assertNull(spec.prereleases)
            assertTrue(spec.contains("1.0.dev1"))
            assertTrue(spec.contains("1.0.dev1", true))

            spec = SpecifierSet.parse("", true)
            assertEquals(true, spec.prereleases)
            assertTrue(spec.contains("1.0.dev1"))
            assertFalse(spec.contains("1.0.dev1", false))
        }

        // Omitted Python kwargs are passed as null, which is the Python default.
        @TestFactory
        fun specifierContainsInstalledPrereleases() = dyn("TestSpecifierSet.test_specifier_contains_installed_prereleases") {
            val spec = SpecifierSet.parse(it.str("specifier"), it.boolOrNull("spec_prereleases"))
            assertEquals(
                it.bool("expected"),
                spec.contains(it.str("version"), it.boolOrNull("contains_prereleases"), it.boolOrNull("installed")),
            )
            val compat = SpecifierSet.parse("~=1.0", false)
            assertTrue(compat.contains("1.1.0.dev1", null, true))
            assertFalse(compat.contains("1.1.0.dev1", false, false))
        }

        // test_specifier_filter (to_range half not ported).
        @TestFactory
        fun specifierFilter() = dyn("TestSpecifierSet.test_specifier_filter") {
            val spec = SpecifierSet.parse(it.str("specifier"), it.boolOrNull("specifier_prereleases"))
            assertEquals(items(it["expected"]), spec.filterAny(items(it["input"]), it.boolOrNull("prereleases")))
        }

        @TestFactory
        fun specifiersetFilterWithKey() = dyn("TestSpecifierSet.test_specifierset_filter_with_key") {
            val items = listOf(mapOf("version" to "1.0"), mapOf("version" to "2.1a1"))
            val spec = SpecifierSet.parse(">=2")
            val result = spec.filterBy(items, { item -> item.getValue("version") }, it.boolOrNull("prereleases"))
            assertEquals(indexes(it["expected_indexes"]).map { i -> items[i] }, result)
        }

        @TestFactory
        fun emptySpecifiersetFilterWithKey() = dyn("TestSpecifierSet.test_empty_specifierset_filter_with_key") {
            val items = listOf(mapOf("version" to "2.0a1"), mapOf("version" to "2.1"))
            val spec = SpecifierSet.parse("", it.boolOrNull("prereleases"))
            val result = spec.filterBy(items, { item -> item.getValue("version") })
            assertEquals(indexes(it["expected_indexes"]).map { i -> items[i] }, result)
        }

        @TestFactory
        fun filterExclusionaryBridges() = dyn("TestSpecifierSet.test_filter_exclusionary_bridges") {
            val spec = SpecifierSet.parse(it.str("specifier"))
            assertEquals(items(it["expected"]), spec.filterAny(items(it["input"]), it.boolOrNull("prereleases")))
        }

        @TestFactory
        fun containsExclusionaryBridges() = dyn("TestSpecifierSet.test_contains_exclusionary_bridges") {
            val spec = SpecifierSet.parse(it.str("specifier"))
            assertEquals(it.bool("expected"), spec.contains(it.str("version"), it.boolOrNull("prereleases")))
        }

        @TestFactory
        fun containsRejectsInvalidSpecifier() = dyn("TestSpecifierSet.test_contains_rejects_invalid_specifier") {
            assertFalse(SpecifierSet.parse(it.str("specifier"), true).contains(it.str("input")))
        }

        @TestFactory
        fun containsPostReleaseBoundaryIntersection() = dyn("TestSpecifierSet.test_contains_post_release_boundary_intersection") {
            val ss = SpecifierSet.parse(it.str("spec_str"))
            val version = it.str("version")
            assertEquals(it.bool("expected"), ss.contains(version, true))
            assertEquals(ss.all { s -> s.contains(version, true) }, ss.contains(version, true))
        }

        @Test
        fun filterPostReleaseBoundaryIntersection() {
            val ss = SpecifierSet.parse(">1.0,!=1.0.post1")
            assertEquals(listOf("1.1"), ss.filter(listOf("1.0.post2", "1.0.post3", "1.1"), true))
        }

        // Intentional difference 7: Python raises ValueError("Exceeds the limit ...") from its
        // int-size limit on first use; Kotlin rejects the over-long version at parse time with
        // InvalidSpecifierException (a ValueError analogue). The message is not matched.
        @TestFactory
        fun containsOversizedVersionRaisesValueerror() = dyn("TestSpecifierSet.test_contains_oversized_version_raises_valueerror") {
            assertThrows(IllegalArgumentException::class.java) {
                SpecifierSet.parse(it.str("specifier")).contains("1.0")
            }
        }

        @TestFactory
        fun containsArbitraryEqualityContains() = dyn("TestSpecifierSet.test_contains_arbitrary_equality_contains") {
            assertEquals(it.bool("expected"), SpecifierSet.parse(it.str("specifier")).contains(it.str("version")))
        }

        @TestFactory
        fun containsArbitraryEqualityNormalization() = dyn("TestSpecifierSet.test_contains_arbitrary_equality_normalization") {
            val spec = SpecifierSet.parse(it.str("spec_str"), true)
            assertEquals(it.bool("expected"), spec.containsAny(item(it["version"])))
        }

        // test_specifiers_str_and_repr: str() half only.
        @TestFactory
        fun specifiersStr() = dyn("TestSpecifierSet.test_specifiers_str_and_repr") {
            assertEquals(it.str("expected"), SpecifierSet.parse(it.str("specifier")).toString())
        }

        @TestFactory
        fun specifiersHash() = dyn("TestSpecifierSet.test_specifiers_hash") {
            assertEquals(
                SpecifierSet.parse(it.str("specifier")).hashCode(),
                SpecifierSet.parse(it.str("specifier")).hashCode(),
            )
        }

        // Python `&` is `and`; `not result.prereleases` is translated as `!= true`.
        @TestFactory
        fun specifiersCombine() = dyn("TestSpecifierSet.test_specifiers_combine") {
            val left = it.str("left")
            val right = it.str("right")
            val expected = SpecifierSet.parse(it.str("expected"))

            assertEquals(expected, SpecifierSet.parse(left).and(SpecifierSet.parse(right)))
            assertEquals(expected, SpecifierSet.parse(left).and(right))

            for ((lp, rp) in listOf(true to null, false to null, null to true, null to false, true to true, false to false)) {
                val result = SpecifierSet.parse(left, lp).and(SpecifierSet.parse(right, rp))
                assertEquals(expected, result)
                val want = lp ?: rp!!
                if (want) assertEquals(true, result.prereleases) else assertNotEquals(true, result.prereleases)
            }

            for ((lp, rp) in listOf(true to false, false to true)) {
                val e = assertThrows(IllegalArgumentException::class.java) {
                    SpecifierSet.parse(left, lp).and(SpecifierSet.parse(right, rp))
                }
                assertTrue(
                    e.message!!.contains("Cannot combine SpecifierSets with True and False prerelease overrides."),
                    e.message,
                )
            }
        }

        @Test
        fun specifiersDuplicateNormalization() {
            val a = SpecifierSet.parse(">1.0.0,>1.0")
            val b = SpecifierSet.parse(">1.0")
            assertEquals(b, a)
            assertEquals(b.hashCode(), a.hashCode())
            assertEquals(b.toString(), a.toString())
        }

        @Test
        fun specifiersCombineDeduplicates() {
            val result = SpecifierSet.parse(">=1.0").and(SpecifierSet.parse(">=1.0,<5.0"))
            assertEquals("<5.0,>=1.0", result.toString())
        }

        // Python's SpecifierSet == Specifier / == str coerce the other side to a SpecifierSet;
        // in Kotlin that coercion is explicit (SpecifierSet.of / parse), so all five forms are these two.
        @TestFactory
        fun comparisonTrue() = dyn("TestSpecifierSet.test_comparison_true") {
            val o = op(it["op"])
            assertTrue(o(SpecifierSet.parse(it.str("left")), SpecifierSet.parse(it.str("right"))))
            assertTrue(o(SpecifierSet.parse(it.str("left")), SpecifierSet.of(listOf(Specifier.parse(it.str("right"))))))
            assertTrue(o(SpecifierSet.of(listOf(Specifier.parse(it.str("left")))), SpecifierSet.parse(it.str("right"))))
        }

        @TestFactory
        fun comparisonFalse() = dyn("TestSpecifierSet.test_comparison_false") {
            val o = op(it["op"])
            assertFalse(o(SpecifierSet.parse(it.str("left")), SpecifierSet.parse(it.str("right"))))
            assertFalse(o(SpecifierSet.parse(it.str("left")), SpecifierSet.of(listOf(Specifier.parse(it.str("right"))))))
            assertFalse(o(SpecifierSet.of(listOf(Specifier.parse(it.str("left")))), SpecifierSet.parse(it.str("right"))))
        }

        @TestFactory
        fun comparisonCanonicalizes() = dyn("TestSpecifierSet.test_comparison_canonicalizes") {
            assertEquals(SpecifierSet.parse(it.str("left")), SpecifierSet.parse(it.str("right")))
        }

        @Test
        fun comparisonNonSpecifier() {
            val ss: Any = SpecifierSet.parse("==1.0")
            assertNotEquals(ss, 12)
            assertFalse(ss.equals(12))
        }

        @TestFactory
        fun comparisonIgnoresLocal() = dyn("TestSpecifierSet.test_comparison_ignores_local") {
            assertEquals(it.bool("expected"), Version.parse(it.str("version")) in SpecifierSet.parse(it.str("specifier")))
        }

        @Test
        fun containsWithCompatibleOperator() {
            val combination = SpecifierSet.parse("~=1.18.0").and(SpecifierSet.parse("~=1.18"))
            assertFalse("1.19.5" in combination)
            assertTrue("1.18.0" in combination)
        }

        @TestFactory
        fun arbitraryEqualityIsIntersectionPreserving() =
            dyn("TestSpecifierSet.test_arbitrary_equality_is_intersection_preserving") {
                val spec1 = it.str("spec1")
                val spec2 = it.str("spec2")
                @Suppress("UNCHECKED_CAST")
                val input = it.list("input_versions") as List<String>
                val versions1 = Specifier.parse(spec1).filter(input).toSet()
                val versions2 = Specifier.parse(spec2).filter(input).toSet()
                val combined = SpecifierSet.parse("$spec1,$spec2").filter(input).toSet()
                assertEquals(combined, versions1 intersect versions2)
            }
    }

    @Nested
    inner class TestIsUnsatisfiable {
        @TestFactory
        fun unsatisfiable() = dyn("TestIsUnsatisfiable.test_unsatisfiable") {
            val ss = SpecifierSet.parse(it.str("spec_str"))
            assertTrue(ss.isUnsatisfiable(), "Expected unsatisfiable")
            assertEquals(emptyList<String>(), ss.filter(SAMPLE_VERSIONS, true), "isUnsatisfiable() but filter matched")
        }

        @TestFactory
        fun satisfiable() = dyn("TestIsUnsatisfiable.test_satisfiable") {
            val ss = SpecifierSet.parse(it.str("spec_str"))
            assertFalse(ss.isUnsatisfiable(), "Expected satisfiable")
            assertTrue(ss.filter(SAMPLE_VERSIONS, true).isNotEmpty(), "Expected filter to match at least one version")
        }

        // Cache assertions dropped.
        @Test
        fun resultIsCached() {
            val ss = SpecifierSet.parse(">=2.0,<1.0")
            assertTrue(ss.isUnsatisfiable())
            assertTrue(ss.isUnsatisfiable())
        }

        // Only the non-cache assertion of test_cache_reset_on_prereleases_change.
        @Test
        fun cacheResetOnPrereleasesChange() {
            assertFalse(SpecifierSet.parse(">=1.0,<2.0").isUnsatisfiable())
        }

        @TestFactory
        fun unsatisfiablePrereleasesFalse() = dyn("TestIsUnsatisfiable.test_unsatisfiable_prereleases_false") {
            val ss = SpecifierSet.parse(it.str("spec_str"), false)
            assertTrue(ss.isUnsatisfiable(), "Expected unsatisfiable")
            assertEquals(emptyList<String>(), ss.filter(SAMPLE_VERSIONS), "isUnsatisfiable() but filter matched")
        }

        @TestFactory
        fun satisfiablePrereleasesFalse() = dyn("TestIsUnsatisfiable.test_satisfiable_prereleases_false") {
            val ss = SpecifierSet.parse(it.str("spec_str"), false)
            assertFalse(ss.isUnsatisfiable(), "Expected satisfiable")
            assertTrue(ss.filter(SAMPLE_VERSIONS).isNotEmpty(), "Expected filter to match at least one version")
        }

        @Test
        fun andPreservesUnsatisfiable() {
            assertTrue(SpecifierSet.parse(">=2.0").and(SpecifierSet.parse("<1.0")).isUnsatisfiable())
        }

        @Test
        fun andSatisfiable() {
            assertFalse(SpecifierSet.parse(">=1.0").and(SpecifierSet.parse("<2.0")).isUnsatisfiable())
        }

        @TestFactory
        fun postReleaseBoundaryUnsatisfiable() = dyn("TestIsUnsatisfiable.test_post_release_boundary_unsatisfiable") {
            assertTrue(SpecifierSet.parse(it.str("spec_str")).isUnsatisfiable(), "Expected unsatisfiable")
        }

        @Test
        fun andReusesIntervalCache() {
            val s1 = SpecifierSet.parse(">=1.0")
            val s2 = SpecifierSet.parse("<2.0")
            assertFalse(s1.isUnsatisfiable())
            assertFalse(s2.isUnsatisfiable())
            assertFalse(s1.and(s2).isUnsatisfiable())
        }

        @Test
        fun canonicalizationKeepsRangeCache() {
            val ss = SpecifierSet.parse(">=1.0,<2.0,!=1.5")
            assertEquals(listOf("1.2"), ss.filter(listOf("1.2")))
            ss.toString()
            assertTrue(ss.contains("1.2"))
        }

        @Test
        fun canonicalizationKeepsUnsatisfiableCache() {
            val ss = SpecifierSet.parse(">=2.0,<1.0")
            assertTrue(ss.isUnsatisfiable())
            ss.toString()
            assertTrue(ss.isUnsatisfiable())
        }

        @TestFactory
        fun containsIdenticalAcrossCanonicalization() = dyn("TestIsUnsatisfiable.test_contains_identical_across_canonicalization") {
            val spec = it.str("spec")
            @Suppress("UNCHECKED_CAST")
            val versions = it.list("versions") as List<String>
            val before = versions.associateWith { v -> SpecifierSet.parse(spec).contains(v) }
            val ss = SpecifierSet.parse(spec)
            versions.forEach { v -> ss.contains(v) }
            ss.toString()
            val after = versions.associateWith { v -> ss.contains(v) }
            assertEquals(before, after)
        }
    }

    // Module-level upstream tests.

    @Test
    fun filterMultirangePep440PrereleaseAfterFinal() {
        val ss = SpecifierSet.parse("!=1.5")
        assertEquals(listOf("1.4"), ss.filter(listOf("1.4", "1.6a1")))
        assertEquals(listOf("1.4"), ss.filter(listOf("1.6a1", "1.4")))
    }

    @Test
    fun specifierFilterWithVersionIterableWarmsThenReusesCache() {
        val spec = Specifier.parse(">=1.5")
        assertTrue(spec.contains(Version.parse("2.0")))
        val items = listOf("1.0", "2.0", "3.0").map(Version::parse)
        assertEquals(listOf(Version.parse("2.0"), Version.parse("3.0")), spec.filterVersions(items))
    }

    @TestFactory
    fun boundaryClosureShortRelease() = dyn("test_boundary_closure_short_release") {
        val spec = Specifier.parse(it.str("spec_str"))
        val version = Version.parse(it.str("version_str"))
        val expected = it.bool("expected")
        assertEquals(expected, spec.contains(version))
        assertEquals(expected, spec.filterVersions(listOf(version), true) == listOf(version))
    }

    @Test
    fun filterArbitraryWithPrereleasesFalseSkipsPre() {
        assertEquals(emptyList<String>(), Specifier.parse("===1.0a1").filter(listOf("1.0a1", "0.9"), false))
    }

    @Test
    fun filterArbitraryConstructorPrereleasesFalseSkipsPre() {
        val spec = Specifier.parse("===1.0a1", false)
        assertEquals(emptyList<String>(), spec.filter(listOf("1.0a1")))
        assertFalse(spec.contains("1.0a1"))
    }

    @Test
    fun filterArbitraryUnparsableUsesKey() {
        val items = listOf(mapOf("v" to "wat"), mapOf("v" to "WAT"), mapOf("v" to "else"))
        val spec = Specifier.parse("===wat")
        assertEquals(listOf(mapOf("v" to "wat"), mapOf("v" to "WAT")), spec.filterBy(items, { x -> x.getValue("v") }))
    }

    @Test
    fun filterArbitraryPep440UnparsableBufferFlush() {
        assertEquals(listOf("wat", "wat"), Specifier.parse("===wat").filter(listOf("wat", "wat")))
    }

    @Test
    fun filterArbitraryPep440PreOnly() {
        assertEquals(listOf("1.0a1"), Specifier.parse("===1.0a1").filter(listOf("1.0a1")))
    }

    // test_specifierset_contains_reads_cached_bounds: behavioural half only.
    @Test
    fun specifiersetContainsReadsCachedBounds() {
        val ss = SpecifierSet.parse(">=1.0,!=1.5,<3.0")
        ss.filterVersions(listOf(Version.parse("2.0")))
        assertTrue(ss.contains("2.0"))
    }
}
