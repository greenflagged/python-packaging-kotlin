package dev.greenflagged.pypackaging

import dev.greenflagged.pypackaging.internal.isAsciiAlnumIgnoreCase
import dev.greenflagged.pypackaging.internal.pyLower
import dev.greenflagged.pypackaging.internal.pyRepr

/**
 * Project name normalization ([PEP 503](https://peps.python.org/pep-0503/#normalized-names))
 * and validation ([core metadata `Name`](https://packaging.python.org/en/latest/specifications/name-normalization/)).
 * A port of `canonicalize_name` and `is_normalized_name` from `packaging.utils`.
 */
public object Names {

    /**
     * The normalized form of [name]: lowercased, with each run of `-`, `_` and `.`
     * replaced by a single `-`. `Foo.Bar__baz` becomes `foo-bar-baz`.
     *
     * @param validate if true, first check that [name] is a valid project name.
     * @throws InvalidNameException if [validate] is true and [name] is not valid.
     */
    @JvmStatic
    @JvmOverloads
    public fun canonicalize(name: String, validate: Boolean = false): String {
        if (validate && !isValid(name)) throw InvalidNameException("name is invalid: ${pyRepr(name)}")
        val lower = pyLower(name)
        val sb = StringBuilder(lower.length)
        var lastDash = false
        for (c in lower) {
            if (c == '-' || c == '_' || c == '.') {
                if (!lastDash) sb.append('-')
                lastDash = true
            } else {
                sb.append(c)
                lastDash = false
            }
        }
        return sb.toString()
    }

    /**
     * Whether [name] is a valid project name: ASCII letters and digits, with `.`, `_`
     * and `-` allowed inside but not at either end.
     */
    @JvmStatic
    public fun isValid(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!isAsciiAlnumIgnoreCase(name[0]) || !isAsciiAlnumIgnoreCase(name[name.length - 1])) return false
        return name.all { isAsciiAlnumIgnoreCase(it) || it == '.' || it == '_' || it == '-' }
    }

    /** Whether [name] is already in normalized form, e.g. `foo-bar` but not `Foo_bar`. */
    @JvmStatic
    public fun isNormalized(name: String): Boolean {
        if (name.isEmpty()) return false
        var lastDash = true // disallows a leading dash
        for (c in name) {
            if (c == '-') {
                if (lastDash) return false
                lastDash = true
            } else if (c in 'a'..'z' || c in '0'..'9') {
                lastDash = false
            } else {
                return false
            }
        }
        return !lastDash
    }
}
