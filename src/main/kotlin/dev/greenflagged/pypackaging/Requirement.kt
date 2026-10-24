package dev.greenflagged.pypackaging

import dev.greenflagged.pypackaging.internal.Parser
import dev.greenflagged.pypackaging.internal.ParserSyntaxError
import dev.greenflagged.pypackaging.internal.pyCompare
import java.util.Collections

/**
 * A [PEP 508 dependency specifier](https://peps.python.org/pep-0508/), such as
 * `requests[socks]>=2.8.1,<3; python_version >= "3.8"` or `pip @ https://example.com/pip.whl`.
 *
 * Immutable. A port of Python's `packaging.requirements.Requirement` (packaging 26.3).
 * Parsing is purely syntactic: the URL is not fetched or validated beyond PEP 508's
 * grammar.
 *
 * ```kotlin
 * val req = Requirement.parse("requests[socks]>=2.8.1; python_version >= '3.8'")
 * req.name        // "requests"
 * req.extras      // [socks]
 * req.specifier   // >=2.8.1
 * req.marker      // python_version >= "3.8"
 * ```
 */
public class Requirement private constructor(
    /** The project name as written. Use [Names.canonicalize] to compare names. */
    public val name: String,
    /** The direct reference URL after `@`, or `null`. */
    public val url: String?,
    extras: Set<String>,
    /** The version specifiers; empty when none were given. */
    public val specifier: SpecifierSet,
    /** The environment marker after `;`, or `null`. */
    public val marker: Marker?,
) {
    /** The requested extras as written, e.g. `[socks]`. Unmodifiable. */
    public val extras: Set<String> = Collections.unmodifiableSet(LinkedHashSet(extras))

    private val canonicalName: String get() = Names.canonicalize(name)

    private val canonicalExtras: Set<String> get() = extras.mapTo(HashSet()) { Names.canonicalize(it) }

    /**
     * Equal when the canonical names, canonical extras, specifier sets, URLs and markers
     * are all equal, so `Foo_Bar[X]>=1.0` equals `foo-bar[x]>=1`.
     */
    override fun equals(other: Any?): Boolean =
        other is Requirement &&
            canonicalName == other.canonicalName &&
            canonicalExtras == other.canonicalExtras &&
            specifier == other.specifier &&
            url == other.url &&
            marker == other.marker

    override fun hashCode(): Int {
        var h = canonicalName.hashCode()
        h = h * 31 + canonicalExtras.hashCode()
        h = h * 31 + specifier.hashCode()
        h = h * 31 + (url?.hashCode() ?: 0)
        h = h * 31 + (marker?.hashCode() ?: 0)
        return h
    }

    /** The normalized requirement string, e.g. `requests[socks]>=2.8.1; python_version >= "3.8"`. */
    override fun toString(): String {
        val sb = StringBuilder(name)
        if (extras.isNotEmpty()) {
            sb.append('[')
            extras.sortedWith(::pyCompare).joinTo(sb, ",")
            sb.append(']')
        }
        if (!specifier.isEmpty()) sb.append(specifier)
        if (url != null) {
            sb.append(" @ ").append(url)
            if (marker != null) sb.append(' ')
        }
        if (marker != null) sb.append("; ").append(marker)
        return sb.toString()
    }

    public companion object {
        /**
         * Parses a PEP 508 requirement string.
         *
         * @throws InvalidRequirementException if [requirement] is not valid. The message matches
         *   Python's, including the position marker line.
         * @throws LimitExceededException if it, or a part of it, exceeds a [Limits] bound.
         */
        @JvmStatic
        public fun parse(requirement: String): Requirement {
            checkLimit("Requirement", requirement.length, Limits.MAX_REQUIREMENT_LENGTH)
            val parsed = try {
                Parser.parseRequirement(requirement)
            } catch (e: ParserSyntaxError) {
                throw InvalidRequirementException(e.render())
            }
            val specifier = try {
                SpecifierSet.parse(parsed.specifier)
            } catch (e: InvalidSpecifierException) {
                throw InvalidRequirementException(e.message ?: "Invalid specifier")
            }
            return Requirement(
                name = parsed.name,
                url = parsed.url.ifEmpty { null },
                extras = parsed.extras.toSet(),
                specifier = specifier,
                marker = parsed.marker?.let { Marker.fromParsed(it) },
            )
        }

        /** Whether [requirement] is a valid PEP 508 requirement string. */
        @JvmStatic
        public fun isValid(requirement: String): Boolean = try {
            parse(requirement)
            true
        } catch (_: InvalidRequirementException) {
            false
        }
    }
}
