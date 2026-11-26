package com.autel.sdksample.tak

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import com.autel.common.flycontroller.evo.EvoFlyControllerInfo
import com.autel.common.flycontroller.visual.AvoidanceRadarInfo
import com.autel.common.gimbal.evo.EvoAngleInfo
import com.taklite.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes the RAW sensor values of the aircraft to Downloads/TAKPilot2 Sensors, two rows each
 * second, on the ground and in flight. It is the instrument for characterising the sensors.
 *
 * **It records. It changes nothing.** No value that this object holds goes to a marker, the
 * SPI, the HUD or the TAK server.
 *
 * **It is fed. It does not subscribe** (one listener for each SDK channel). [onFlyController] and
 * [onGimbal] are called from the callbacks that `AutelTakBridge` already owns, and [onRadar]
 * from the callback that `AutelAvoidance` already owns. It makes no SDK call.
 *
 * **It writes only while the Debug log switch is on** (`AppLog.enabled`). With the switch off,
 * each call is one preference read.
 *
 * Why the file exists: the SDK getters do not give the raw values. `EvoGpsInfo.getAltitude()`
 * and `getHeightMeanSeaLevel()` divide the millimetre integers by 1000 as INTEGERS, thus the
 * application gets whole metres. The millimetres are only in `toString()` — see [rawGps].
 *
 * The callback thread is not blocked: a row is a string built in the callback, and all file
 * work is on the worker thread. Rows go to the file each [FLUSH_MS], thus a crash loses at
 * most that much.
 */
object SensorTrace {

    private const val TAG = "SensorTrace"
    private const val SUBFOLDER = "TAKPilot2 Sensors"
    private const val FOLDER_MAX_BYTES = 30L * 1024 * 1024
    private const val SAMPLE_MS = 500L
    private const val FLUSH_MS = 5000L

    internal const val CSV_HEADER =
        "utc_time,fly_mode,flying,app_rel_alt_m," +
        "gps_lat,gps_lon,gps_alt_mm,gps_hmsl_mm,gps_vn_mms,gps_ve_mms,gps_vd_mms,gps_speed_mms," +
        "gps_hacc_raw,gps_vacc_raw,gps_sacc,gps_vel_valid,gps_fix,gps_sats," +
        "loc_alt_m,loc_lat,loc_lon,loc_vx,loc_vy,loc_vz,loc_speed,home_lat,home_lon,home_alt_raw,home_dist,home_yaw," +
        "att_roll_deg,att_pitch_deg,att_yaw_deg,att_yaw_speed," +
        "imu_pressure,imu_temp,mag_x,mag_y,mag_z,acc_x,acc_y,acc_z,imu_fields," +
        "gim_pitch_deg,gim_yaw_deg,gim_roll_deg,gim_imu_status,gim_state,gim_age_ms," +
        "radar_bottom_min_cm,radar_bottom_age_ms,radar_bottom_pushes," +
        "takeoff_dted_m,geoid_n_m\n"

    @Volatile private var lastSampleAt = 0L

    // Latest gimbal frame, AS THE SDK GIVES IT (pitch is down-positive here; the bridge negates
    // its own copy). Written by the gimbal callback, read by the fly-controller callback.
    @Volatile private var gimPitch = Float.NaN
    @Volatile private var gimYaw = Float.NaN
    @Volatile private var gimRoll = Float.NaN
    @Volatile private var gimImuStatus = -1
    @Volatile private var gimState = ""
    @Volatile private var gimAtMs = 0L

    // The radar sends one face in each push. The bottom face is kept apart, or a 2 Hz sample
    // of the latest push does not see it.
    @Volatile private var bottomMinCm = Float.NaN
    @Volatile private var bottomAtMs = 0L
    @Volatile private var bottomPushes = 0

