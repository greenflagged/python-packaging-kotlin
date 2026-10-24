package dev.greenflagged.pypackaging

import dev.greenflagged.pypackaging.internal.FULL_RANGE
import dev.greenflagged.pypackaging.internal.Interval
import dev.greenflagged.pypackaging.internal.SpecifierScanner
import dev.greenflagged.pypackaging.internal.VersionParser
import dev.greenflagged.pypackaging.internal.boundsForSpec
import dev.greenflagged.pypackaging.internal.coerceVersion
import dev.greenflagged.pypackaging.internal.filterByRanges
import dev.greenflagged.pypackaging.internal.matchesBoundsOnly
import dev.greenflagged.pypackaging.internal.pyLower
import dev.greenflagged.pypackaging.internal.pyRepr
import dev.greenflagged.pypackaging.internal.pyStrip
import dev.greenflagged.pypackaging.internal.resolvePrereleases
import dev.greenflagged.pypackaging.internal.skipPyWhitespace
import dev.greenflagged.pypackaging.internal.trimRelease
import java.util.function.Function

/**
 * A single [PEP 440 version specifier](https://peps.python.org/pep-0440/#version-specifiers),
 * such as `>=1.0`, `~=2.2`, `!=1.3.*` or `===foobar`.
 *
 * Immutable. A port of Python's `packaging.specifiers.Specifier` (packaging 26.3),
 * including its pre-release rules: with no explicit setting, [contains] accepts a
 * pre-release that falls inside the range, and the filter methods keep pre-releases
 * only when no final release matches.
 *
 * ```kotlin
 * val spec = Specifier.parse(">=1.0")
 * spec.contains("1.5")                 // true
 * spec.contains("1.5a1", false)        // false: pre-releases excluded
 * ```
 */
