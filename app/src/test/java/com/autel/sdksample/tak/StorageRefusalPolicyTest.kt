package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageRefusalPolicyTest {

    @Test
    fun aMissingOrFullCardIsNamed() {
        assertEquals("No SD card", StorageRefusalPolicy.reason("NO_CARD"))
        assertEquals("SD card is full", StorageRefusalPolicy.reason("CARD_FULL"))
        assertEquals("SD card is write protected", StorageRefusalPolicy.reason("CARD_PROTECT"))
        assertEquals("SD card needs a format", StorageRefusalPolicy.reason("UNKNOWN_FILE_SYSTEM_FAT"))
        assertEquals("SD card is too slow", StorageRefusalPolicy.reason("LOW_SPEED_CARD_STOP_RECORD"))
    }

    // A ready card, or a state not in the table, leaves the caller's own message.
    @Test
    fun aReadyOrUnknownCardGivesNoReason() {
        assertNull(StorageRefusalPolicy.reason("CARD_READY"))
        assertNull(StorageRefusalPolicy.reason("UNKNOWN"))
        assertNull(StorageRefusalPolicy.reason(null))
    }
}