    private val worker: Handler by lazy {
        HandlerThread("SensorTrace").apply { start() }.let { Handler(it.looper) }
    }
    // Worker thread only.
    private val pending = StringBuilder()
    private var fileUri: Uri? = null
    private var legacyFile: File? = null
    private var flushPosted = false

    private val isoUtcMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    /** One gimbal frame from the bridge's angle callback. Field stores only. */
    fun onGimbal(info: EvoAngleInfo) {
        try {
            gimPitch = info.pitch
            gimYaw = info.yaw
            gimRoll = info.roll
            gimImuStatus = info.gimbalImuStatus
            gimState = info.gimbalState?.name ?: ""
            gimAtMs = SystemClock.elapsedRealtime()
        } catch (t: Throwable) {
            // A trace must not stop the callback that feeds it.
        }
    }

    /** One radar push from AutelAvoidance's callback. 0 = face not in this push, 10000 = clear. */
    fun onRadar(info: AvoidanceRadarInfo) {
        try {
            val face = info.bottom ?: return
            if (face.all { it == 0f }) return
            bottomMinCm = face.filter { it > 0f }.minOrNull() ?: Float.NaN
            bottomAtMs = SystemClock.elapsedRealtime()
            bottomPushes++
        } catch (t: Throwable) {
        }
    }

    /**
     * One fly-controller frame from the bridge's callback. [appRelAlt] is the height above
     * takeoff that the application uses (up-positive), [flyMode] and [flying] are what the
     * bridge decided for this frame.
     */
    fun onFlyController(info: EvoFlyControllerInfo, appRelAlt: Double, flyMode: String?, flying: Boolean) {
        try {
            if (!AppLog.enabled) return
            val now = System.currentTimeMillis()
            if (now - lastSampleAt < SAMPLE_MS) return
            lastSampleAt = now
            val row = row(now, info, appRelAlt, flyMode, flying)
            worker.post { append(row) }
        } catch (t: Throwable) {
        }
    }

    /** Stop of the bridge or of the application: put the rows that wait into the file. */
    fun flush() {
        runCatching { worker.post { writePending() } }
    }

    private fun row(now: Long, info: EvoFlyControllerInfo, appRelAlt: Double, flyMode: String?, flying: Boolean): String {
        val up = SystemClock.elapsedRealtime()
        val sb = StringBuilder(512)
        fun f(v: Any?) { if (v != null && !(v is Float && v.isNaN()) && !(v is Double && v.isNaN())) sb.append(v); sb.append(',') }
        sb.append(isoUtcMs.format(Date(now))).append(',')
        f(flyMode); f(if (flying) 1 else 0); f(num(appRelAlt, 2))
        val gps = info.gpsInfo
        val raw = gps?.let { rawGps(it.toString()) }
        f(gps?.latitude?.let { num(it, 7) }); f(gps?.longitude?.let { num(it, 7) })
        f(raw?.first); f(raw?.second)
        f(gps?.velocityNorth); f(gps?.velocityEast); f(gps?.velocityDown); f(gps?.gpsSpeed)
        f(gps?.horizontalAccuracy); f(gps?.verticalAccuracy); f(gps?.speedAccuracy)
        f(gps?.velNedValid); f(gps?.fixType); f(gps?.satellitesVisible)
        val loc = info.localCoordinateInfo
        f(loc?.altitude); f(loc?.latitude?.let { num(it, 7) }); f(loc?.longitude?.let { num(it, 7) })
        f(loc?.xSpeed); f(loc?.ySpeed); f(loc?.zSpeed); f(loc?.speed)
        f(loc?.homeLatitude?.let { num(it, 7) }); f(loc?.homeLongitude?.let { num(it, 7) })
        f(loc?.homeAltitude); f(loc?.distance); f(loc?.homeYaw)
        val att = info.attitudeInfo
        f(att?.roll?.let { num(it, 2) }); f(att?.pitch?.let { num(it, 2) }); f(att?.yaw?.let { num(it, 2) })
        f(att?.yawSpeed?.let { num(it, 4) })
        val imu = info.imuStateInfo
        f(imu?.absolutePressure); f(imu?.temperature)
        f(imu?.xMagnetometer); f(imu?.yMagnetometer); f(imu?.zMagnetometer)
        f(imu?.xAccelerometer); f(imu?.yAccelerometer); f(imu?.zAccelerometer); f(imu?.fieldsUpdated)
        val gAt = gimAtMs
        f(gimPitch); f(gimYaw); f(gimRoll); f(if (gAt == 0L) null else gimImuStatus); f(gimState)
        f(if (gAt == 0L) null else up - gAt)
        val bAt = bottomAtMs
        f(bottomMinCm); f(if (bAt == 0L) null else up - bAt); f(bottomPushes)
        f(TerrainAgl.takeoffTerrainElevMsl?.let { num(it, 1) })
        sb.append(TakBridgeHolder.geoidSeparationM?.let { num(it, 2) } ?: "")
        return sb.append('\n').toString()
    }

