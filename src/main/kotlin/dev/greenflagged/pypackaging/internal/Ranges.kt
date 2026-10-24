package dev.greenflagged.pypackaging.internal

import dev.greenflagged.pypackaging.CmpKey
import dev.greenflagged.pypackaging.PreRelease
import dev.greenflagged.pypackaging.Version
import java.math.BigInteger

/*
 * A port of packaging 26.3's `_ranges.py`: each specifier becomes a list of version
 * intervals, a specifier set is their intersection, and membership is a bounds check.
 *
 * Python compares `Version` and `BoundaryVersion` objects with each other through
 * reflected operators and `functools.total_ordering`. The functions [ptLt], [ptGt],
 * [ptEq] and [ptGe] spell those semantics out case by case so this port gives the
 * same answers, including the asymmetric ones.
 */

internal enum class BoundaryKind { AFTER_LOCALS, AFTER_POSTS }

internal fun trimRelease(release: List<BigInteger>): List<BigInteger> {
    var end = release.size
    while (end > 1 && release[end - 1].signum() == 0) end--
    return if (end == release.size) release else release.subList(0, end)
}

/**
 * Whether [other] has [version]'s epoch, release (ignoring trailing zeros) and
 * pre-release: the shared prefix of `_is_family` and the `_make_*` closures.
 */
private fun sameReleaseAndPre(version: Version, trimmed: List<BigInteger>, other: Version): Boolean {
    if (other.epoch != version.epoch) return false
    val rel = other.release
    if (rel.size < trimmed.size) return false
    for (k in trimmed.indices) if (rel[k] != trimmed[k]) return false
    for (k in trimmed.size until rel.size) if (rel[k].signum() != 0) return false
    return other.pre == version.pre
}

/** A point just past every local (or post) release of [version]. */
internal class BoundaryVersion(val version: Version, val kind: BoundaryKind) {
    private val trimmed = trimRelease(version.release)

    // Order key: version's key, but with the post fields forced to (1, +inf) for AFTER_POSTS,
    // and the local segment ignored (Python appends +inf in its place).
    private val key: CmpKey = CmpKey.of(version).let { k ->
        CmpKey(k.epoch, k.release, k.preRank, k.preN, k.postRank, k.postN, k.devRank, k.devN, null)
    }

    fun isFamily(other: Version): Boolean {
        if (!sameReleaseAndPre(version, trimmed, other)) return false
        if (kind == BoundaryKind.AFTER_LOCALS) return other.post == version.post && other.dev == version.dev
        return other.dev == version.dev || other.post != null
    }

    fun compareTo(other: BoundaryVersion): Int {
        var c = key.epoch.compareTo(other.key.epoch)
        if (c != 0) return c
        c = CmpKey.compareReleases(key.release, other.key.release)
        if (c != 0) return c
        c = key.preRank.compareTo(other.key.preRank)
        if (c != 0) return c
        c = key.preN.compareTo(other.key.preN)
        if (c != 0) return c
        val aPosts = kind == BoundaryKind.AFTER_POSTS
        val bPosts = other.kind == BoundaryKind.AFTER_POSTS
        val aPostRank = if (aPosts) 1 else key.postRank
        val bPostRank = if (bPosts) 1 else other.key.postRank
        c = aPostRank.compareTo(bPostRank)
        if (c != 0) return c
        c = when {
            aPosts && bPosts -> 0
            aPosts -> 1
            bPosts -> -1
            else -> key.postN.compareTo(other.key.postN)
        }
        if (c != 0) return c
        val aDevRank = if (aPosts) 1 else key.devRank
        val bDevRank = if (bPosts) 1 else other.key.devRank
        c = aDevRank.compareTo(bDevRank)
        if (c != 0) return c
        val aDevN = if (aPosts) BigInteger.ZERO else key.devN
        val bDevN = if (bPosts) BigInteger.ZERO else other.key.devN
        return aDevN.compareTo(bDevN)
    }

    /** `BoundaryVersion.__lt__(Version)`. */
    fun ltVersion(other: Version): Boolean {
        if (version >= other) return false
        return !isFamily(other)
    }

