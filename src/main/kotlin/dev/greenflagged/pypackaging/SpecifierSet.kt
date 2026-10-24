package dev.greenflagged.pypackaging

import dev.greenflagged.pypackaging.internal.Interval
import dev.greenflagged.pypackaging.internal.coerceVersion
import dev.greenflagged.pypackaging.internal.filterByRanges
import dev.greenflagged.pypackaging.internal.intersectSpecifierBounds
import dev.greenflagged.pypackaging.internal.matchesBoundsOnly
import dev.greenflagged.pypackaging.internal.pyCompare
import dev.greenflagged.pypackaging.internal.pyLower
import dev.greenflagged.pypackaging.internal.pyStrip
import dev.greenflagged.pypackaging.internal.rangesArePrereleaseOnly
import java.util.function.Function

/**
 * A comma-separated set of PEP 440 specifiers, such as `>=1.0,!=1.3.*,<2`. A version
 * matches when it matches every specifier. An empty set matches every version.
 *
 * Immutable. A port of Python's `packaging.specifiers.SpecifierSet` (packaging 26.3).
 *
 * ```kotlin
 * val set = SpecifierSet.parse(">=1.0,!=1.3.*,<2")
 * set.contains("1.4")          // true
 * set.contains("1.3.1")        // false
 * set.filter(listOf("0.9", "1.2", "2.0"))   // ["1.2"]
 * ```
 *
 * Unlike Python, iteration and [size] always reflect the specifiers in the order they
 * were given; Python reorders and deduplicates them in place the first time the set is
 * converted to a string or compared.
 */