    private fun num(v: Double, decimals: Int): String =
        if (v.isFinite()) String.format(Locale.US, "%.${decimals}f", v) else ""

    private val RAW_GPS = Regex("""\bAlt:(-?\d+) hmsl:(-?\d+)""")

    /**
     * The raw GPS heights, in millimetres, from `GPSInfoInternal.toString()`: (Alt, hmsl).
     * Null if the text does not have them. The getters give whole metres (integer division,
     * read in the aar bytecode), and the fields are private.
     */
    internal fun rawGps(text: String): Pair<Int, Int>? {
        val m = RAW_GPS.find(text) ?: return null
        val alt = m.groupValues[1].toIntOrNull() ?: return null
        val hmsl = m.groupValues[2].toIntOrNull() ?: return null
        return alt to hmsl
    }

    // ---- Worker thread from here down ----

    private fun append(row: String) {
        pending.append(row)
        if (!flushPosted) {
            flushPosted = true
            worker.postDelayed({ writePending() }, FLUSH_MS)
        }
    }

    private fun writePending() {
        flushPosted = false
        if (pending.isEmpty()) return
        val text = pending.toString()
        pending.setLength(0)
        try {
            if (fileUri == null && legacyFile == null) createFile()
            fileUri?.let { uri ->
                context()?.contentResolver?.openOutputStream(uri, "wa")?.use { it.write(text.toByteArray()) }
            }
            legacyFile?.appendText(text)
        } catch (t: Throwable) {
            // Best-effort, like the flight record.
        }
    }

    private fun context() = com.autel.sdksample.TestApplication.getInstance()

    /** One file for each process. The name is the time of the first row. */
    private fun createFile() {
        val ctx = context() ?: return
        val name = "sensors-${fileNameFormat.format(Date())}.csv"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            enforceFolderCap()
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            }
            fileUri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            fileUri?.let { uri ->
                ctx.contentResolver.openOutputStream(uri, "wa")?.use { it.write(CSV_HEADER.toByteArray()) }
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBFOLDER)
            if (!dir.exists()) dir.mkdirs()
            legacyFile = File(dir, name).also { it.appendText(CSV_HEADER) }
        }
        AppLog.i(TAG, "sensor trace started: $name")
    }

    /** The oldest files go first until the folder is below [FOLDER_MAX_BYTES]. */
    private fun enforceFolderCap() {
        runCatching {
            val resolver = context()?.contentResolver ?: return
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val entries = ArrayList<Pair<Long, Long>>()   // id, size — oldest first
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER%"),
                "${MediaStore.Downloads.DATE_ADDED} ASC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (c.moveToNext()) entries.add(c.getLong(idCol) to c.getLong(sizeCol))
            }
            var total = entries.sumOf { it.second }
            for ((id, size) in entries) {
                if (total <= FOLDER_MAX_BYTES) break
                runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
                total -= size
            }
        }
    }
}
