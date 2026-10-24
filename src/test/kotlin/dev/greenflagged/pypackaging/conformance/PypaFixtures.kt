package dev.greenflagged.pypackaging.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import java.math.BigInteger

/*
 * Test vectors from pypa/packaging (Apache-2.0 OR BSD-2-Clause, Copyright (c) Donald
 * Stufft and individual contributors), harvested by tools/harvest_pypa_vectors.py into
 * src/test/resources/pypa/. See NOTICE.
 *
 * Each upstream parametrized test becomes a list of cases: a map from the pytest
 * parameter name to its value, decoded into plain Kotlin values:
 *   str -> String, int -> Long or BigInteger, bool -> Boolean, None -> null,
 *   tuple/list -> List<Any?>, set -> Set<Any?>, dict -> Map<Any?, Any?>,
 *   Version/Specifier/... -> PyObject(type, str, prereleases), other -> PyRepr(repr).
 */

/** A packaging object from a vector, e.g. `PyObject("version", "1.0")`. */
data class PyObject(val type: String, val str: String, val prereleases: Boolean? = null)

/** A value the harvester could only record as its Python `repr`. */
data class PyRepr(val repr: String)

typealias Case = Map<String, Any?>

object PypaFixtures {
    private val cache = HashMap<String, JsonObject>()

    private fun load(file: String): JsonObject = cache.getOrPut(file) {
        val stream = PypaFixtures::class.java.getResourceAsStream("/pypa/$file.json")
            ?: error("missing fixture /pypa/$file.json")
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    /** The decoded cases of upstream test [test] (e.g. `TestVersion.test_valid_versions`) in [file] (e.g. `test_version`). */
    fun cases(file: String, test: String): List<Case> {
        val tests = load(file)["tests"]!!.jsonObject
        val entry = tests[test]?.jsonObject ?: error("no upstream test $test in $file; known: ${tests.keys}")
        val cases = entry["cases"]!!.jsonArray.map { c -> c.jsonObject.mapValues { decode(it.value) } }
        assertTrue(cases.isNotEmpty(), "upstream test $test has no parametrized cases")
        return cases
    }

    /** One dynamic test per case of upstream [test], named after its parameters. */
    fun dynamic(file: String, test: String, body: (Case) -> Unit): List<DynamicTest> =
        cases(file, test).mapIndexed { i, case ->
            DynamicTest.dynamicTest("$test[$i] ${case.values.joinToString(", ").take(150)}") { body(case) }
        }

    fun decode(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.content.toDouble()
        }
        is JsonArray -> e.map { decode(it) }
        is JsonObject -> when {
            "\$int" in e -> BigInteger(e["\$int"]!!.jsonPrimitive.content)
            "\$set" in e -> e["\$set"]!!.jsonArray.map { decode(it) }.toSet()
            "\$dict" in e -> e["\$dict"]!!.jsonArray.associate { kv -> decode(kv.jsonArray[0]) to decode(kv.jsonArray[1]) }
            "\$repr" in e -> PyRepr(e["\$repr"]!!.jsonPrimitive.content)
            else -> {
                val type = e.keys.first { it.startsWith("$") }
                PyObject(type.removePrefix("$"), e[type]!!.jsonPrimitive.content, e["prereleases"]?.jsonPrimitive?.booleanOrNull)
            }
        }
    }
}

/** Typed accessors for case values. */
fun Case.str(key: String): String = this[key] as String

fun Case.strOrNull(key: String): String? = this[key] as String?

fun Case.bool(key: String): Boolean = this[key] as Boolean

fun Case.boolOrNull(key: String): Boolean? = this[key] as Boolean?

@Suppress("UNCHECKED_CAST")
fun Case.list(key: String): List<Any?> = this[key] as List<Any?>

/** Converts a decoded integer (Long or BigInteger) to BigInteger. */
fun bigOf(value: Any?): BigInteger = when (value) {
    is Long -> BigInteger.valueOf(value)
    is BigInteger -> value
    is Int -> BigInteger.valueOf(value.toLong())
    else -> error("not an integer: $value")
}
