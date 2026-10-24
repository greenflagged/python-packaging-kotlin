package dev.greenflagged.pypackaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Differences from Python found by the differential test, pinned so they stay fixed. */
class RegressionTest {

    @Test
    fun specifierPrefixMatchBacktracksOutOfEpochWithoutRelease() {
        // Python's tokenizer matches "==83" here (the epoch group backtracks), so the
        // error points after it rather than at the start of the specifier.
        val e = assertThrows<InvalidRequirementException> { Requirement.parse(" Y ==83!.12  ") }
        assertEquals(
            "Expected comma (within version specifier), semicolon (after version specifier) or end\n" +
                "     Y ==83!.12  \n" +
                "       ~~~~^",
            e.message,
        )
    }

    @Test
    fun lowercaseFollowsPythonFinalSigmaRule() {
        // Final sigma: preceded by a cased letter and not followed by one.
        assertEquals("10bbaς9z2cc", Names.canonicalize("10bbaΣ9Z2cc"))
        assertEquals("xς-a-", Names.canonicalize("XΣ_a-_"))
        // Not final: preceded by "_" (neither cased nor case-ignorable).
        assertEquals("-yzx01a-σ", Names.canonicalize("_YZX01a_Σ"))
        assertEquals("aza-x-σ1", Names.canonicalize("aZa.X_Σ1"))
        assertEquals("i̇", Names.canonicalize("İ"))
    }
}
