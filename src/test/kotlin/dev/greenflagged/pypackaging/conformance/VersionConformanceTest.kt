package dev.greenflagged.pypackaging.conformance

import dev.greenflagged.pypackaging.InvalidVersionException
import dev.greenflagged.pypackaging.LimitExceededException
import dev.greenflagged.pypackaging.PreRelease
import dev.greenflagged.pypackaging.PreReleaseKind
import dev.greenflagged.pypackaging.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.Executable
import java.math.BigInteger

/*
 * Conformance tests ported from pypa/packaging 26.3:
 *   https://github.com/pypa/packaging/blob/26.3/tests/test_version.py
 * Test vectors and test logic are from pypa/packaging (Apache-2.0 OR BSD-2-Clause,
 * Copyright (c) Donald Stufft and individual contributors). See NOTICE.
 *
 * Mapping: Python `replace(v, ...)` / `__replace__` -> `v.toBuilder()...build()`;
 * `Version.from_parts(...)` -> `Version.builder()...build()`. Python's `epoch=None` and
 * `release=None` mean "the default" (0 and (0,)), which the builder spells `epoch(0)` and
 * `release(0)` since it has no nullable epoch/release setters. A pre letter such as
 * "Alpha" is normalized with Python's `normalize_pre` table ([preKind]) before reaching
 * the typed `PreReleaseKind` API.
 *
 * Not ported:
 *   TestVersion.test_match_args — `__match_args__` / `_str` (Python-only).
 *   TestVersion.test_non_string_versions_raise_invalid_version — non-str inputs; Kotlin's type system prevents them.
 *   TestVersion.test_version_str_repr — `repr()` half only; the `str()` half is ported as versionStr.
 *   TestVersion.test_dunder_op_returns_notimplemented — NotImplemented with non-packaging types.
 *   TestVersion.test_compare_other — NotImplemented/comparison with non-packaging types.
 *   TestVersion.test_base_version_notimplemented_with_non_base_version — `_BaseVersion` subclass + NotImplemented.
 *   TestVersion.test_base_version_hash — `_BaseVersion` subclass (internal).
 *   TestVersion.test_base_version_ne_with_base_version — `_BaseVersion` subclass (internal).
 *   TestVersion.test_version_compare_with_base_version_subclass — `_BaseVersion` subclass (internal).
 *   TestVersion.test_replace_returns_same_instance_when_unchanged — object identity (`is`) is a CPython
 *     allocation optimisation; the builder always builds a fresh instance. Equality/str are already covered.
 *   TestVersion.test_replace_invalid_epoch_type / _post_type / _dev_type — str passed for int; type system prevents.
 *   TestVersion.test_replace_invalid_pre_type — ("x", 1): an unknown letter can't be expressed as a PreReleaseKind.
 *   TestVersion.test_replace_invalid_pre_format — non-2-tuple `pre`; type system prevents.
 *   test_from_parts_rejects_non_str_pre_letter — int pre letter; type system prevents.
 *   test_deprecated__version — deprecated `_version`.
 *   test_hatchling_usage__version — hatchling shim over `_version`.
 *   test_pickle_roundtrip, test_pickle_old_format_loads, test_pickle_old_format_re_pickled_is_clean,
 *   test_pickle_26_0_slots_format_loads, test_pickle_26_2_tuple_getstate_loads,
 *   test_pickle_setstate_rejects_invalid_state — pickling.
 *   test_structures_shim_repr — `_structures`.
 *   test_trimmed_release_hash_from_unhashed_version / _prehashed_version — internal `_TrimmedRelease` hash cache.
 *
 * Adapted:
 *   TestVersion.test_oversized_version_raises_valueerror — Python raises ValueError from its int-size
 *     limit, not InvalidVersion; this port throws LimitExceededException (> Limits.MAX_VERSION_LENGTH),
 *     which is likewise not an InvalidVersionException.
 *   Cache-warming tests (hash_with_warm_key_cache, gt_with_cached_other, ne/le_with_uncached_keys) keep
 *     their comparison assertions; there is no cache to warm.
 */
class VersionConformanceTest {
    private fun v(s: String) = Version.parse(s)
    private fun big(n: Long) = BigInteger.valueOf(n)

