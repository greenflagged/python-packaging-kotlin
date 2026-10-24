package dev.greenflagged.pypackaging

/**
 * Input bounds. This library is meant to parse untrusted package metadata, so every
 * parser rejects inputs above these sizes before doing any work. Python's `packaging`
 * has no such limits; inputs within them behave identically.
 *
 * Parsing is linear in input length (there are no backtracking regular expressions),
 * so the limits bound memory and the size of the numbers involved rather than
 * guarding against pathological patterns.
 */
public object Limits {
    /** Maximum length, in UTF-16 chars, of a version string. */
    public const val MAX_VERSION_LENGTH: Int = 1024

    /** Maximum length of a specifier or comma-separated specifier set. */
    public const val MAX_SPECIFIER_LENGTH: Int = 16 * 1024

    /** Maximum length of a marker expression. */
    public const val MAX_MARKER_LENGTH: Int = 64 * 1024

    /** Maximum length of a requirement string. */
    public const val MAX_REQUIREMENT_LENGTH: Int = 64 * 1024

    /**
     * Maximum parenthesis nesting depth in a marker. Python's own recursion limit
     * rejects markers nested a few hundred levels deep.
     */
    public const val MAX_MARKER_NESTING: Int = 100

    /** Maximum length of a wheel or sdist filename. */
    public const val MAX_FILENAME_LENGTH: Int = 4096

    /** Maximum number of tags a wheel filename's compressed tag set may expand to. */
    public const val MAX_WHEEL_TAGS: Int = 4096
}