    /** `BoundaryVersion.__gt__(Version)`. */
    fun gtVersion(other: Version): Boolean {
        if (version >= other) return true
        return isFamily(other)
    }
}

// A bound's point: null (unbounded), a Version, or a BoundaryVersion.

internal fun ptLt(a: Any?, b: Any?): Boolean = when {
    a is Version && b is Version -> a < b
    a is BoundaryVersion && b is BoundaryVersion -> a.compareTo(b) < 0
    a is BoundaryVersion && b is Version -> a.ltVersion(b)
    a is Version && b is BoundaryVersion -> b.gtVersion(a) // reflected
    else -> error("unordered points")
}

internal fun ptGt(a: Any?, b: Any?): Boolean = when {
    a is Version && b is Version -> a > b
    a is BoundaryVersion && b is BoundaryVersion -> a.compareTo(b) > 0
    a is BoundaryVersion && b is Version -> a.gtVersion(b)
    a is Version && b is BoundaryVersion -> b.ltVersion(a) // reflected
    else -> error("unordered points")
}

/** Python `==` between points: a Version never equals a BoundaryVersion. */
internal fun ptEq(a: Any?, b: Any?): Boolean = when {
    a == null || b == null -> a == null && b == null
    a is Version && b is Version -> a == b
    a is BoundaryVersion && b is BoundaryVersion -> a.compareTo(b) == 0
    else -> false
}

internal class LowerBound(val point: Any?, val inclusive: Boolean) {
    /** `_above`: whether a version lies above this bound; null means always. */
    val above: ((Version) -> Boolean)? = when (point) {
        null -> null
        is BoundaryVersion ->
            if (point.kind == BoundaryKind.AFTER_POSTS) aboveAfterPosts(point.version) else aboveAfterLocals(point.version)
        is Version -> if (inclusive) { v -> point <= v } else { v -> point < v }
        else -> error("bad point")
    }

    fun eq(other: LowerBound): Boolean = ptEq(point, other.point) && inclusive == other.inclusive

    fun lt(other: LowerBound): Boolean {
        if (point == null) return other.point != null
        if (other.point == null) return false
        if (!ptEq(point, other.point)) return ptLt(point, other.point)
        return inclusive && !other.inclusive
    }

    // functools.total_ordering's __gt__ derived from __lt__.
    fun gt(other: LowerBound): Boolean = !lt(other) && !eq(other)
}

internal class UpperBound(val point: Any?, val inclusive: Boolean) {
    /** `_below`: whether a version lies below this bound; null means always. */
    val below: ((Version) -> Boolean)? = when (point) {
        null -> null
        is BoundaryVersion ->
            if (point.kind == BoundaryKind.AFTER_LOCALS) belowAfterLocals(point.version) else { v -> !point.ltVersion(v) }
        is Version -> if (inclusive) { v -> point >= v } else { v -> point > v }
        else -> error("bad point")
    }

    fun lt(other: UpperBound): Boolean {
        if (point == null) return false
        if (other.point == null) return true
        if (!ptEq(point, other.point)) return ptLt(point, other.point)
        return !inclusive && other.inclusive
    }
}

internal class Interval(val lower: LowerBound, val upper: UpperBound)

private val NEG_INF = LowerBound(null, false)
private val POS_INF = UpperBound(null, false)
internal val FULL_RANGE: List<Interval> = listOf(Interval(NEG_INF, POS_INF))

private val MIN_VERSION: Version = VersionParser.parse("0.dev0")!!
private val MIN_RELEASE: Version = VersionParser.parse("0")!!

private fun aboveAfterPosts(version: Version): (Version) -> Boolean {
    val trimmed = trimRelease(version.release)
    return { parsed -> if (version >= parsed) false else !sameReleaseAndPre(version, trimmed, parsed) }
}

private fun aboveAfterLocals(version: Version): (Version) -> Boolean {
    val trimmed = trimRelease(version.release)
    return { parsed ->
        when {
            version >= parsed -> false
            !sameReleaseAndPre(version, trimmed, parsed) -> true
            parsed.post != version.post -> true
            else -> parsed.dev != version.dev
        }
    }
}

