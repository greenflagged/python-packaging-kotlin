package dev.greenflagged.pypackaging

import dev.greenflagged.pypackaging.internal.isAsciiDigit
import dev.greenflagged.pypackaging.internal.isPyWordCodePoint
import dev.greenflagged.pypackaging.internal.pyCompare
import dev.greenflagged.pypackaging.internal.pyLower
import dev.greenflagged.pypackaging.internal.pyRepr
import java.math.BigInteger
import java.util.Collections

/**
 * A wheel compatibility tag such as `py3-none-any` ([PEP 425](https://peps.python.org/pep-0425/)).
 * All three parts are lowercased. A port of Python's `packaging.tags.Tag`.
 */
public class Tag(interpreter: String, abi: String, platform: String) {
    /** The interpreter, e.g. `cp312` or `py3`. */
    public val interpreter: String = pyLower(interpreter)

    /** The ABI, e.g. `cp312` or `none`. */
    public val abi: String = pyLower(abi)

    /** The platform, e.g. `manylinux_2_17_x86_64` or `any`. */
    public val platform: String = pyLower(platform)

    override fun equals(other: Any?): Boolean =
        other is Tag && interpreter == other.interpreter && abi == other.abi && platform == other.platform

    override fun hashCode(): Int = (interpreter.hashCode() * 31 + abi.hashCode()) * 31 + platform.hashCode()

    /** `interpreter-abi-platform`. */
    override fun toString(): String = "$interpreter-$abi-$platform"

    public companion object {
        /**
         * Parses a tag, which may be a compressed tag set such as `py2.py3-none-any`, into
         * the set of tags it stands for. A port of `packaging.tags.parse_tag`.
         *
         * @param validateOrder if true, require each compressed component to be sorted.
         * @param limit the most tags the set may expand to.
         * @throws InvalidTagException if the tag is malformed.
         * @throws UnsortedTagsException if [validateOrder] is true and a component is unsorted.
         * @throws TooManyTagsException if the set expands to more than [limit] tags.
         */
        @JvmStatic
        @JvmOverloads
        public fun parse(tag: String, validateOrder: Boolean = false, limit: Int = Limits.MAX_WHEEL_TAGS): Set<Tag> {
            require(limit >= 0) { "limit must be non-negative" }
            val components = tag.split('-').map { it.split('.') }
            for (parts in components) {
                if ("" in parts) {
                    throw InvalidTagException("Tag ${pyRepr(tag)} has an empty component: ${pyRepr(parts.joinToString("."))}")
                }
                if (validateOrder && parts != parts.sortedWith(::pyCompare)) {
                    throw UnsortedTagsException(
                        "Tag component ${pyRepr(parts.joinToString("."))} is not in sorted order per PEP 425",
                    )
                }
            }
            var count = BigInteger.ONE
            for (parts in components) count *= BigInteger.valueOf(parts.size.toLong())
            if (count > BigInteger.valueOf(limit.toLong())) {
                throw TooManyTagsException("Compressed tag set would generate $count tags, exceeding limit $limit")
            }
            if (components.size != 3) throw InvalidTagException("Tag ${pyRepr(tag)} must have exactly three components")
            val (interpreters, abis, platforms) = components
            for (interpreter in interpreters) {
                if (!isPyIdentifier(interpreter)) {
                    throw InvalidTagException("Tag ${pyRepr(tag)} has an invalid interpreter: ${pyRepr(interpreter)}")
                }
            }
            val result = LinkedHashSet<Tag>()
            for (i in interpreters) for (a in abis) for (p in platforms) result.add(Tag(i, a, p))
            return Collections.unmodifiableSet(result)
        }

        // Python's str.isidentifier(), restricted to ASCII. Python also accepts non-ASCII
        // identifiers; this rejects them rather than guess at Python's XID tables. Real
        // interpreter tags (py3, cp312, pp310, ...) are ASCII.
        private fun isPyIdentifier(s: String): Boolean {
            if (s.isEmpty()) return false
            for ((i, c) in s.withIndex()) {
                val ok = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || (i > 0 && c in '0'..'9')
                if (!ok) return false
            }
            return true
        }
    }
}

/** A wheel's optional build tag, e.g. `1` or `2a` in `pkg-1.0-2a-py3-none-any.whl`. */
public class BuildTag(
    /** The leading number. */
    public val number: BigInteger,
    /** The text after the number, possibly empty. */
    public val suffix: String,
) {
    override fun equals(other: Any?): Boolean = other is BuildTag && number == other.number && suffix == other.suffix

    override fun hashCode(): Int = number.hashCode() * 31 + suffix.hashCode()

    override fun toString(): String = "$number$suffix"
}

/** The parts of a wheel filename. See [Filenames.parseWheel]. */
public class WheelFilename(
    /** The normalized project name. */
    public val name: String,
    /** The version. */
    public val version: Version,
    /** The build tag, or `null`. */
    public val buildTag: BuildTag?,
    /** The compatibility tags. */
    public val tags: Set<Tag>,
) {
    override fun equals(other: Any?): Boolean =
        other is WheelFilename && name == other.name && version == other.version &&
            buildTag == other.buildTag && tags == other.tags

    override fun hashCode(): Int = ((name.hashCode() * 31 + version.hashCode()) * 31 + buildTag.hashCode()) * 31 + tags.hashCode()

    override fun toString(): String = "WheelFilename(name=$name, version=$version, buildTag=$buildTag, tags=$tags)"
}