    private fun factory(test: String, body: (Case) -> Unit): List<DynamicTest> = PypaFixtures.dynamic("test_version", test, body)

    /** Python's `normalize_pre`, then the typed kind. */
    private fun preKind(letter: String): PreReleaseKind {
        val l = letter.lowercase()
        val n = mapOf("alpha" to "a", "beta" to "b", "c" to "rc", "pre" to "rc", "preview" to "rc")[l] ?: l
        return PreReleaseKind.entries.single { it.label == n }
    }

    private fun pre(letter: String, n: Long) = PreRelease(preKind(letter), big(n))

    private fun assertInvalid(match: String, block: () -> Unit) {
        val e = assertThrows(InvalidVersionException::class.java, Executable(block))
        assertTrue(e.message!!.contains(match), "message ${e.message} should contain '$match'")
    }

    // ---- module-level -------------------------------------------------------------------

    @Test
    fun parse() {
        assertEquals(Version::class.java, Version.parse("1.0")::class.java)
    }

    @Test
    fun parseRaises() {
        assertThrows(InvalidVersionException::class.java) { Version.parse("lolwat") }
    }

    // ---- parsing ------------------------------------------------------------------------

    @TestFactory
    fun validVersions() = factory("TestVersion.test_valid_versions") { Version.parse(it.str("version")) }

    @TestFactory
    fun invalidVersions() = factory("TestVersion.test_invalid_versions") {
        assertThrows(InvalidVersionException::class.java) { Version.parse(it.str("version")) }
        assertNull(Version.parseOrNull(it.str("version")))
    }

    // Adapted: Python raises ValueError("Exceeds the limit ...") from its int-size limit; see header.
    @TestFactory
    fun oversizedVersionRaisesValueerror() = factory("TestVersion.test_oversized_version_raises_valueerror") {
        assertThrows(LimitExceededException::class.java) { Version.parse(it.str("version")) }
    }

    @TestFactory
    fun normalizedVersions() = factory("TestVersion.test_normalized_versions") {
        assertEquals(it.str("normalized"), v(it.str("version")).toString())
    }

    // test_version_str_repr (str() half)
    @TestFactory
    fun versionStr() = factory("TestVersion.test_version_str_repr") {
        assertEquals(it.str("expected"), v(it.str("version")).toString())
    }

    @Test
    fun versionRcAndCEquals() {
        assertEquals(v("1.0rc1"), v("1.0c1"))
    }

    // ---- hashing ------------------------------------------------------------------------

    @TestFactory
    fun versionHash() = factory("TestVersion.test_version_hash") {
        assertEquals(v(it.str("version")).hashCode(), v(it.str("version")).hashCode())
    }

    @Test
    fun versionHashWithWarmKeyCache() {
        val x = v("1.0")
        assertTrue(x > v("0.9"))
        assertEquals(x.hashCode(), v("1.0").hashCode())
    }

    // ---- properties ---------------------------------------------------------------------

    @TestFactory
    fun versionPublic() = factory("TestVersion.test_version_public") {
        assertEquals(it.str("public"), v(it.str("version")).public)
    }

    @TestFactory
    fun versionBaseVersion() = factory("TestVersion.test_version_base_version") {
        assertEquals(it.str("base_version"), v(it.str("version")).baseVersion)
    }

    @TestFactory
    fun versionEpoch() = factory("TestVersion.test_version_epoch") {
        assertEquals(bigOf(it["epoch"]), v(it.str("version")).epoch)
    }

    @TestFactory
    fun versionRelease() = factory("TestVersion.test_version_release") {
        assertEquals(it.list("release").map(::bigOf), v(it.str("version")).release)
    }

    @TestFactory
    fun versionLocal() = factory("TestVersion.test_version_local") {
        assertEquals(it.strOrNull("local"), v(it.str("version")).local)
    }

    @TestFactory
    fun versionPre() = factory("TestVersion.test_version_pre") {
        val expected = it["pre"] as List<*>?
        val actual = v(it.str("version")).pre
        if (expected == null) {
            assertNull(actual)
        } else {
            assertEquals(expected[0], actual?.kind?.label)
            assertEquals(bigOf(expected[1]), actual?.number)
        }
    }

