package dev.greenflagged.pypackaging

import dev.greenflagged.pypackaging.internal.VersionParser
import dev.greenflagged.pypackaging.internal.pyRepr
import java.math.BigInteger

/**
 * A [PEP 440](https://peps.python.org/pep-0440/) version, such as `1.0`, `2!3.4rc1.post2.dev3+local.7`.
 *
 * Instances are immutable, and ordered and compared by PEP 440 rules, so `1.0 == 1.0.0` and
 * `1.0a1 < 1.0`. [toString] returns the normalized form. The behaviour matches Python's
 * `packaging.version.Version` (packaging 26.3), which this class is a port of.
 *
 * Numeric components are [BigInteger] because Python integers are unbounded and
 * versions such as `20240101123456789012` are valid.
 *
 * ```kotlin
 * val v = Version.parse("1.0RC1")
 * v.toString()      // "1.0rc1"
 * v.isPrerelease    // true
 * v < Version.parse("1.0")   // true
 * ```
 */
public class Version internal constructor(
    epoch: BigInteger,
    release: List<BigInteger>,
    pre: PreRelease?,
    post: BigInteger?,
    dev: BigInteger?,
    local: List<Any>?,
) : Comparable<Version> {

    /** The epoch (`2` in `2!1.0`), `0` when absent. */
    public val epoch: BigInteger = epoch

    /** The release segment, e.g. `[1, 2, 0]` for `1.2.0`. Never empty. Trailing zeros are kept. */
    public val release: List<BigInteger> = release

    /** The pre-release segment (`rc1` in `1.0rc1`), or `null`. */
    public val pre: PreRelease? = pre

    /** The post-release number (`2` in `1.0.post2`), or `null`. */
    public val post: BigInteger? = post

    /** The development release number (`3` in `1.0.dev3`), or `null`. */
    public val dev: BigInteger? = dev

    // Local segments: each element is a lowercase String or a BigInteger.
    internal val localParts: List<Any>? = local

    private val key: CmpKey by lazy(LazyThreadSafetyMode.PUBLICATION) { CmpKey.of(this) }

    /** The normalized local version label (`ubuntu.1` in `1.0+ubuntu-01`), or `null`. */
    public val local: String?
        get() = localParts?.joinToString(".")

    /** The version without its local label, e.g. `1.0` for `1.0+abc`. */
    public val public: String
        get() = toString().substringBefore('+')

    /** The epoch and release segment only, e.g. `1!2.0` for `1!2.0rc1.post1`. */
    public val baseVersion: String
        get() {
            val rel = release.joinToString(".")
            return if (epoch.signum() != 0) "$epoch!$rel" else rel
        }

    /** True for pre-releases and development releases. */
    public val isPrerelease: Boolean get() = dev != null || pre != null

    /** True when there is a post-release segment. */
    public val isPostrelease: Boolean get() = post != null

    /** True when there is a development release segment. */
    public val isDevrelease: Boolean get() = dev != null

    /** The first release component. */
    public val major: BigInteger get() = release.getOrElse(0) { BigInteger.ZERO }

    /** The second release component, `0` when absent. */
    public val minor: BigInteger get() = release.getOrElse(1) { BigInteger.ZERO }

    /** The third release component, `0` when absent. */
    public val micro: BigInteger get() = release.getOrElse(2) { BigInteger.ZERO }

    /** Returns a builder initialised with this version's parts, for making a modified copy. */
    public fun toBuilder(): Builder = Builder(this)

    override fun compareTo(other: Version): Int = key.compareTo(other.key)

    /** PEP 440 equality: `1.0` equals `1.0.0`. Consistent with [compareTo] and [hashCode]. */
    override fun equals(other: Any?): Boolean = other is Version && key == other.key

    override fun hashCode(): Int = key.hashCode()

    /** The normalized form, e.g. `1.0rc1.post2.dev3+abc.5`. */
    override fun toString(): String = render(release)

    internal fun render(rel: List<BigInteger>): String {
        val sb = StringBuilder()
        if (epoch.signum() != 0) sb.append(epoch).append('!')
        rel.joinTo(sb, ".")
        pre?.let { sb.append(it.kind.label).append(it.number) }
        post?.let { sb.append(".post").append(it) }
        dev?.let { sb.append(".dev").append(it) }
        localParts?.let { sb.append('+'); it.joinTo(sb, ".") }
        return sb.toString()
    }

    internal fun trimmedReleaseString(): String {
        var end = release.size
        while (end > 1 && release[end - 1].signum() == 0) end--
        return render(if (end == release.size) release else release.subList(0, end))
    }

    internal fun copy(
        epoch: BigInteger = this.epoch,
        release: List<BigInteger> = this.release,
        pre: PreRelease? = this.pre,
        post: BigInteger? = this.post,
        dev: BigInteger? = this.dev,
        local: List<Any>? = this.localParts,
    ): Version = Version(epoch, release, pre, post, dev, local)

    /** Builds a [Version] from its parts, mirroring Python's `Version.from_parts`. */
    public class Builder internal constructor(source: Version?) {
        private var epoch: BigInteger = source?.epoch ?: BigInteger.ZERO
        private var release: List<BigInteger> = source?.release ?: listOf(BigInteger.ZERO)
        private var pre: PreRelease? = source?.pre
        private var post: BigInteger? = source?.post
        private var dev: BigInteger? = source?.dev
        private var local: List<Any>? = source?.localParts

        /** Sets the epoch. Must be non-negative. */
        public fun epoch(epoch: Long): Builder = epoch(BigInteger.valueOf(epoch))

        /** Sets the epoch. Must be non-negative. */
        public fun epoch(epoch: BigInteger): Builder = apply { this.epoch = nonNegative("epoch", epoch) }

        /** Sets the release segment. Must be non-empty and non-negative. */
        public fun release(vararg components: Long): Builder =
            release(components.map { BigInteger.valueOf(it) })

        /** Sets the release segment. Must be non-empty and non-negative. */
        public fun release(components: List<BigInteger>): Builder = apply {
            if (components.isEmpty() || components.any { it.signum() < 0 }) {
                throw InvalidVersionException(
                    "release must be a non-empty tuple of non-negative integers, got $components",
                )
            }
            this.release = components.toList()
        }

        /** Sets the pre-release segment, or clears it with `null`. */
        public fun pre(pre: PreRelease?): Builder = apply { this.pre = pre }

        /** Sets the pre-release segment, e.g. `pre(PreReleaseKind.RC, 1)`. */
        public fun pre(kind: PreReleaseKind, number: Long): Builder = pre(PreRelease(kind, BigInteger.valueOf(number)))

        /** Sets the post-release number, or clears it with `null`. */
        public fun post(post: BigInteger?): Builder = apply { this.post = post?.let { nonNegative("post", it) } }

        /** Sets the post-release number. */
        public fun post(post: Long): Builder = post(BigInteger.valueOf(post))

        /** Sets the development release number, or clears it with `null`. */
        public fun dev(dev: BigInteger?): Builder = apply { this.dev = dev?.let { nonNegative("dev", it) } }

        /** Sets the development release number. */
        public fun dev(dev: Long): Builder = dev(BigInteger.valueOf(dev))

        /** Sets the local label, e.g. `ubuntu.1`, or clears it with `null`. */
        public fun local(local: String?): Builder = apply {
            this.local = local?.let {
                VersionParser.parseLocalLabel(it)
                    ?: throw InvalidVersionException("local must be a valid version string, got ${pyRepr(it)}")
            }
        }

        /** Builds the version. */
        public fun build(): Version = Version(epoch, release, pre, post, dev, local)

        private fun nonNegative(what: String, value: BigInteger): BigInteger {
            if (value.signum() < 0) throw InvalidVersionException("$what must be non-negative integer, got $value")
            return value
        }
    }

    public companion object {
        /**
         * Parses [version] according to PEP 440, accepting every spelling Python's
         * `packaging` accepts (case-insensitive, `v` prefix, alternative separators,
         * surrounding whitespace).
         *
         * @throws InvalidVersionException if [version] is not a valid version, or is
         *   longer than [Limits.MAX_VERSION_LENGTH] characters.
         */
        @JvmStatic
        public fun parse(version: String): Version =
            VersionParser.parse(version) ?: throw InvalidVersionException("Invalid version: ${pyRepr(version)}")

        /** Like [parse], but returns `null` instead of throwing. */
        @JvmStatic
        public fun parseOrNull(version: String): Version? = VersionParser.parse(version)

        /** Whether [version] is a valid PEP 440 version. */
        @JvmStatic
        public fun isValid(version: String): Boolean = VersionParser.parse(version) != null

        /** Starts building a version from its parts. The release defaults to `0`. */
        @JvmStatic
        public fun builder(): Builder = Builder(null)

        /**
         * The canonical form of a version string, as Python's `packaging.utils.canonicalize_version`:
         * normalized, and with trailing `.0` release components removed when
         * [stripTrailingZero] is true. A string that is not a valid version is returned unchanged.
         */
        @JvmStatic
        @JvmOverloads
        public fun canonicalize(version: String, stripTrailingZero: Boolean = true): String {
            val parsed = VersionParser.parse(version) ?: return version
            return canonicalize(parsed, stripTrailingZero)
        }

        /** The canonical form of [version]; see [canonicalize]. */
        @JvmStatic
        @JvmOverloads
        public fun canonicalize(version: Version, stripTrailingZero: Boolean = true): String =
            if (stripTrailingZero) version.trimmedReleaseString() else version.toString()
    }
}

