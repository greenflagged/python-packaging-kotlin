package dev.greenflagged.pypackaging

import dev.greenflagged.pypackaging.internal.MarkerItem
import dev.greenflagged.pypackaging.internal.MarkerList
import dev.greenflagged.pypackaging.internal.Parser
import dev.greenflagged.pypackaging.internal.ParserSyntaxError
import dev.greenflagged.pypackaging.internal.Value
import dev.greenflagged.pypackaging.internal.Variable
import dev.greenflagged.pypackaging.internal.pyRepr

/**
 * A [PEP 508 environment marker](https://peps.python.org/pep-0508/#environment-markers),
 * such as `python_version >= "3.8" and sys_platform == "win32"`.
 *
 * Immutable. A port of Python's `packaging.markers.Marker` (packaging 26.3). [toString]
 * gives the normalized form; two markers are equal when their normalized forms are.
 *
 * ```kotlin
 * val marker = Marker.parse("python_version >= '3.8' and extra == 'Socks'")
 * marker.toString()   // python_version >= "3.8" and extra == "socks"
 * marker.evaluate(env.toBuilder().extra("socks").build())
 * ```
 */
public class Marker private constructor(
    internal val markers: MarkerList,
    // Comparisons in the expression, counting repeats; bounds evaluate() and toString().
    private val clauses: Long,
) {
    private constructor(markers: MarkerList) : this(markers, countClauses(markers))

    private val string: String by lazy(LazyThreadSafetyMode.PUBLICATION) { format(markers) }

    /**
     * Evaluates this marker against [environment].
     *
     * Version-valued variables (`python_version`, `python_full_version`,
     * `implementation_version`, `platform_release`) are compared as PEP 440 versions when
     * the other side is a valid specifier; everything else compares as strings. `extra`,
     * `extras` and `dependency_groups` values are name-normalized before comparison.
     * All clauses are evaluated (there is no short-circuiting), as in Python.
     *
     * @param context which defaults apply: in [EvaluateContext.METADATA] `extra` defaults
     *   to `""`; in [EvaluateContext.LOCK_FILE] `extras` and `dependency_groups` default
     *   to empty sets.
     * @throws UndefinedEnvironmentNameException if the marker uses a variable that
     *   [environment] does not define.
     * @throws UndefinedComparisonException for comparisons with no defined result,
     *   such as `~=` on a non-version value.
     */
    @JvmOverloads
    public fun evaluate(environment: MarkerEnvironment, context: EvaluateContext = EvaluateContext.METADATA): Boolean {
        val env = LinkedHashMap<String, Any>()
        when (context) {
            EvaluateContext.LOCK_FILE -> {
                env["extras"] = emptySet<String>()
                env["dependency_groups"] = emptySet<String>()
            }
            EvaluateContext.METADATA -> env["extra"] = ""
            EvaluateContext.REQUIREMENT -> {}
        }
        env.putAll(environment.values)
        env.putAll(environment.setValues)
        val extra = env["extra"]
        if (extra is String) env["extra"] = if (extra.isEmpty()) "" else Names.canonicalize(extra)
        val full = env["python_full_version"]
        // Python 3.x+ builds report e.g. "3.13.0+"; make that a valid PEP 440 version.
        if (full is String && full.endsWith("+")) env["python_full_version"] = full + "local"
        return evaluateList(markers, env)
    }

    /**
     * A marker that holds when both this one and [other] do. Composition can nest to any
     * depth, but the total number of comparisons is capped at [Limits.MAX_MARKER_CLAUSES].
     *
     * @throws LimitExceededException if the result would exceed that cap.
     */
    public fun and(other: Marker): Marker = combine("and", other)

    /**
     * A marker that holds when this one or [other] does. See [and] for the size cap.
     *
     * @throws LimitExceededException if the result would exceed that cap.
     */
    public fun or(other: Marker): Marker = combine("or", other)

    private fun combine(op: String, other: Marker): Marker {
        val total = clauses + other.clauses
        if (total > Limits.MAX_MARKER_CLAUSES) {
            throw LimitExceededException(
                "Combined marker would have $total comparisons, more than ${Limits.MAX_MARKER_CLAUSES}",
                Limits.MAX_MARKER_CLAUSES,
            )
        }
        return Marker(MarkerList(listOf(markers, op, other.markers)), total)
    }

    override fun equals(other: Any?): Boolean = other is Marker && toString() == other.toString()

    override fun hashCode(): Int = toString().hashCode()

    /** The normalized marker, e.g. `python_version >= "3.8" and extra == "socks"`. */
    override fun toString(): String = string

    public companion object {
        /**
         * Parses a marker expression.
         *
         * @throws InvalidMarkerException if [marker] is not valid.
         * @throws LimitExceededException if it is longer than [Limits.MAX_MARKER_LENGTH] or nests
         *   parentheses deeper than [Limits.MAX_MARKER_NESTING].
         */
        @JvmStatic
        public fun parse(marker: String): Marker {
            checkLimit("Marker", marker.length, Limits.MAX_MARKER_LENGTH)
            val parsed = try {
                Parser.parseFullMarker(marker)
            } catch (e: ParserSyntaxError) {
                throw InvalidMarkerException(e.render())
            }
            return Marker(normalizeExtras(parsed))
        }

        /** Whether [marker] is a valid marker expression. */
        @JvmStatic
        public fun isValid(marker: String): Boolean = try {
            parse(marker)
            true
        } catch (_: InvalidMarkerException) {
            false
        }

        internal fun fromParsed(parsed: MarkerList): Marker = Marker(normalizeExtras(parsed))

        private val SET_VALUED = setOf("extras", "dependency_groups")
        private val VERSION_VALUED = setOf("implementation_version", "platform_release", "python_full_version", "python_version")

        // `_normalize_extras`: canonicalize literal names compared with extra/extras/dependency_groups.
        private fun normalizeExtras(list: MarkerList): MarkerList = MarkerList(
            list.elements.map { element ->
                when (element) {
                    is MarkerList -> normalizeExtras(element)
                    is MarkerItem -> {
                        val lhs = element.lhs
                        val rhs = element.rhs
                        when {
                            lhs is Variable && lhs.value == "extra" && rhs is Value ->
                                MarkerItem(lhs, element.op, Value(Names.canonicalize(rhs.value)))
                            rhs is Variable && rhs.value == "extra" && lhs is Value ->
                                MarkerItem(Value(Names.canonicalize(lhs.value)), element.op, rhs)
                            rhs is Variable && rhs.value in SET_VALUED && lhs is Value ->
                                MarkerItem(Value(Names.canonicalize(lhs.value)), element.op, rhs)
                            else -> element
                        }
                    }
                    else -> element
                }
            },
        )

        // Python's `_format_marker`, written iteratively: markers built with and()/or()
        // can nest arbitrarily deep, and recursion would overflow the stack.
        private class FormatFrame(val list: MarkerList, val first: Boolean) {
            var index = 0
        }

        private fun format(root: MarkerList): String {
            val sb = StringBuilder()
            val stack = ArrayDeque<FormatFrame>()
            fun open(node: Any, first: Boolean) {
                var n = node
                // A one-element list holding an item or list formats as that element.
                while (n is MarkerList) {
                    val only = n.elements.singleOrNull()
                    if (only is MarkerList || only is MarkerItem) n = only else break
                }
                when (n) {
                    is MarkerItem -> sb.append(n.lhs.serialize()).append(' ').append(n.op).append(' ').append(n.rhs.serialize())
                    is MarkerList -> {
                        if (!first) sb.append('(')
                        stack.addLast(FormatFrame(n, first))
                    }
                    else -> sb.append(n as String)
                }
            }
            open(root, true)
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (frame.index == frame.list.elements.size) {
                    if (!frame.first) sb.append(')')
                    stack.removeLast()
                    continue
                }
                if (frame.index > 0) sb.append(' ')
                open(frame.list.elements[frame.index++], false)
            }
            return sb.toString()
        }

        private fun countClauses(list: MarkerList): Long {
            var count = 0L
            val stack = ArrayDeque<MarkerList>()
            stack.addLast(list)
            while (stack.isNotEmpty()) {
                for (e in stack.removeLast().elements) {
                    if (e is MarkerItem) count++ else if (e is MarkerList) stack.addLast(e)
                }
            }
            return count
        }

        private fun lookup(env: Map<String, Any>, key: String): Any =
            env[key] ?: throw UndefinedEnvironmentNameException(key)

        // `_evaluate_markers`, written iteratively for the same reason as [format]. Every
        // clause is evaluated, left to right, as in Python (no short-circuiting).
        private class EvalFrame(val list: MarkerList) {
            var index = 0
            val groups = ArrayList<ArrayList<Boolean>>().apply { add(ArrayList()) }
            fun result(): Boolean = groups.any { g -> g.all { it } }
        }

        private fun evaluateList(root: MarkerList, env: Map<String, Any>): Boolean {
            val stack = ArrayDeque<EvalFrame>()
            stack.addLast(EvalFrame(root))
            while (true) {
                val frame = stack.last()
                if (frame.index == frame.list.elements.size) {
                    stack.removeLast()
                    val result = frame.result()
                    if (stack.isEmpty()) return result
                    stack.last().groups.last().add(result)
                    continue
                }
                when (val element = frame.list.elements[frame.index++]) {
                    is MarkerList -> stack.addLast(EvalFrame(element))
                    is MarkerItem -> frame.groups.last().add(evaluateItem(element, env))
                    "or" -> frame.groups.add(ArrayList())
                    "and" -> {}
                    else -> error("Unexpected marker $element")
                }
            }
        }

        private fun evaluateItem(element: MarkerItem, env: Map<String, Any>): Boolean {
            val key: String
            val lhsValue: Any
            val rhsValue: Any
            if (element.lhs is Variable) {
                key = element.lhs.value
                lhsValue = lookup(env, key)
                rhsValue = element.rhs.value
            } else {
                lhsValue = element.lhs.value
                key = element.rhs.value
                rhsValue = lookup(env, key)
            }
            if (lhsValue !is String) {
                throw UndefinedComparisonException(
                    "Set-valued marker ${pyRepr(key)} can only be used with the membership form " +
                        "(e.g. \"<name>\" in $key); it cannot appear on the left-hand side of " +
                        "${pyRepr(element.op)}.",
                )
            }
            val (l, r) = normalize(lhsValue, rhsValue, key)
            return evalOp(l, element.op, r, key)
        }

        private fun normalize(lhs: String, rhs: Any, key: String): Pair<String, Any> {
            if (key == "extra") return lhs to rhs
            if (key in SET_VALUED) {
                return Names.canonicalize(lhs) to when (rhs) {
                    is String -> Names.canonicalize(rhs)
                    else -> (rhs as Set<*>).mapTo(LinkedHashSet()) { Names.canonicalize(it as String) }
                }
            }
            return lhs to rhs
        }

        private fun evalOp(lhs: String, op: String, rhs: Any, key: String): Boolean {
            if (key in VERSION_VALUED && rhs is String) {
                val spec = try {
                    Specifier.parse(op + rhs)
                } catch (_: InvalidSpecifierException) {
                    null
                }
                if (spec != null) return spec.contains(lhs, true)
            }
            return when (op) {
                "in" -> if (rhs is String) lhs in rhs else lhs in (rhs as Set<*>)
                "not in" -> if (rhs is String) lhs !in rhs else lhs !in (rhs as Set<*>)
                "<", ">" -> false
                "<=", "==", ">=" -> lhs == rhs
                "!=" -> lhs != rhs
                else -> throw UndefinedComparisonException(
                    "Undefined <Op(${pyRepr(op)})> on ${pyRepr(lhs)} and ${reprValue(rhs)}.",
                )
            }
        }

        private fun reprValue(value: Any): String = when (value) {
            is String -> pyRepr(value)
            is Set<*> -> if (value.isEmpty()) "frozenset()" else value.joinToString(", ", "{", "}") { pyRepr(it as String) }
            else -> value.toString()
        }
    }
}