public class Specifier private constructor(
    /** The operator: one of `~=`, `==`, `!=`, `<=`, `>=`, `<`, `>`, `===`. */
    public val operator: String,
    /** The version part as written (whitespace removed), e.g. `1.0.*` or `1.0RC1`. */
    public val version: String,
    /**
     * The explicit pre-release setting this specifier was created with, or `null`
     * for the PEP 440 default. Compare [prereleases], which also reflects the default.
     */
    public val prereleasesOverride: Boolean?,
    private val specVersion: Version?,
) {

    /**
     * Whether this specifier admits pre-releases by default: [prereleasesOverride] if set;
     * otherwise `false` for `!=` and `==X.*`, else whether the specifier's own version is a
     * pre-release. `null` when that version is not a valid version (only possible for `===`).
     */
    public val prereleases: Boolean?
        get() {
            prereleasesOverride?.let { return it }
            if (operator == "!=") return false
            if (operator == "==" && version.endsWith(".*")) return false
            return specVersion?.isPrerelease
        }

    internal val ranges: List<Interval> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (operator == "===") FULL_RANGE else boundsForSpec(operator, version, specVersion!!)
    }

    // Python's `_canonical_spec`, which drives equality and hashing.
    private val canonical: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (operator == "===" || version.endsWith(".*")) {
            version
        } else {
            Version.canonicalize(specVersion!!, stripTrailingZero = operator != "~=")
        }
    }

    /** A copy of this specifier with a different pre-release setting. */
    public fun withPrereleases(prereleases: Boolean?): Specifier =
        Specifier(operator, version, prereleases, specVersion)

    /** Whether [version] satisfies this specifier, using the default pre-release rules. */
    public operator fun contains(version: Version): Boolean = containsItem(version, null)

    /**
     * Whether [version] satisfies this specifier. [prereleases] `true` or `false` includes or
     * excludes pre-releases; `null` uses [prereleasesOverride], then the default.
     */
    public fun contains(version: Version, prereleases: Boolean?): Boolean = containsItem(version, prereleases)

    /** Whether the version string [version] satisfies this specifier. Invalid versions never do, except under `===`. */
    public operator fun contains(version: String): Boolean = containsItem(version, null)

    /** Whether the version string [version] satisfies this specifier; see [contains]. */
    public fun contains(version: String, prereleases: Boolean?): Boolean = containsItem(version, prereleases)

    internal fun containsItem(item: Any, prereleases: Boolean?): Boolean {
        if (operator == "===") return filterItems(listOf(item), { it }, prereleases).isNotEmpty()
        val parsed = coerceVersion(item) ?: return false
        val pre = prereleases ?: resolvePrereleases(prereleasesOverride, this.prereleases)
        if (pre == false && parsed.isPrerelease) return false
        fastMatch(parsed)?.let { return it }
        return matchesBoundsOnly(ranges, parsed)
    }

    /**
     * Returns the version strings in [versions] that satisfy this specifier, in order.
     * Strings that are not valid versions are dropped (except under `===`). With
     * [prereleases] `null`, pre-releases are kept only if no final release matches.
     */
    @JvmOverloads
    public fun filter(versions: Iterable<String>, prereleases: Boolean? = null): List<String> =
        filterItems(versions, { it }, prereleases)

    /** Like [filter], for parsed versions. */
    @JvmOverloads
    public fun filterVersions(versions: Iterable<Version>, prereleases: Boolean? = null): List<Version> =
        filterItems(versions, { it }, prereleases)

    /**
     * Like [filter], for arbitrary items whose version string is given by [key]. A `null`
     * key result is treated as Python treats `None`: as the unparsable string `"None"`.
     */
    @JvmOverloads
    public fun <T> filterBy(items: Iterable<T>, key: Function<in T, String?>, prereleases: Boolean? = null): List<T> =
        filterItems(items, { key.apply(it) ?: "None" }, prereleases)

    internal fun <T> filterItems(items: Iterable<T>, key: (T) -> Any, prereleases: Boolean?): List<T> {
        val pre = prereleases ?: resolvePrereleases(prereleasesOverride, this.prereleases)
        if (operator == "===") {
            val specLower = pyLower(version)
            val matches = items.filter { pyLower(key(it).toString()) == specLower }
            return applyPrereleasesFilter(matches, key, pre)
        }
        return filterByRanges(ranges, items, key, pre)
    }

    // `_fast_match`: decides common cases without building ranges; null means undecided.
    internal fun fastMatch(parsed: Version): Boolean? {
        if (version.endsWith(".*") || parsed.localParts != null) return null
        val spec = specVersion ?: return null
        return when (operator) {
            ">=" -> parsed >= spec
            "<=" -> parsed <= spec
            "==" -> parsed == spec
            "!=" -> parsed != spec
            "<", ">" ->
                if (parsed.epoch != spec.epoch || trimRelease(parsed.release) != trimRelease(spec.release)) {
                    if (operator == "<") parsed < spec else parsed > spec
                } else {
                    null
                }
            else -> null
        }
    }

    /**
     * Equal when the operator matches and the versions are equivalent (`==1.0` equals
     * `==1.0.0`; `~=` and `.*` keep trailing zeros significant). The pre-release
     * setting is ignored, as in Python.
     */
    override fun equals(other: Any?): Boolean =
        other is Specifier && operator == other.operator && canonical == other.canonical

    override fun hashCode(): Int = operator.hashCode() * 31 + canonical.hashCode()

    /** The specifier as written, without whitespace, e.g. `>=1.0`. */
    override fun toString(): String = operator + version

    public companion object {
        /**
         * Parses a single specifier such as `>=1.0`.
         *
         * @param prereleases an explicit pre-release setting, or `null` for the default.
         * @throws InvalidSpecifierException if [spec] is not a valid specifier.
         * @throws LimitExceededException if it is longer than [Limits.MAX_SPECIFIER_LENGTH], or its
         *   version is longer than [Limits.MAX_VERSION_LENGTH].
         */
        @JvmStatic
        @JvmOverloads
        public fun parse(spec: String, prereleases: Boolean? = null): Specifier {
            checkLimit("Specifier", spec.length, Limits.MAX_SPECIFIER_LENGTH)
            val start = skipPyWhitespace(spec, 0)
            val end = SpecifierScanner.scan(spec, start)
            if (end < 0 || skipPyWhitespace(spec, end) != spec.length) {
                throw InvalidSpecifierException("Invalid specifier: ${pyRepr(spec)}")
            }
            val stripped = pyStrip(spec)
            val opLength = when {
                stripped.startsWith("===") -> 3
                stripped.startsWith("~=") || stripped.startsWith("==") || stripped.startsWith("!=") ||
                    stripped.startsWith("<=") || stripped.startsWith(">=") -> 2
                else -> 1
            }
            val operator = stripped.substring(0, opLength)
            val version = pyStrip(stripped.substring(opLength))
            val specVersion = VersionParser.parse(if (operator == "===") version else version.removeSuffix(".*"))
            if (specVersion == null && operator != "===") {
                // Only reachable through Python's Unicode case folding in the ~= branch
                // (e.g. U+017F in "post"). Python accepts the specifier and then fails an
                // internal assertion when it is used; rejecting it up front is safer.
                throw InvalidSpecifierException("Invalid specifier: ${pyRepr(spec)}")
            }
            return Specifier(operator, version, prereleases, specVersion)
        }

        /** Whether [spec] is a valid single specifier. */
        @JvmStatic
        public fun isValid(spec: String): Boolean = try {
            parse(spec)
            true
        } catch (_: InvalidSpecifierException) {
            false
        }
    }
}

/** `_apply_prereleases_filter`: applies the pre-release rule to already-matched items. */
internal fun <T> applyPrereleasesFilter(matches: Iterable<T>, key: (T) -> Any, prereleases: Boolean?): List<T> {
    if (prereleases == null) return pep440FilterPrereleases(matches, key)
    if (prereleases) return matches.toList()
    return matches.filter { item -> coerceVersion(key(item))?.isPrerelease != true }
}

/**
 * `_pep440_filter_prereleases`: keeps final releases; keeps pre-releases only if no final
 * release is present. Items that are not versions are kept, positioned as Python does.
 */
internal fun <T> pep440FilterPrereleases(items: Iterable<T>, key: (T) -> Any): List<T> {
    val out = ArrayList<T>()
    val allNonFinal = ArrayList<T>()
    val arbitrary = ArrayList<T>()
    var foundFinal = false
    for (item in items) {
        val parsed = coerceVersion(key(item))
        if (parsed == null) {
            if (foundFinal) {
                out.add(item)
            } else {
                arbitrary.add(item)
                allNonFinal.add(item)
            }
            continue
        }
        if (!parsed.isPrerelease) {
            if (!foundFinal) {
                out.addAll(arbitrary)
                foundFinal = true
            }
            out.add(item)
            continue
        }
        if (!foundFinal) allNonFinal.add(item)
    }
    if (!foundFinal) out.addAll(allNonFinal)
    return out
}