/** The kind of a pre-release: alpha, beta or release candidate. */
public enum class PreReleaseKind(
    /** The normalized spelling: `a`, `b` or `rc`. */
    public val label: String,
) {
    ALPHA("a"),
    BETA("b"),
    RC("rc"),
}

/** A pre-release segment such as `rc1`. */
public class PreRelease(
    /** Alpha, beta or release candidate. */
    public val kind: PreReleaseKind,
    /** The pre-release number, `1` in `rc1`. Non-negative. */
    public val number: BigInteger,
) {
    init {
        if (number.signum() < 0) {
            throw InvalidVersionException("pre must be a tuple of ('a'|'b'|'rc', non-negative int), got $this")
        }
    }

    override fun equals(other: Any?): Boolean = other is PreRelease && kind == other.kind && number == other.number

    override fun hashCode(): Int = kind.hashCode() * 31 + number.hashCode()

    /** The normalized form, e.g. `rc1`. */
    override fun toString(): String = kind.label + number
}

/**
 * Python's `_cmpkey`: (epoch, release with trailing zeros removed, suffix, local).
 * Suffix ranks follow packaging: dev-only releases sort before alphas, and a
 * missing local label sorts before any local label.
 */
internal class CmpKey(
    val epoch: BigInteger,
    val release: List<BigInteger>,
    val preRank: Int,
    val preN: BigInteger,
    val postRank: Int,
    val postN: BigInteger,
    val devRank: Int,
    val devN: BigInteger,
    val local: List<Any>?,
) : Comparable<CmpKey> {

    override fun compareTo(other: CmpKey): Int {
        var c = epoch.compareTo(other.epoch)
        if (c != 0) return c
        c = compareReleases(release, other.release)
        if (c != 0) return c
        c = compareSuffix(other)
        if (c != 0) return c
        return compareLocal(local, other.local)
    }

    fun compareSuffix(other: CmpKey): Int {
        var c = preRank.compareTo(other.preRank)
        if (c != 0) return c
        c = preN.compareTo(other.preN)
        if (c != 0) return c
        c = postRank.compareTo(other.postRank)
        if (c != 0) return c
        c = postN.compareTo(other.postN)
        if (c != 0) return c
        c = devRank.compareTo(other.devRank)
        if (c != 0) return c
        return devN.compareTo(other.devN)
    }

    override fun equals(other: Any?): Boolean = other is CmpKey && compareTo(other) == 0

    override fun hashCode(): Int {
        var h = epoch.hashCode()
        h = h * 31 + release.hashCode()
        h = h * 31 + preRank
        h = h * 31 + preN.hashCode()
        h = h * 31 + postRank
        h = h * 31 + postN.hashCode()
        h = h * 31 + devRank
        h = h * 31 + devN.hashCode()
        h = h * 31 + (local?.hashCode() ?: 0)
        return h
    }

    companion object {
        private const val PRE_RANK_DEV_ONLY = -1
        private const val PRE_RANK_STABLE = 3

        fun of(v: Version): CmpKey {
            var i = v.release.size
            while (i > 0 && v.release[i - 1].signum() == 0) i--
            val trimmed = if (i == v.release.size) v.release else v.release.subList(0, i)
            val pre = v.pre
            val (preRank, preN) = when {
                pre == null && v.post == null && v.dev != null -> PRE_RANK_DEV_ONLY to BigInteger.ZERO
                pre == null -> PRE_RANK_STABLE to BigInteger.ZERO
                else -> pre.kind.ordinal to pre.number
            }
            return CmpKey(
                epoch = v.epoch,
                release = trimmed,
                preRank = preRank,
                preN = preN,
                postRank = if (v.post == null) 0 else 1,
                postN = v.post ?: BigInteger.ZERO,
                devRank = if (v.dev == null) 1 else 0,
                devN = v.dev ?: BigInteger.ZERO,
                local = v.localParts,
            )
        }

        fun compareReleases(a: List<BigInteger>, b: List<BigInteger>): Int {
            val n = minOf(a.size, b.size)
            for (k in 0 until n) {
                val c = a[k].compareTo(b[k])
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)
        }

        // Python compares local segments as tuples: (int, "") for numbers and (-1, str) for
        // strings, so any number sorts after any string, and a missing local sorts first.
        fun compareLocal(a: List<Any>?, b: List<Any>?): Int {
            if (a == null) return if (b == null) 0 else -1
            if (b == null) return 1
            val n = minOf(a.size, b.size)
            for (k in 0 until n) {
                val x = a[k]
                val y = b[k]
                val c = when {
                    x is BigInteger && y is BigInteger -> x.compareTo(y)
                    x is BigInteger -> 1
                    y is BigInteger -> -1
                    else -> (x as String).compareTo(y as String)
                }
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)
        }
    }
}
