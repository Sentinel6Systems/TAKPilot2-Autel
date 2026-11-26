package com.autel.sdksample.tak

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.taklite.util.AppLog

/**
 * The controller's own compass facing, for the BVLOS antenna-aim readout.
 *
 * Smart Controller sensor set VERIFIED (dumpsys sensorservice): ISENTEK IST8310
 * magnetometer plus QTI Rotation Vector and GeoMagnetic Rotation Vector fusion sensors —
 * TYPE_ROTATION_VECTOR is real on this hardware. [azimuthTrueDeg] gives the direction the
 * controller's top edge faces, in degrees TRUE (declination from the pilot's GPS fix —
 * magnetic-only can be well over 10° off at high latitudes). The no-sensor path stays as a
 * guard for any other device, returning null, which simply hides the aim indicator.
 *
 * Started/stopped with the flight screen so the sensor never runs in the background.
 */
object ControllerCompass {
    private const val TAG = "ControllerCompass"

    @Volatile private var azimuthMagDeg: Double? = null
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var fieldListener: SensorEventListener? = null
    private var loggedMissing = false

    // IS THE COMPASS FIT TO USE? (Inside a vehicle the marker can point behind a pilot who
    // faces the aircraft.) The sensors' own accuracy status and the strength of
    // the measured field against the Earth's — see compassReliable. Written on the main
    // thread by the listeners, read on it by the HUD tick.
    @Volatile private var rotationAccuracy: Int? = null
    @Volatile private var fieldAccuracy: Int? = null
    @Volatile private var fieldUt: Double? = null
    private var expectedFieldUt: Double? = null
    private var lastReliable = true

    // Declination cache: azimuthTrueDeg() is called from the 2 Hz HUD tick, and building a
    // GeomagneticField is a real spherical-harmonics computation, not a cheap lookup — done
    // every 500ms it was work with no payoff, since declination barely moves over a few km.
    // Rebuilt only when the operator fix has moved past DECLINATION_CACHE_DEG.
    private var cachedDeclination: Double? = null
    private var cachedFixLat: Double? = null
    private var cachedFixLon: Double? = null
    private const val DECLINATION_CACHE_DEG = 0.01   // ~1.1km at the equator

    fun start(context: Context) {
        if (listener != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            if (!loggedMissing) {
                loggedMissing = true
                AppLog.i(TAG, "no rotation-vector sensor — antenna aim falls back to cardinal text")
            }
            return
        }
        val l = object : SensorEventListener {
            private val rot = FloatArray(9)
            private val remap = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                // The controller is held tilted, screen toward the pilot. The X/Z remap
                // treats the top edge as forward for that posture (the standard handheld
                // remap), which is the direction the antennas point.
                SensorManager.remapCoordinateSystem(
                    rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
                SensorManager.getOrientation(remap, orientation)
                azimuthMagDeg = CameraSlantPoint.norm360(Math.toDegrees(orientation[0].toDouble()))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                rotationAccuracy = accuracy
            }
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager = sm
        listener = l
        AppLog.i(TAG, "rotation-vector sensor armed for antenna aim")

        // The magnetometer itself, slowly (about 5 readings a second): only its field
        // strength and its accuracy status are used, to judge the heading above.
        val field = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
        val f = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                val now = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
                // Smoothed, so one odd reading does not change the colour of the indicator.
                fieldUt = fieldUt?.let { it + 0.2 * (now - it) } ?: now
                fieldAccuracy = event.accuracy
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                fieldAccuracy = accuracy
            }
        }
        sm.registerListener(f, field, SensorManager.SENSOR_DELAY_NORMAL)
        fieldListener = f
    }

    /**
     * False when the compass is disturbed or not calibrated: the antenna-aim indicator turns
     * red. True with no sign of trouble, and true when nothing can be measured. A change is
     * logged once, with the numbers, so a wrong indicator can be explained after a flight.
     */
    val reliable: Boolean
        get() {
            val status = listOfNotNull(rotationAccuracy, fieldAccuracy).minOrNull()
            val now = compassReliable(status, fieldUt, expectedFieldUt, lastReliable)
            if (now != lastReliable) {
                lastReliable = now
                AppLog.i(TAG, "compass ${if (now) "reliable" else "NOT RELIABLE"}: status=$status" +
                    " field=${fieldUt?.let { "%.0f".format(it) }} uT" +
                    " expected=${expectedFieldUt?.let { "%.0f".format(it) }} uT" +
                    " facing=${azimuthMagDeg?.let { "%.0f".format(it) }} mag")
            }
            return now
        }

    fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        fieldListener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        fieldListener = null
        sensorManager = null
        azimuthMagDeg = null
        rotationAccuracy = null
        fieldAccuracy = null
        fieldUt = null
        lastReliable = true
    }

    /**
     * Controller facing in degrees TRUE (0-360), or null when there is no sensor, no
     * reading yet, or no GPS fix to take declination from. Null keeps the readout honest:
     * a magnetic number silently presented as true would point the antennas wrong by the
     * local declination (more than 10° in places).
     */
    fun azimuthTrueDeg(): Double? {
        val mag = azimuthMagDeg ?: return null
        val fix = OperatorLocation.latest ?: return null
        return CameraSlantPoint.norm360(mag + declinationFor(fix))
    }

    private fun declinationFor(fix: android.location.Location): Double {
        val lastLat = cachedFixLat
        val lastLon = cachedFixLon
        val stale = cachedDeclination == null || lastLat == null || lastLon == null ||
            Math.abs(fix.latitude - lastLat) > DECLINATION_CACHE_DEG ||
            Math.abs(fix.longitude - lastLon) > DECLINATION_CACHE_DEG
        if (stale) {
            val model = GeomagneticField(
                fix.latitude.toFloat(), fix.longitude.toFloat(),
                fix.altitude.toFloat(), System.currentTimeMillis())
            cachedDeclination = model.declination.toDouble()
            // The same model gives the strength of the Earth's field here (nanotesla).
            expectedFieldUt = model.fieldStrength / 1000.0
            cachedFixLat = fix.latitude
            cachedFixLon = fix.longitude
        }
        return cachedDeclination!!
    }
}