/** The parts of an sdist filename. See [Filenames.parseSdist]. */
public class SdistFilename(
    /** The normalized project name. */
    public val name: String,
    /** The version. */
    public val version: Version,
) {
    override fun equals(other: Any?): Boolean = other is SdistFilename && name == other.name && version == other.version

    override fun hashCode(): Int = name.hashCode() * 31 + version.hashCode()

    override fun toString(): String = "SdistFilename(name=$name, version=$version)"
}

/**
 * Wheel and sdist filename parsing, ported from `packaging.utils.parse_wheel_filename`
 * and `parse_sdist_filename`.
 */
public object Filenames {

    /**
     * Parses a wheel filename such as `requests-2.31.0-py3-none-any.whl`.
     *
     * @param validateOrder if true, require compressed tag set components to be sorted.
     * @throws InvalidWheelFilenameException if the filename is malformed.
     * @throws LimitExceededException if it is longer than [Limits.MAX_FILENAME_LENGTH], or its
     *   version is longer than [Limits.MAX_VERSION_LENGTH].
     */
    @JvmStatic
    @JvmOverloads
    public fun parseWheel(filename: String, validateOrder: Boolean = false): WheelFilename {
        checkLimit("Wheel filename", filename.length, Limits.MAX_FILENAME_LENGTH)
        if (!filename.endsWith(".whl")) {
            throw InvalidWheelFilenameException("Invalid wheel filename (extension must be '.whl'): ${pyRepr(filename)}")
        }
        val stem = filename.substring(0, filename.length - 4)
        val dashes = stem.count { it == '-' }
        if (dashes != 4 && dashes != 5) {
            throw InvalidWheelFilenameException("Invalid wheel filename (wrong number of parts): ${pyRepr(stem)}")
        }
        val parts = stem.split("-", limit = dashes - 1)
        val namePart = parts[0]
        if ("__" in namePart || !isWheelName(namePart)) {
            throw InvalidWheelFilenameException("Invalid project name: ${pyRepr(stem)}")
        }
        val name = Names.canonicalize(namePart)
        val version = Version.parseOrNull(parts[1])
            ?: throw InvalidWheelFilenameException("Invalid wheel filename (invalid version): ${pyRepr(stem)}")
        val build = if (dashes == 5) {
            val buildPart = parts[2]
            var k = 0
            while (k < buildPart.length && isAsciiDigit(buildPart[k])) k++
            if (k == 0) throw InvalidWheelFilenameException("Invalid build number: $buildPart in ${pyRepr(stem)}")
            // Python's `(\d+)(.*)` stops the suffix at the first newline.
            val rest = buildPart.substring(k)
            BuildTag(BigInteger(buildPart.substring(0, k)), rest.substringBefore('\n'))
        } else {
            null
        }
        val tags = try {
            Tag.parse(parts.last(), validateOrder)
        } catch (_: UnsortedTagsException) {
            throw InvalidWheelFilenameException(
                "Invalid wheel filename (compressed tag set components must be in sorted order per PEP 425): ${pyRepr(stem)}",
            )
        } catch (_: TooManyTagsException) {
            throw InvalidWheelFilenameException("Invalid wheel filename (too many tags): ${pyRepr(stem)}")
        } catch (_: InvalidTagException) {
            throw InvalidWheelFilenameException("Invalid wheel filename (invalid tag component): ${pyRepr(stem)}")
        }
        return WheelFilename(name, version, build, tags)
    }

    /**
     * Parses an sdist filename such as `requests-2.31.0.tar.gz` or `foo-1.0.zip`.
     *
     * @throws InvalidSdistFilenameException if the filename is malformed.
     * @throws LimitExceededException if it is longer than [Limits.MAX_FILENAME_LENGTH], or its
     *   version is longer than [Limits.MAX_VERSION_LENGTH].
     */
    @JvmStatic
    public fun parseSdist(filename: String): SdistFilename {
        checkLimit("Sdist filename", filename.length, Limits.MAX_FILENAME_LENGTH)
        val stem = when {
            filename.endsWith(".tar.gz") -> filename.substring(0, filename.length - 7)
            filename.endsWith(".zip") -> filename.substring(0, filename.length - 4)
            else -> throw InvalidSdistFilenameException(
                "Invalid sdist filename (extension must be '.tar.gz' or '.zip'): ${pyRepr(filename)}",
            )
        }
        val dash = stem.lastIndexOf('-')
        if (dash < 0) throw InvalidSdistFilenameException("Invalid sdist filename: ${pyRepr(filename)}")
        val namePart = stem.substring(0, dash)
        if (namePart.isEmpty()) {
            throw InvalidSdistFilenameException("Invalid sdist filename (empty project name): ${pyRepr(filename)}")
        }
        val version = Version.parseOrNull(stem.substring(dash + 1))
            ?: throw InvalidSdistFilenameException("Invalid sdist filename (invalid version): ${pyRepr(filename)}")
        return SdistFilename(Names.canonicalize(namePart), version)
    }

    // Python's `^[\w._]+\Z` with Unicode \w.
    private fun isWheelName(s: String): Boolean {
        if (s.isEmpty()) return false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp != '.'.code && !isPyWordCodePoint(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }
}