private fun belowAfterLocals(version: Version): (Version) -> Boolean {
    val trimmed = trimRelease(version.release)
    return { parsed ->
        when {
            version >= parsed -> true
            !sameReleaseAndPre(version, trimmed, parsed) -> false
            parsed.post != version.post -> false
            else -> parsed.dev == version.dev
        }
    }
}

private val ONE = BigInteger.ONE

private fun nextPrefixDev0(version: Version): Version {
    val rel = version.release.toMutableList()
    rel[rel.size - 1] = rel[rel.size - 1] + ONE
    return Version(version.epoch, rel, null, null, BigInteger.ZERO, null)
}

private fun baseDev0(version: Version): Version =
    Version(version.epoch, version.release, null, null, BigInteger.ZERO, null)

internal fun leastVersionAbove(boundary: BoundaryVersion): Version? {
    val base = boundary.version
    if (boundary.kind == BoundaryKind.AFTER_LOCALS) {
        base.dev?.let { return base.copy(dev = it + ONE, local = null) }
        val nextPost = base.post?.let { it + ONE } ?: BigInteger.ZERO
        return base.copy(post = nextPost, dev = BigInteger.ZERO, local = null)
    }
    val pre = base.pre ?: return null
    return base.copy(pre = PreRelease(pre.kind, pre.number + ONE), post = null, dev = BigInteger.ZERO, local = null)
}

internal fun rangeIsEmpty(lower: LowerBound, upper: UpperBound): Boolean {
    val up = upper.point ?: return false
    val lo = lower.point ?: return !upper.inclusive && up is Version && up <= MIN_VERSION
    if (lo is BoundaryVersion) {
        val successor = leastVersionAbove(lo)
        if (successor != null) {
            if (ptEq(up, successor)) return !upper.inclusive
            return ptLt(up, successor)
        }
    }
    if (ptEq(lo, up)) return !(lower.inclusive && upper.inclusive)
    return ptGt(lo, up)
}

internal fun intersectRanges(left: List<Interval>, right: List<Interval>): List<Interval> {
    val result = ArrayList<Interval>()
    var li = 0
    var ri = 0
    while (li < left.size && ri < right.size) {
        val l = left[li]
        val r = right[ri]
        // Python's max(a, b) returns b only if b > a; min(a, b) returns b only if b < a.
        val lower = if (r.lower.gt(l.lower)) r.lower else l.lower
        val upper = if (r.upper.lt(l.upper)) r.upper else l.upper
        if (!rangeIsEmpty(lower, upper)) result.add(Interval(lower, upper))
        if (l.upper.lt(r.upper)) li++ else ri++
    }
    return result
}

internal fun intersectSpecifierBounds(perSpecifier: List<List<Interval>>): List<Interval> {
    require(perSpecifier.isNotEmpty()) { "intersect_specifier_bounds called with no specifiers" }
    var result = perSpecifier[0]
    for (k in 1 until perSpecifier.size) {
        result = intersectRanges(result, perSpecifier[k])
        if (result.isEmpty()) break
    }
    return result
}

internal fun matchesBoundsOnly(ranges: List<Interval>, version: Version): Boolean {
    for (interval in ranges) {
        val above = interval.lower.above
        if (above != null && !above(version)) return false
        val below = interval.upper.below
        if (below == null || below(version)) return true
    }
    return false
}

private fun nearestReleaseAbovePrerelease(version: Version): Version =
    if (version.pre != null) {
        version.copy(pre = null, post = null, dev = null, local = null)
    } else {
        version.copy(dev = null, local = null)
    }

private fun lowestReleaseAtOrAbove(value: Any?): Version = when (value) {
    null -> MIN_RELEASE
    is BoundaryVersion -> {
        val inner = value.version
        if (inner.isPrerelease) {
            nearestReleaseAbovePrerelease(inner)
        } else {
            inner.copy(post = inner.post?.let { it + ONE } ?: BigInteger.ZERO, local = null)
        }
    }
    is Version -> if (!value.isPrerelease) value else nearestReleaseAbovePrerelease(value)
    else -> error("bad point")
}