public class SpecifierSet private constructor(
    private val specs: List<Specifier>,
    private val hasArbitrary: Boolean,
    /**
     * The explicit pre-release setting this set was created with, or `null` for the
     * default. Compare [prereleases].
     */
    public val prereleasesOverride: Boolean?,
) : Iterable<Specifier> {

    /**
     * Whether this set admits pre-releases by default: [prereleasesOverride] if set;
     * otherwise `true` if any member specifier admits them, else `null`.
     */
    public val prereleases: Boolean?
        get() {
            prereleasesOverride?.let { return it }
            if (specs.isEmpty()) return null
            if (specs.any { it.prereleases == true }) return true
            return null
        }

    /** The number of specifiers, counting duplicates. */
    public val size: Int get() = specs.size

    /** Whether there are no specifiers, so every version matches. */
    public fun isEmpty(): Boolean = specs.isEmpty()

    override fun iterator(): Iterator<Specifier> = specs.iterator()

    /** The specifiers sorted by their string form, with equivalent ones removed. */
    private val canonicalSpecs: List<Specifier> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (specs.size <= 1) {
            specs
        } else {
            val unique = LinkedHashSet<Specifier>()
            specs.sortedWith { a, b -> pyCompare(a.toString(), b.toString()) }.forEach { unique.add(it) }
            unique.toList()
        }
    }

    private val ranges: List<Interval> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        intersectSpecifierBounds(specs.map { it.ranges })
    }

    /** A copy of this set with a different pre-release setting. */
    public fun withPrereleases(prereleases: Boolean?): SpecifierSet = SpecifierSet(specs, hasArbitrary, prereleases)

    /**
     * The intersection of this set and [other]: a version must satisfy both.
     *
     * @throws IllegalArgumentException if one set explicitly allows pre-releases and the other
     *   explicitly forbids them.
     */
    public fun and(other: SpecifierSet): SpecifierSet {
        val pre = when {
            prereleasesOverride == null || prereleasesOverride == other.prereleasesOverride -> other.prereleasesOverride
            other.prereleasesOverride == null -> prereleasesOverride
            else -> throw IllegalArgumentException(
                "Cannot combine SpecifierSets with True and False prerelease overrides.",
            )
        }
        return SpecifierSet(specs + other.specs, hasArbitrary || other.hasArbitrary, pre)
    }

    /** The intersection of this set and the specifier set string [other]. */
    public fun and(other: String): SpecifierSet = and(parse(other))

    /**
     * Whether no version can satisfy this set, e.g. `>2,<1`, or `==1.0a1` when
     * pre-releases are forbidden.
     */
    public fun isUnsatisfiable(): Boolean {
        if (specs.isEmpty()) return false
        var result = ranges.isEmpty()
        if (!result) result = arbitraryUnsatisfiable()
        if (!result && prereleases == false) result = rangesArePrereleaseOnly(ranges)
        return result
    }

    private fun arbitraryUnsatisfiable(): Boolean {
        val arbitrary = specs.filter { it.operator == "===" }
        if (arbitrary.isEmpty()) return false
        val first = pyLower(arbitrary[0].version)
        if (arbitrary.drop(1).any { pyLower(it.version) != first }) return true
        val candidate = coerceVersion(arbitrary[0].version)
        if (prereleases == false && candidate != null && candidate.isPrerelease) return true
        val standard = specs.filter { it.operator != "===" }
        if (standard.isEmpty()) return false
        if (candidate == null) return true
        return !standard.all { it.containsItem(candidate, null) }
    }

    /** Whether [version] satisfies every specifier, using the default pre-release rules. */
    public operator fun contains(version: Version): Boolean = containsItem(version, null, null)

    /** Whether the version string [version] satisfies every specifier. */
    public operator fun contains(version: String): Boolean = containsItem(version, null, null)

    /**
     * Whether [version] satisfies every specifier.
     *
     * @param prereleases `true` or `false` to include or exclude pre-releases; `null` for
     *   [prereleasesOverride], then the default.
     * @param installed `true` to accept [version] even if it is a pre-release, as pip does
     *   for an already-installed version.
     */
    @JvmOverloads
    public fun contains(version: Version, prereleases: Boolean?, installed: Boolean? = null): Boolean =
        containsItem(version, prereleases, installed)

    /** Whether the version string [version] satisfies every specifier; see [contains]. */
    @JvmOverloads
    public fun contains(version: String, prereleases: Boolean?, installed: Boolean? = null): Boolean =
        containsItem(version, prereleases, installed)

    internal fun containsItem(item: Any, prereleases: Boolean?, installed: Boolean?): Boolean {
        var pre = prereleases
        val version = coerceVersion(item)
        if (version != null && installed == true && version.isPrerelease) pre = true
        val checkItem: Any = if (version == null || (hasArbitrary && item !is Version)) item else version
        if (version != null && !hasArbitrary && version.localParts == null && specs.isNotEmpty()) {
            if (version.isPrerelease && (pre == false || (pre == null && prereleasesOverride == false))) return false
            var undecided = false
            for (spec in specs) {
                val match = spec.fastMatch(version)
                if (match == null) {
                    undecided = true
                    break
                }
                if (!match) return false
            }
            if (!undecided) return true
            return matchesBoundsOnly(ranges, version)
        }
        return filterItems(listOf(checkItem), { it }, pre).isNotEmpty()
    }

    /**
     * Returns the version strings in [versions] that satisfy every specifier, in order.
     * Strings that are not valid versions are dropped, except when the set is empty or
     * uses `===`. With [prereleases] `null`, pre-releases are kept only if no final
     * release matches.
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
        var pre = prereleases
        if (pre == null && this.prereleases != null) pre = this.prereleases
        if (specs.isEmpty()) return applyPrereleasesFilter(items, key, pre)
        if (hasArbitrary) {
            val matches = items.filter { item -> specs.all { it.containsItem(key(item), true) } }
            return applyPrereleasesFilter(matches, key, pre)
        }
        return filterByRanges(ranges, items, key, pre)
    }

    /**
     * Equal when both sets contain equivalent specifiers, ignoring order, duplicates and
     * pre-release settings: `>=1.0,<2` equals `<2.0,>=1`.
     */
    override fun equals(other: Any?): Boolean = other is SpecifierSet && canonicalSpecs == other.canonicalSpecs

    override fun hashCode(): Int = canonicalSpecs.hashCode()

    /** The specifiers sorted, deduplicated and comma-joined, e.g. `<2,>=1.0`. */
    override fun toString(): String = canonicalSpecs.joinToString(",")

    public companion object {
        /** The empty set, which matches every version. */
        @JvmField
        public val EMPTY: SpecifierSet = SpecifierSet(emptyList(), false, null)

        /**
         * Parses a comma-separated specifier set. Empty items and surrounding whitespace are
         * ignored, so `""` is the empty set.
         *
         * @throws InvalidSpecifierException if any item is invalid, or [specifiers] is longer
         *   than [Limits.MAX_SPECIFIER_LENGTH].
         */
        @JvmStatic
        @JvmOverloads
        public fun parse(specifiers: String, prereleases: Boolean? = null): SpecifierSet {
            if (specifiers.length > Limits.MAX_SPECIFIER_LENGTH) {
                throw InvalidSpecifierException(
                    "Invalid specifier: longer than ${Limits.MAX_SPECIFIER_LENGTH} characters",
                )
            }
            val specs = specifiers.split(',').map(::pyStrip).filter { it.isNotEmpty() }.map { Specifier.parse(it) }
            return SpecifierSet(specs, "===" in specifiers, prereleases)
        }

        /** A set of the given specifiers. */
        @JvmStatic
        @JvmOverloads
        public fun of(specifiers: Iterable<Specifier>, prereleases: Boolean? = null): SpecifierSet {
            val specs = specifiers.toList()
            return SpecifierSet(specs, specs.any { "===" in it.toString() }, prereleases)
        }

        /** Whether [specifiers] is a valid specifier set. */
        @JvmStatic
        public fun isValid(specifiers: String): Boolean = try {
            parse(specifiers)
            true
        } catch (_: InvalidSpecifierException) {
            false
        }
    }
}
