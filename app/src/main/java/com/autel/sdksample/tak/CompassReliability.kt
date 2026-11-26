package com.autel.sdksample.tak

import kotlin.math.abs

/** How far the measured magnetic field may be from the Earth's field at the place, as a part
 *  of it, before the compass is called unreliable. */
internal const val COMPASS_FIELD_TOLERANCE = 0.25

/** The tolerance to come BACK to reliable: smaller, so the indicator does not flicker between
 *  the two colours at the limit. */
internal const val COMPASS_FIELD_TOLERANCE_BACK = 0.20

/**
 * Is the compass of the controller fit to aim the antennas with?
 *
 * Two independent signs of a bad compass, either is enough:
 *  - the sensor says so itself: status UNRELIABLE (0) or LOW (1) of Android's four;
 *  - the STRENGTH of the measured field is not the Earth's. Steel and electric current near
 *    the controller (a vehicle) add their own field, and a field that is 25 % too strong or
 *    too weak also has a wrong direction. The expected strength comes from the world magnetic
 *    model at the operator's position.
 *
 * A value that is not known (null) is not a sign of a bad compass: a controller with no
 * magnetometer readout keeps the indicator as it was. Pure, pinned by CompassReliabilityTest.
 *
 * @param accuracyStatus the lower of the sensors' accuracy statuses, null when none reported.
 * @param measuredUt the measured field strength, microtesla.
 * @param expectedUt the Earth's field strength at the operator's position, microtesla.
 * @param wasReliable the answer before this one, for the smaller way-back tolerance.
 */
internal fun compassReliable(
    accuracyStatus: Int?,
    measuredUt: Double?,
    expectedUt: Double?,
    wasReliable: Boolean = true,
): Boolean {
    if (accuracyStatus != null && accuracyStatus <= 1) return false
    if (measuredUt != null && expectedUt != null && measuredUt.isFinite() && expectedUt > 0.0) {
        val tolerance = if (wasReliable) COMPASS_FIELD_TOLERANCE else COMPASS_FIELD_TOLERANCE_BACK
        if (abs(measuredUt - expectedUt) / expectedUt > tolerance) return false
    }
    return true
}
