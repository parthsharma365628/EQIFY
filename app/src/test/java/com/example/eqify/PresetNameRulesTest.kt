package com.example.eqify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetNameRulesTest {

    @Test fun reservedNamesAreCaseInsensitive() {
        assertTrue(PresetNameRules.isReserved("custom"))
        assertTrue(PresetNameRules.isReserved(" FLAT "))
        assertTrue(PresetNameRules.isReserved("hip-hop"))
    }

    @Test fun validateNewMatchesExistingDialogMessages() {
        assertEquals("Enter a profile name.", PresetNameRules.validateNew("  ", emptyList()))
        assertEquals("Choose a unique profile name.", PresetNameRules.validateNew("Rock", emptyList()))
        assertEquals("Choose a unique profile name.", PresetNameRules.validateNew("gym", listOf("Gym")))
        assertNull(PresetNameRules.validateNew("Gym 2", listOf("Gym")))
    }

    @Test fun uniqueNameKeepsFreeNames() =
        assertEquals("Night Bass", PresetNameRules.uniqueName("Night Bass", listOf("Gym")))

    @Test fun uniqueNameAddsSuffixChain() {
        assertEquals("Gym (2)", PresetNameRules.uniqueName("Gym", listOf("gym")))
        assertEquals("Gym (3)", PresetNameRules.uniqueName("Gym", listOf("Gym", "Gym (2)")))
    }

    @Test fun uniqueNameAvoidsReservedNames() {
        assertEquals("Rock (2)", PresetNameRules.uniqueName("Rock", emptyList()))
        assertEquals("custom (2)", PresetNameRules.uniqueName("custom", emptyList()))
    }

    @Test fun uniqueNameSanitizesAndCapsLength() {
        assertEquals("Late Night", PresetNameRules.uniqueName("  Late\u0000\n  Night ", emptyList()))
        assertEquals(EqProfileCodec.FALLBACK_NAME, PresetNameRules.uniqueName("   ", emptyList()))
        val long = "a".repeat(80)
        val first = PresetNameRules.uniqueName(long, emptyList())
        assertEquals(PresetNameRules.MAX_LENGTH, first.length)
        val second = PresetNameRules.uniqueName(long, listOf(first))
        assertTrue(second.length <= PresetNameRules.MAX_LENGTH)
        assertTrue(second.endsWith(" (2)"))
    }
}
