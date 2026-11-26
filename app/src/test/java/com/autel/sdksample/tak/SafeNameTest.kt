package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeNameTest {

    @Test
    fun lettersDigitsDashAndUnderscoreAreAllowed() {
        assertTrue(SafeName.isValid("UAS-1_Alpha"))
        assertTrue(SafeName.isValid(""))
    }

    @Test
    fun aSpaceOrOtherPunctuationIsNot() {
        assertFalse(SafeName.isValid("UAS 1"))
        assertFalse(SafeName.isValid("UAS/1"))
        assertFalse(SafeName.isValid("UAS.1"))
        assertFalse(SafeName.isValid("UAS?1"))
        assertFalse(SafeName.isValid("Ü1"))
    }

    @Test
    fun theErrorNamesEachBadCharacterOnce() {
        assertEquals("space", SafeName.badChars("UAS 1 2"))
        assertEquals("space/", SafeName.badChars("A B/C"))
    }

    @Test
    fun typingAndPastingKeepOnlyAllowedCharacters() {
        assertEquals("UAS1", SafeName.clean("UAS 1"))
        assertEquals("UAS-1_a", SafeName.clean(" UAS-1_a!"))
    }

    @Test
    fun anEmptyBroadcastIdOrOneStillOnTheOldCallsignFollowsTheCallsign() {
        assertEquals("UAS-2", SafeName.followCallsign("", "UAS-1", "UAS-2"))
        assertEquals("UAS-2", SafeName.followCallsign("UAS-1", "UAS-1", "UAS-2"))
        assertEquals("UAS-2", SafeName.followCallsign("", null, "UAS-2"))
    }

    @Test
    fun aBroadcastIdSetOnPurposeStaysAndABadCallsignIsNotCopied() {
        assertNull(SafeName.followCallsign("CAM-NORTH", "UAS-1", "UAS-2"))
        assertNull(SafeName.followCallsign("", "UAS-1", "UAS 2"))
        assertNull(SafeName.followCallsign("", "UAS-1", ""))
    }
}