    @TestFactory
    fun versionIsPrerelease() = factory("TestVersion.test_version_is_prerelease") {
        assertEquals(it.bool("expected"), v(it.str("version")).isPrerelease)
    }

    @TestFactory
    fun versionDev() = factory("TestVersion.test_version_dev") {
        assertEquals(it["dev"]?.let(::bigOf), v(it.str("version")).dev)
    }

    @TestFactory
    fun versionIsDevrelease() = factory("TestVersion.test_version_is_devrelease") {
        assertEquals(it.bool("expected"), v(it.str("version")).isDevrelease)
    }

    @TestFactory
    fun versionPost() = factory("TestVersion.test_version_post") {
        assertEquals(it["post"]?.let(::bigOf), v(it.str("version")).post)
    }

    @TestFactory
    fun versionIsPostrelease() = factory("TestVersion.test_version_is_postrelease") {
        assertEquals(it.bool("expected"), v(it.str("version")).isPostrelease)
    }

    @Test
    fun majorVersion() {
        assertEquals(big(2), v("2.1.0").major)
    }

    @Test
    fun minorVersion() {
        assertEquals(big(1), v("2.1.0").minor)
        assertEquals(big(0), v("2").minor)
    }

    @Test
    fun microVersion() {
        assertEquals(big(3), v("2.1.3").micro)
        assertEquals(big(0), v("2.1").micro)
        assertEquals(big(0), v("2").micro)
    }

    // ---- comparison ---------------------------------------------------------------------

    private fun applyOp(op: Any?, l: Version, r: Version): Boolean = when ((op as PyRepr).repr) {
        "<built-in function lt>" -> l < r
        "<built-in function le>" -> l <= r
        "<built-in function eq>" -> l == r
        "<built-in function ne>" -> l != r
        "<built-in function ge>" -> l >= r
        "<built-in function gt>" -> l > r
        else -> error("unknown op ${op.repr}")
    }

    // ~25k cases each: one @Test that loops all cases and reports the failures together.
    private fun runComparisons(test: String, expected: Boolean) {
        val cases = PypaFixtures.cases("test_version", test)
        val failures = cases.filter { c ->
            applyOp(c["op"], v(c.str("left")), v(c.str("right"))) != expected
        }.map { c -> "${c.str("left")} ${(c["op"] as PyRepr).repr} ${c.str("right")}" }
        assertTrue(
            failures.isEmpty(),
            "${failures.size}/${cases.size} cases were not $expected, e.g.:\n" + failures.take(20).joinToString("\n"),
        )
    }

    @Test
    fun comparisonTrue() = runComparisons("TestVersion.test_comparison_true", true)

    @Test
    fun comparisonFalse() = runComparisons("TestVersion.test_comparison_false", false)

    @Test
    fun gtWithCachedOther() {
        val other = v("1.0")
        assertTrue(other < v("2.0"))
        assertTrue(v("2.0") > other)
    }

    @Test
    fun versionNeWithUncachedKeys() {
        val v1 = v("1.0")
        val v2 = v("2.0")
        assertTrue(v1 != v2)
        val v3 = v("1.5")
        assertTrue(v1 != v3)
        val v4 = v("1.2")
        assertTrue(v4 != v3)
    }

    @Test
    fun versionLeWithUncachedKeys() {
        val v1 = v("1.0")
        val v2 = v("2.0")
        assertTrue(v1 <= v2)
        val v3 = v("1.5")
        assertTrue(v1 <= v3)
    }

    // ---- replace() -> toBuilder() --------------------------------------------------------

    @Test
    fun replaceNoArgs() {
        val x = v("1.2.3a1.post2.dev3+local")
        val replaced = x.toBuilder().build()
        assertEquals(x, replaced)
        assertEquals(x.toString(), replaced.toString())
    }

    @Test
    fun replaceEpoch() {
        val x = v("1.2.3")
        assertEquals("2!1.2.3", x.toBuilder().epoch(2).build().toString())
        assertEquals(big(0), x.toBuilder().epoch(0).build().epoch)
        val withEpoch = v("1!1.2.3")
        assertEquals("2!1.2.3", withEpoch.toBuilder().epoch(2).build().toString())
        assertEquals("1.2.3", withEpoch.toBuilder().epoch(0).build().toString()) // epoch=None
    }