internal fun rangesArePrereleaseOnly(ranges: List<Interval>): Boolean {
    for (interval in ranges) {
        val nearest = lowestReleaseAtOrAbove(interval.lower.point)
        val up = interval.upper.point ?: return false
        if (ptLt(nearest, up)) return false
        if (ptEq(nearest, up) && interval.upper.inclusive) return false
    }
    return true
}

private fun wildcardRanges(op: String, base: Version): List<Interval> {
    val lower = baseDev0(base)
    val upper = nextPrefixDev0(base)
    if (op == "==") return listOf(Interval(LowerBound(lower, true), UpperBound(upper, false)))
    return listOf(Interval(NEG_INF, UpperBound(lower, false)), Interval(LowerBound(upper, true), POS_INF))
}

private fun standardRanges(op: String, version: Version, hasLocal: Boolean): List<Interval> {
    when (op) {
        ">=" -> return listOf(Interval(LowerBound(version, true), POS_INF))
        "<=" -> return listOf(
            Interval(NEG_INF, UpperBound(BoundaryVersion(version, BoundaryKind.AFTER_LOCALS), true)),
        )
        ">" -> {
            version.dev?.let {
                return listOf(Interval(LowerBound(version.copy(dev = it + ONE, local = null), true), POS_INF))
            }
            version.post?.let {
                val bound = version.copy(post = it + ONE, dev = BigInteger.ZERO, local = null)
                return listOf(Interval(LowerBound(bound, true), POS_INF))
            }
            return listOf(Interval(LowerBound(BoundaryVersion(version, BoundaryKind.AFTER_POSTS), false), POS_INF))
        }
        "<" -> {
            val bound = if (version.isPrerelease) version else version.copy(dev = BigInteger.ZERO, local = null)
            if (bound <= MIN_VERSION) return emptyList()
            return listOf(Interval(NEG_INF, UpperBound(bound, false)))
        }
    }
    val afterLocals = BoundaryVersion(version, BoundaryKind.AFTER_LOCALS)
    val upper: Any = if (hasLocal) version else afterLocals
    return when (op) {
        "==" -> listOf(Interval(LowerBound(version, true), UpperBound(upper, true)))
        "!=" -> listOf(Interval(NEG_INF, UpperBound(version, false)), Interval(LowerBound(upper, false), POS_INF))
        "~=" -> {
            val prefix = version.copy(release = version.release.subList(0, version.release.size - 1))
            listOf(Interval(LowerBound(version, true), UpperBound(nextPrefixDev0(prefix), false)))
        }
        else -> throw IllegalArgumentException("Unknown operator: $op")
    }
}

internal fun boundsForSpec(op: String, versionString: String, version: Version): List<Interval> =
    if (versionString.endsWith(".*")) wildcardRanges(op, version) else standardRanges(op, version, '+' in versionString)

internal fun resolvePrereleases(configured: Boolean?, autodetected: Boolean?): Boolean? = when {
    configured != null -> configured
    autodetected == true -> true
    else -> null
}

/**
 * `filter_by_ranges`. Items are anything [key] maps to a Version or a String;
 * items that do not parse as versions are dropped. With [prereleases] null,
 * pre-releases are kept only when no final release matches.
 */
internal fun <T> filterByRanges(
    ranges: List<Interval>,
    items: Iterable<T>,
    key: (T) -> Any,
    prereleases: Boolean?,
): List<T> {
    val out = ArrayList<T>()
    if (prereleases == null) {
        val buffer = ArrayList<T>()
        var foundFinal = false
        for (item in items) {
            val parsed = coerceVersion(key(item)) ?: continue
            if (!matchesBoundsOnly(ranges, parsed)) continue
            if (!parsed.isPrerelease) {
                foundFinal = true
                out.add(item)
            } else if (!foundFinal) {
                buffer.add(item)
            }
        }
        if (!foundFinal) out.addAll(buffer)
        return out
    }
    for (item in items) {
        val parsed = coerceVersion(key(item)) ?: continue
        if (!prereleases && parsed.isPrerelease) continue
        if (matchesBoundsOnly(ranges, parsed)) out.add(item)
    }
    return out
}

internal fun coerceVersion(value: Any): Version? = when (value) {
    is Version -> value
    is String -> VersionParser.parse(value)
    else -> error("expected Version or String")
}
