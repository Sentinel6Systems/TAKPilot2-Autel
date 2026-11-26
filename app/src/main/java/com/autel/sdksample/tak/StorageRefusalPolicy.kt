package com.autel.sdksample.tak

/**
 * WHY THE CAMERA WILL NOT WRITE, in the pilot's words, from the SD card state. Pure, no SDK
 * import: StorageRefusalPolicyTest pins it. [FlightActivity.recordFailureReason] feeds it the
 * card state the camera last reported.
 *
 * The state names are this SDK's `SDCardState`. Before, the app showed the raw name
 * ("The SD card is not ready (NO_CARD).").
 */
object StorageRefusalPolicy {

    /**
     * @param sdStateName the SDK's `SDCardState` name, null when the camera has not answered.
     * @return the reason, or null when the state has no words here. Null means "say the state
     *   name": the caller keeps its own message for a state this table does not know.
     */
    fun reason(sdStateName: String?): String? = when (sdStateName) {
        "NO_CARD" -> "No SD card"
        "CARD_FULL" -> "SD card is full"
        "CARD_PROTECT" -> "SD card is write protected"
        "UNKNOWN_FILE_SYSTEM_FAT", "FORMAT_FAIL" -> "SD card needs a format"
        "FORMATTING" -> "SD card is formatting"
        "CARD_NOT_SUPPORT" -> "SD card cannot be used"
        "LOW_SPEED_CARD", "LOW_SPEED_CARD_STOP_RECORD" -> "SD card is too slow"
        "CARD_ERROR" -> "SD card error"
        else -> null
    }
}