    @Test
    fun replaceReleaseTuple() {
        val x = v("1.2.3")
        assertEquals("2.0.0", x.toBuilder().release(2, 0, 0).build().toString())
        assertEquals("1", x.toBuilder().release(1).build().toString())
        assertEquals("1.2.3.4.5", x.toBuilder().release(1, 2, 3, 4, 5).build().toString())
    }

    @Test
    fun replaceReleaseNone() {
        // release=None means (0,), the builder default.
        assertEquals("0", v("1.2.3").toBuilder().release(0).build().toString())
        assertEquals("0", Version.builder().build().toString())
    }

    @Test
    fun replacePreAlpha() {
        val x = v("1.2.3")
        assertEquals("1.2.3a1", x.toBuilder().pre(pre("a", 1)).build().toString())
        assertEquals("1.2.3a0", x.toBuilder().pre(pre("A", 0)).build().toString())
        assertEquals("1.2.3a2", x.toBuilder().pre(pre("Alpha", 2)).build().toString())
    }

    @Test
    fun replacePreAlphaNone() {
        assertEquals("1.2.3", v("1.2.3a1").toBuilder().pre(null).build().toString())
    }

    @Test
    fun replacePreBeta() {
        val x = v("1.2.3")
        assertEquals("1.2.3b1", x.toBuilder().pre(pre("b", 1)).build().toString())
        assertEquals("1.2.3b0", x.toBuilder().pre(pre("b", 0)).build().toString())
        assertEquals("1.2.3b2", x.toBuilder().pre(pre("bEta", 2)).build().toString())
    }

    @Test
    fun replacePreBetaNone() {
        assertEquals("1.2.3", v("1.2.3b1").toBuilder().pre(null).build().toString())
    }

    @Test
    fun replacePreRc() {
        val x = v("1.2.3")
        assertEquals("1.2.3rc1", x.toBuilder().pre(pre("rc", 1)).build().toString())
        assertEquals("1.2.3rc0", x.toBuilder().pre(pre("rc", 0)).build().toString())
    }

    @Test
    fun replacePreRcNone() {
        assertEquals("1.2.3", v("1.2.3rc1").toBuilder().pre(null).build().toString())
    }

    @Test
    fun replacePost() {
        val x = v("1.2.3")
        assertEquals("1.2.3.post1", x.toBuilder().post(1).build().toString())
        assertEquals("1.2.3.post0", x.toBuilder().post(0).build().toString())
    }

    @Test
    fun replacePostNone() {
        assertEquals("1.2.3", v("1.2.3.post1").toBuilder().post(null).build().toString())
    }

    @Test
    fun replaceDev() {
        val x = v("1.2.3")
        assertEquals("1.2.3.dev1", x.toBuilder().dev(1).build().toString())
        assertEquals("1.2.3.dev0", x.toBuilder().dev(0).build().toString())
    }

    @Test
    fun replaceDevNone() {
        assertEquals("1.2.3", v("1.2.3.dev1").toBuilder().dev(null).build().toString())
    }

    @Test
    fun replaceLocalString() {
        val x = v("1.2.3")
        assertEquals("1.2.3+abc", x.toBuilder().local("abc").build().toString())
        assertEquals("1.2.3+abc.123", x.toBuilder().local("abc.123").build().toString())
        assertEquals("1.2.3+abc.123", x.toBuilder().local("abc-123").build().toString())
    }

    @Test
    fun replaceLocalNone() {
        assertEquals("1.2.3", v("1.2.3+local").toBuilder().local(null).build().toString())
    }

    @Test
    fun replaceMultipleComponents() {
        val x = v("1.2.3")
        assertEquals("1.2.3a1.post1", x.toBuilder().pre(pre("a", 1)).post(1).build().toString())
        assertEquals("2.0.0b2.dev1", x.toBuilder().release(2, 0, 0).pre(pre("b", 2)).dev(1).build().toString())
        assertEquals("1!3.0+abc", x.toBuilder().epoch(1).release(3, 0).local("abc").build().toString())
    }

