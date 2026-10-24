package dev.greenflagged.pypackaging.conformance

import dev.greenflagged.pypackaging.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class SmokeTest {
    @TestFactory
    fun valid(): List<DynamicTest> = PypaFixtures.dynamic("test_version", "TestVersion.test_valid_versions") { Version.parse(it.str("version")) }

    @TestFactory
    fun invalid(): List<DynamicTest> = PypaFixtures.dynamic("test_version", "TestVersion.test_invalid_versions") { assertNull(Version.parseOrNull(it.str("version"))) }

    @TestFactory
    fun normalized(): List<DynamicTest> = PypaFixtures.dynamic("test_version", "TestVersion.test_normalized_versions") {
        assertEquals(it.str("normalized"), Version.parse(it.str("version")).toString())
    }
}