    @Test
    fun replaceClearAllOptional() {
        val x = v("1!1.2.3a1.post2.dev3+local")
        val cleared = x.toBuilder().epoch(0).pre(null).post(null).dev(null).local(null).build()
        assertEquals("1.2.3", cleared.toString())
    }

    @Test
    fun replacePreservesComparison() {
        val v1 = v("1.2.3")
        val v2 = v("1.2.4")
        val v1New = v1.toBuilder().release(1, 2, 4).build()
        assertEquals(v2, v1New)
        assertTrue(v1 < v2)
        assertTrue(v1New >= v2)
    }

    @Test
    fun replacePreservesHash() {
        val v1 = v("1.2.3")
        val v2 = v1.toBuilder().release(1, 2, 3).build()
        assertEquals(v1.hashCode(), v2.hashCode())
        val v3 = v1.toBuilder().release(2, 0, 0).build()
        assertNotEquals(v1.hashCode(), v3.hashCode())
    }

    @Test
    fun replaceChangePreType() {
        val x = v("1.2.3a1")
        assertEquals("1.2.3b2", x.toBuilder().pre(pre("b", 2)).build().toString())
        assertEquals("1.2.3rc1", x.toBuilder().pre(pre("rc", 1)).build().toString())
        assertEquals("1.2.3a0", v("1.2.3rc5").toBuilder().pre(pre("a", 0)).build().toString())
    }

    @Test
    fun replaceInvalidEpochNegative() {
        assertInvalid("epoch must be non-negative") { v("1.2.3").toBuilder().epoch(-1).build() }
    }

    @Test
    fun replaceInvalidReleaseEmpty() {
        assertInvalid("release must be a non-empty tuple") { v("1.2.3").toBuilder().release().build() }
    }

    @Test
    fun replaceInvalidReleaseTupleContent() {
        assertInvalid("release must be a non-empty tuple of non-negative") {
            v("1.2.3").toBuilder().release(1, -2, 3).build()
        }
    }

    @Test
    fun replaceInvalidPreNegative() {
        assertInvalid("pre must be a tuple") { v("1.2.3").toBuilder().pre(pre("a", -1)).build() }
    }

    @Test
    fun replaceInvalidPostNegative() {
        assertInvalid("post must be non-negative") { v("1.2.3").toBuilder().post(-1).build() }
    }

    @Test
    fun replaceInvalidDevNegative() {
        assertInvalid("dev must be non-negative") { v("1.2.3").toBuilder().dev(-1).build() }
    }

    @Test
    fun replaceInvalidLocalString() {
        assertInvalid("local must be a valid version string") { v("1.2.3").toBuilder().local("abc+123").build() }
        assertInvalid("local must be a valid version string") { v("1.2.3").toBuilder().local("+abc").build() }
    }

    // ---- from_parts() -> builder() -------------------------------------------------------

    @TestFactory
    fun fromParts() = factory("test_from_parts") { case ->
        @Suppress("UNCHECKED_CAST")
        val args = case["args"] as Map<String, Any?>
        val b = Version.builder()
        for ((k, value) in args) {
            when (k) {
                "release" -> b.release((value as List<*>).map(::bigOf))
                "epoch" -> b.epoch(value?.let(::bigOf) ?: BigInteger.ZERO) // epoch=None -> default 0
                "pre" -> b.pre((value as List<*>?)?.let { PreRelease(preKind(it[0] as String), bigOf(it[1])) })
                "post" -> b.post(value?.let(::bigOf))
                "dev" -> b.dev(value?.let(::bigOf))
                "local" -> b.local(value as String?)
                else -> error("unknown from_parts arg $k")
            }
        }
        assertEquals(v(case.str("string")), b.build())
    }

    // Sanity check that the comparison helper agrees with plain assertions (guards the op mapping).
    @Test
    fun comparisonOpMappingCoversAllOps() {
        val ops = (PypaFixtures.cases("test_version", "TestVersion.test_comparison_true") +
            PypaFixtures.cases("test_version", "TestVersion.test_comparison_false"))
            .map { (it["op"] as PyRepr).repr }.toSet()
        assertEquals(6, ops.size, "ops: $ops")
        assertFalse(applyOp(PyRepr("<built-in function lt>"), v("1.0"), v("1.0")))
    }
}
