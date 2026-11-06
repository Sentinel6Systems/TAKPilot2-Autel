package com.autel.sdksample.tak

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakUser
import com.taklite.util.AppLog
import kotlin.math.abs
import kotlin.math.atan2
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * Augmented-reality overlay: projects TAK marker positions onto the live FPV so they appear
 * pinned to the world. Three deliberate design choices are listed below.
 *
 * **Dropped pins are the ground-truth test.** A pin is placed at [TakBridgeHolder.lookPoint], which is derived from
 * the same camera pose this view projects with, so **a freshly dropped pin must render dead
 * centre under the crosshair.** That is a ground-truth test needing no second TAK client, no
 * survey point and no flying — if it doesn't land under the crosshair, the projection, the pose
 * or the video rect is wrong, and contacts drawn on top of that cannot be trusted either.
 *
 * ### Design choices
 *
 * 1. **A real camera-frame projection, not angle differences.** Mapping angle to pixels
 *    linearly (`x = cx + Δbearing / (hFov/2) · halfW`) is a small-angle approximation. Here the
 *    target's direction is rotated into the camera's frame and divided by its forward
 *    component — the gnomonic picture, at any pitch — then scaled by tan of the half fields of
 *    view ([ArProjection]). The difference from linear is visible toward the frame edges and
 *    vanishes at the centre, which is what makes it easy to miss when eyeballing a marker in
 *    the middle of frame.
 * 2. **Draws to the video rectangle, not the view.** [FpvTextureView] letterboxes the image
 *    inside the view; projecting against view bounds shifts everything by the size of the bars.
 *    Same rect [CrosshairView] already uses, fed the same way.
 * 3. **Pitch sign.** Gimbal pitch is negative when looking down (as the DJI SDK also reports it); screen Y grows
 *    downward. Both conventions are handled once, in [project], rather than at each call site.
 * 4. **The world's angles are not the camera's.** A bearing difference is a rotation about the
 *    VERTICAL axis; once the gimbal pitches down it is only partly sideways in the camera's
 *    tilted frame — up to 15° wrong at −75°. [cameraFrameAngles] does that rotation here, and
 *    [project] never takes the world differences.
 *
 * Taking `tan(Δbearing)` and `tan(Δelevation)` as picture angles goes wrong in the air: with the
 * camera 71° down, a marker 35° off the bearing is 11° from the centre, and every yaw threw it
 * to the frame edge. [ArProjection] is that rotation with the perspective divide inside it and
 * those numbers as unit tests. There is no ±85° guard, and the edge arrows take their direction
 * from the same vector as the marker.
 *
 * Accuracy is bounded by the gimbal bearing and by two ages that differ: the picture's (video
 * lag, unmeasured) and the pose's (the attitude is current; the GPS position is moved forward
 * from its 2 Hz fixes) — markers will swim during fast gimbal movement. This is a "which of those buildings" tool, not a
 * survey instrument; the crosshair drop remains the precise one.
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Flight-tuned HUD colours, resolved once per view — see takpilot_colors.xml.
    private val AIRCRAFT_COLOR = ContextCompat.getColor(context, R.color.tp_ar_aircraft)
    private val ARROW_COLOR_PIN = ContextCompat.getColor(context, R.color.tp_accent)

    /**
     * Video image bounds within this view. Autel's `AutelCodecView` is an opaque SDK widget
     * with no content-rect callback, so this defaults to the full view bounds — the SAME
     * assumption [CrosshairView] documents, and the two MUST share it: a freshly dropped pin has
     * to render under the crosshair, which only holds if overlay and crosshair agree where the
     * image centre is. If the video turns out to be letterboxed internally, feed both via
     * [setVideoRect].
     */
    private val videoRect = RectF()
    private var videoRectExplicit = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!videoRectExplicit) {
            videoRect.set(0f, 0f, w.toFloat(), h.toFloat())
            invalidate()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /** Faster than the 500ms HUD tick — at 500ms a marker visibly steps across the frame during
     *  a gimbal sweep. Every frame composites over live video, so this is the one knob to back
     *  off first if the FPV frame rate suffers (that pipeline must not regress). */
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    fun setVideoRect(rect: RectF) {
        videoRect.set(rect)
        videoRectExplicit = true
        invalidate()
    }

    /**
     * How much of the video the app's own chrome REALLY covers. Only [drawEdgeArrow] uses
     * these — projected markers stay pinned to their true position even if chrome partly covers
     * them (moving a marker off its target would be worse than briefly hiding it), but an EDGE
     * ARROW has no true position; it is purely a "look this way" cue, so one parked where it
     * cannot be seen conveys nothing at all.
     *
     * ⚠ **OPAQUE CHROME ONLY — THE HUD COLUMN IS NOT CHROME** (design decision).
     * The HUD column is outlined text over live video and covers only the pixels around its
     * glyphs, so an arrow behind it is perfectly visible. Insetting by the column's whole width
     * (about 208dp on the Smart Controller) would cost a fifth of the screen's width in which
     * the direction cue could never be drawn — on the one edge a pilot scans most, since the
     * contacts list and the map both sit there.
     *
     * ⚠ **AND THE RIGHT STILL TAKES NO INSET — [rightGap] IS NOT ONE** (design decision).
     * An arrow can land hard against the glyphs of a readout such as "200 ft AGL". The fix
     * is NOT to give the column back its width: the arrow is moved OUTBOARD, into the
     * clear strip the column already leaves between its text and the edge of the picture, so it
     * costs nothing and the no-inset decision stands. An arrow may still pass BEHIND a wider
     * readout, which is the intended trade — outlined text hides nothing.
     *
     * What remains genuinely opaque, and is still excluded:
     *
     *  - **the toolbar band** ([top]), whose two capsules are a 70 % black fill;
     *  - **the actions column** ([left]), which takes the same 70 % fill and holds the pills
     *    the pilot presses; without this inset an edge arrow can be drawn ON the AR pill.
     *    ⚠ It is excluded by INSET and not by corner, unlike the map: the column runs the full
     *    height of the picture, so there is no "above it" to lift an arrow to. It costs one
     *    pill's width on an edge that holds no readouts;
     *  - **the mini-map** ([mapLeft], [mapTop]), which is a real map and hides anything under
     *    it completely. It is excluded by CORNER rather than by inset: an arrow that would land
     *    on it is lifted to just above it and keeps its place on the right edge, so the cue
     *    still points the right way instead of being pushed a fifth of the screen inboard.
     *
     * Fed from the flight screen's real measured view bounds rather than hardcoded dp, so this
     * cannot drift out of step with a toolbar or HUD layout change.
     */
    fun setChromeInsets(top: Float, left: Float, rightGap: Float, mapLeft: Float, mapTop: Float) {
        if (chromeInsetTop == top && chromeInsetLeft == left && chromeRightGap == rightGap &&
            chromeMapLeft == mapLeft && chromeMapTop == mapTop) return
        chromeInsetTop = top
        chromeInsetLeft = left
        chromeRightGap = rightGap
        chromeMapLeft = mapLeft
        chromeMapTop = mapTop
        // ⚠ SAID OUT LOUD, because these decide where an edge arrow may be drawn and a wrong
        // value hides the arrow completely rather than misplacing it visibly — without this line,
        // vanished arrows leave nothing in the log to say what this method was handed. Gated by
        // the change guard above, so it writes once per
        // real layout change and not per frame.
        AppLog.i(TAG, "chrome insets: top=%.0f left=%.0f rightGap=%.0f map=%.0f,%.0f"
            .format(top, left, rightGap, mapLeft, mapTop))
        invalidate()
    }

    private var chromeInsetTop = 0f
    private var chromeInsetLeft = 0f
    /** The HUD column's own end padding — the clear strip between the readouts' glyphs and the
     *  right edge of the picture. NOT an inset: see [setChromeInsets]. */
    private var chromeRightGap = 0f
    /** Left and top of the mini-map in this view's coordinates; [Float.MAX_VALUE] = not known
     *  yet, which excludes nothing. See [setChromeInsets]. */
    private var chromeMapLeft = Float.MAX_VALUE
    private var chromeMapTop = Float.MAX_VALUE

    fun start() {
        if (running) return
        running = true
        isRunningAnywhere = true
        visibility = VISIBLE
        handler.removeCallbacks(tick)
        handler.post(tick)
        AppLog.i(TAG, "AR overlay ON")
    }

    fun stop() {
        if (!running) return
        running = false
        isRunningAnywhere = false
        handler.removeCallbacks(tick)
        visibility = GONE
        AppLog.i(TAG, "AR overlay OFF")
    }

    val isRunning: Boolean get() = running

    // ---- Paints ----

    private val d get() = resources.displayMetrics.density

    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        alpha = OVERLAY_ALPHA
    }
    /** [iconPaint] for a STALE 2525 frame: grey and faded, the same treatment the map gives
     *  it in TakMapMarkers.makeMilIcon and the same the dots get below. One paint, applied at
     *  draw time, so the bitmap cache stays keyed on the resource alone. */
    private val staleIconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        alpha = TakMapMarkers.STALE_ALPHA
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        alpha = OVERLAY_ALPHA
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150 * OVERLAY_ALPHA / 255, 0, 0, 0)
    }

    /** PLI contacts draw as a team-coloured dot with a dark ring, matching the mini-map. */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1.5f * Resources.getSystem().displayMetrics.density
        alpha = OVERLAY_ALPHA
    }

    /**
     * Sets [dotPaint]'s fill colour and restores the overlay alpha.
     *
     * Paint.color carries its own alpha byte, so assigning a fully-opaque colour silently resets
     * whatever alpha was set at construction. Every dynamic colour therefore goes through here
     * rather than touching `dotPaint.color` directly — otherwise the dots would be opaque while
     * the icons and labels around them were not, which is the kind of inconsistency nobody
     * notices in the code and everybody notices in the air.
     */
    private fun dotFill(color: Int) {
        dotPaint.color = color
        dotPaint.alpha = OVERLAY_ALPHA
    }

    /** Reused across edge arrows so the draw loop doesn't allocate a Path per frame. */
    private val arrowPath = android.graphics.Path()

    private val iconCache = HashMap<Int, Bitmap>()

    // Throttled so a persistent "why is nothing drawing" condition logs once rather than at the
    // refresh rate — this runs several times a second.
    private var lastSkipReason: String? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        if (videoRect.isEmpty) return skipped("no video rect yet")

        val pose = TakBridgeHolder.cameraPose() ?: return skipped("no camera pose (GPS/gimbal)")
        val hud = TakBridgeHolder.hud() ?: return skipped("no telemetry")
        if (!hud.hasFix) return skipped("no GPS fix")
        // Nothing drawn over a frozen picture or from a frozen pose:
        // a marker in the wrong place reads as truth, an absent one does not.
        if (PictureClock.telemetryStale()) return skipped("aircraft data is stale")
        if (PictureClock.pictureFrozen()) return skipped("the video picture is frozen")
        lastSkipReason = null

        // NOTE: no early-return when there are no dropped pins — inbound contacts are drawn
        // below and are entirely independent of whether this pilot has placed anything.
        val pins = if (ArSettings.isEnabled(context, ArSettings.Category.MY_MARKERS)) {
            TakDropMarkers.listPins()
        } else {
            emptyList()
        }

        // Throttle per DRAW PASS, not per pin — a per-call throttle only ever logs whichever pin
        // happens to be first in the list, which hid a second marker's trace entirely during
        // troubleshooting.
        val now = System.currentTimeMillis()
        val logThisPass = now - lastDiagMs >= DIAG_INTERVAL_MS
        if (logThisPass) lastDiagMs = now

        // Aircraft altitude in the same reference the pins carry (DTED MSL). Without any
        // terrain at all we can't difference two real elevations, so both pins and contacts
        // fall back to a flat-ground assumption below — degraded, not disabled. With DTED under
        // the aircraft but no takeoff reference, the ground under it stands in — see
        // [estimateAircraftMsl].
        val terrain = TerrainAgl.reading(context, hud)
        val aircraftMsl = terrain.mslMeters
        // Loud, because without it EVERY vertical angle silently degrades: reported altitudes
        // can't be differenced against anything, and both pins and contacts fall back to a
        // flat-plane assumption that puts everything at the pilot's own takeoff level. That
        // failure looks like "AR works but heights are wrong" rather than like a missing input.
        if (logThisPass && aircraftMsl == null) {
            AppLog.w(TAG, "no aircraft MSL (no DTED at the takeoff point or under the aircraft) " +
                "— pin and contact elevations are flat-plane estimates")
        }
        // The geoid separation the aircraft's receiver applies — a contact's hae comes into the
        // DTED frame through it. Null before a fix; then the hae/MSL datum mix remains and is
        // said so once.
        val geoidN = TakBridgeHolder.geoidSeparationM
        if (logThisPass && geoidN == null && aircraftMsl != null) {
            AppLog.w(TAG, "no geoid separation from the aircraft yet — reported contact " +
                "heights carry the hae/MSL offset (about 12 m in places)")
        }

        for (pin in pins) {
            // Ground (great-circle) distance — what the label shows, matching the home-distance
            // and markers-list convention used elsewhere in the app.
            val groundDist = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, pin.lat, pin.lon)
            // Same ground horizon everything else on the ground gets — one source, so our own
            // pins can't out-range another operator's marker sitting beside them.
            if (groundDist > ArSettings.rangeMeters(context, ArSettings.Category.MY_MARKERS)) continue

            val bearing = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, pin.lat, pin.lon)
            // Wrapped to -180..180 so a target behind the aircraft doesn't project as if it were
            // far off to one side.
            val dBearing = ((bearing - pose.bearingDeg + 540.0) % 360.0) - 180.0

            // Height of the pin relative to the aircraft; negative = below, the normal case.
            // A pin with no elevation of its own (NaN: map tap, restored, dropped without
            // coverage) sits on the terrain under it, like a ground contact; only with nothing
            // to difference does it fall to the flat plane — see [pinHeightAboveAircraft] for
            // the sea-level fault this replaced.
            val terrainUnderPin = if (pin.alt.isFinite() || aircraftMsl == null) null
                else DtedIndex.elevationAt(context, pin.lat, pin.lon)
            val dz = pinHeightAboveAircraft(pin.alt, terrainUnderPin, aircraftMsl, hud.relAlt)

            // Reject on SLANT range, never on ground distance. Aiming steeply down — which is
            // exactly how a marker gets dropped on something beneath the aircraft — drives
            // ground distance toward zero while the pin is still tens of metres away and
            // perfectly visible. Guarding on ground distance made near-nadir markers
            // undrawable, which is the one case the crosshair self-test most naturally lands in.
            val slantRange = kotlin.math.hypot(groundDist, dz)
            if (slantRange < MIN_RANGE_M) continue

            // Depression angle: rise over run against the ground distance, so straight-down
            // correctly approaches -90 degrees.
            val elevDeg = Math.toDegrees(atan2(dz, groundDist))
            // The projection rotates the world's bearing and elevation differences into the
            // camera's frame itself — see ArProjection for why they are not the camera's.
            val xy = project(dBearing, elevDeg, pose.pitchDeg)
            if (logThisPass) diag(pin, pose, groundDist, dz, bearing, dBearing, elevDeg, xy)
            if (xy == null) {
                drawEdgeArrow(canvas, dBearing, elevDeg, pose.pitchDeg, ARROW_COLOR_PIN)
                continue
            }
            drawPin(canvas, xy.first, xy.second, pin)
        }

        drawContacts(canvas, pose, hud, aircraftMsl, logThisPass)
    }

    /**
     * Inbound TAK contacts — other operators' positions and the markers they've placed.
     *
     * Skips, in order: contacts with no position; ones the pilot has locally hidden (that hide
     * is shared with the mini-map, so the two pictures agree); our own dropped pins echoed back
     * by the server (already drawn from [TakDropMarkers]); and our own published aircraft/SPI
     * uids, which are not targets at all.
     */
    private fun drawContacts(
        canvas: Canvas,
        pose: AutelTakBridge.CameraPose,
        hud: AutelTakBridge.Hud,
        aircraftMsl: Double?,
        logThisPass: Boolean,
    ) {
        // The geoid separation the aircraft's receiver applies, read once per pass — see the
        // note in onDraw. Null before a fix; then a reported hae is used as it came.
        val geoidN = TakBridgeHolder.geoidSeparationM
        // Nearest first, so when the label budget runs out it's the distant contacts that lose
        // their plate rather than whichever happened to arrive first.
        val users = runCatching { TakManager.getInstance().takUsers }.getOrNull()
            ?.sortedBy { CameraSlantPoint.distanceMeters(hud.lat, hud.lon, it.lat, it.lon) }
            ?: return
        var drawn = 0
        var offFrame = 0
        var skipped = 0
        var detailed = 0

        val nowMs = System.currentTimeMillis()
        for (u in users) {
            var lat = u.lat
            var lon = u.lon
            if (lat == 0.0 && lon == 0.0) { skipped++; continue }
            if (TakMapMarkers.isHidden(u.uid)) { skipped++; continue }
            if (TakDropMarkers.ownsUid(u.uid)) { skipped++; continue }
            if (TakBridgeHolder.isOwnPublishedUid(u.uid)) { skipped++; continue }

            // Category decides BOTH whether this is drawn and how far out it stays relevant —
            // air traffic is worth seeing well past the range a ground marker is.
            val category = ArSettings.categoryFor(u.uid, u.type, u.isLiveClient)
            if (!ArSettings.isEnabled(context, category)) { skipped++; continue }
            // High-altitude traffic is clutter for a UAS below 400ft — see the ceiling's
            // doc. Shared with the map so both views show the same picture.
            if (ArSettings.isAboveAirTrafficCeiling(u.type, u.alt)) { skipped++; continue }

            // AIR TRACKS MOVED TO NOW. ADS-B arrives every ~4 s and ~1 s
            // old, so a 60 m/s aircraft was drawn up to ~400 m behind itself. Dead-reckoned
            // along its reported course and speed, from the moment THIS controller received it
            // (its own clock, so a sender's or a drifting controller clock cannot skew the age),
            // capped so a dropped track does not fly off. Ground PLIs are not moved (design
            // decision).
            if (category == ArSettings.Category.AIRCRAFT && u.hasCourse() && u.speed > 0.5) {
                val ageS = ((nowMs - u.lastUpdateTime) / 1000.0).coerceIn(0.0, AIR_PROPAGATE_MAX_S)
                if (ageS > 0.0) {
                    val p = CameraSlantPoint.destination(lat, lon, u.course, u.speed * ageS)
                    lat = p.first; lon = p.second
                }
            }

            val groundDist = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, lat, lon)
            if (groundDist > ArSettings.rangeMeters(context, category)) { skipped++; continue }

            // Height of the contact relative to the aircraft. Two independent ways to get it,
            // and they disagree — which one is right is an open field question (see below).
            //
            // REPORTED (primary, by design): the contact's own CoT altitude. TAK clients
            // do know and publish their height, so using it is the obvious thing and it is the
            // only option that can be right for a target NOT standing on the ground — an upper
            // floor, a bridge, another aircraft.
            //   Caveat: CotParser reads it from the `hae` attribute, i.e. height above the WGS84
            //   ELLIPSOID, while aircraftMsl is MSL (DTED takeoff terrain + height climbed).
            //   Subtracting one from the other carries the geoid separation — commonly order
            //   10-15 m. That is nothing at 1 km and about 18 degrees at 30 m, so if contacts
            //   render systematically LOW and worsen as you close on them, this is why.
            //
            // TERRAIN (fallback): ground elevation under the contact from DTED. Self-consistent
            // in MSL with no datum mixing, and accurate to roughly a person's height for anyone
            // on foot — but simply wrong for anything off the ground. Using reported altitude for
            // ground contacts makes them float in the sky and slew as the gimbal tilts.
            val targetGroundMsl = DtedIndex.elevationAt(context, lat, lon)
            val dzTerrain = if (targetGroundMsl != null && aircraftMsl != null) {
                targetGroundMsl - aircraftMsl
            } else {
                // No DTED under the target: assume it sits at our takeoff elevation (the
                // flat-plane assumption).
                -hud.relAlt
            }
            // The contact's hae, brought into the DTED frame when the aircraft has given us
            // the separation. Ground contacts still prefer terrain below; this is
            // for everything that is NOT on the ground.
            // ⚠ AIR TRACKS ARE NOT HAE. An ADS-B gateway puts the PRESSURE
            // altitude (29.92 datum) in `hae`, off by (29.92 - QNH) x 1000 ft — about 100-200 m —
            // plus a temperature term. Subtracting N "corrects" a datum the number never had,
            // so an air track's height is used AS IT CAME, and it is never printed as trusted.
            val isAir = category == ArSettings.Category.AIRCRAFT
            val reported = if (isAir) u.alt else reportedHaeToMsl(u.alt, geoidN)
            val dzReported = if (aircraftMsl != null && isUsableAltitude(u.alt)) {
                reported - aircraftMsl
            } else {
                null
            }
            // AIR TRAFFIC BALLPARK (no DTED, category == AIRCRAFT only): we don't know our own
            // MSL, but ADS-B still tells us roughly how high THEY are. Same "assume flat ground
            // at our takeoff elevation" convention as dzTerrain's else-branch and the pin
            // fallback above — their reported altitude, taken as height above that same assumed
            // ground, minus how far above it we currently are. This silently assumes our
            // takeoff point sits at the same true elevation their altimeter is referenced to,
            // which is why [dzIsTrusted] is false here — good enough to place the icon in
            // roughly the right place, not good enough to print a number and imply precision
            // that isn't there. Placing an airliner at our own level (the old fallback here)
            // was a worse approximation than this for the one category where altitude is the
            // entire point of drawing it.
            // The guard reads the RAW value (hae=0 is CoT's "unknown" from many senders,
            // and after a geoid subtraction it no longer looks like 0). Our own height without
            // DTED is the receiver's sea-level height, not "the takeoff point is at sea level"
            // (that would put the field's whole elevation into dz).
            val ownGpsMsl = if (hud.hae.isFinite() && geoidN != null) hud.hae - geoidN else null
            val dzAirBallpark = if (isAir && isUsableAltitude(u.alt)) {
                if (ownGpsMsl != null) reported - ownGpsMsl else reported - hud.relAlt
            } else null
            // GROUND CONTACTS USE TERRAIN, NOT THEIR OWN REPORTED ALTITUDE.
            //
            // Measured against a real contact, aircraft at 390ft AGL, target 253m out:
            //     dzReported = -95.8m   elevReported = -20.8 deg
            //     dzTerrain  = -108.0m  elevTerrain  = -23.1 deg
            // a 12.2m disagreement, which is exactly the local geoid separation the note above
            // predicts. It put the icon 2.3 deg high — visibly up and to the right of
            // the crosshair while the camera was aimed dead at the contact's map position.
            //
            // The datum mix is the whole cause: the contact publishes `hae` (height above the
            // WGS84 ELLIPSOID) and aircraftMsl is MSL, so subtracting one from the other carries
            // the separation. For anything whose CoT type says GROUND the question is moot — it is
            // standing on the terrain, and DTED is self-consistent in MSL with no datum to mix.
            //
            // ⚠ WHY THIS IS NOT JUST "TRUST TERRAIN ALWAYS": reported altitude remains the only
            // source that can be right for something NOT on the ground — an upper floor, a bridge,
            // aircraft. Those keep reported. The cost here is a ground contact genuinely on a
            // structure, which renders at street level; that is the better failure than every
            // ground contact floating a geoid's worth into the air.
            //
            // ⚠ AND DO NOT "FIX" THE RESIDUAL WITH THE PITCH OFFSET. The offset also feeds
            // lookPoint()/the SPI, so tuning it until an icon sits under the crosshair would trade
            // a cosmetic overlay error for wrong marker DROPS — which nobody downstream could see
            // was wrong. Pitch is calibrated against terrain the camera is aimed at, not against
            // where a contact icon lands.
            val preferTerrain = ArSettings.isGroundAffiliation(u.type)
            val dz = if (preferTerrain) dzTerrain else (dzReported ?: dzAirBallpark ?: dzTerrain)
            // Only a fully DTED-backed computation is trustworthy enough to LABEL with a number —
            // see drawAircraft. The air ballpark and the flat-terrain fallback both still drive
            // where the icon is drawn; they just don't get printed as text. When we prefer terrain
            // that means real DTED under the contact, not dzTerrain's flat-plane else-branch.
            val dzIsTrusted = if (preferTerrain) (targetGroundMsl != null && aircraftMsl != null)
                              else (dzReported != null && !isAir)   // never for air tracks

            if (kotlin.math.hypot(groundDist, dz) < MIN_RANGE_M) { skipped++; continue }

            val bearing = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, lat, lon)
            val dBearing = ((bearing - pose.bearingDeg + 540.0) % 360.0) - 180.0
            val elevDeg = Math.toDegrees(atan2(dz, groundDist))

            val xy = project(dBearing, elevDeg, pose.pitchDeg)

            // Trace the first few in detail, showing BOTH height methods side by side. The
            // whole point is that "reported" and "terrain" disagree by the geoid offset, and
            // whichever one puts the marker on the actual person is the one to keep — that is
            // an observation to make in the field, not a call to settle from a desk. The
            // elevation angle each would produce is spelled out because degrees, not metres,
            // are what moves the icon on screen.
            if (logThisPass && detailed < CONTACT_DETAIL_LIMIT) {
                detailed++
                val elevRep = dzReported?.let { Math.toDegrees(atan2(it, groundDist)) }
                val elevTer = Math.toDegrees(atan2(dzTerrain, groundDist))
                // TWO LINES PER CONTACT, ON PURPOSE. The `height` line is the contact-only
                // question — reported and terrain disagree by the geoid offset, and which one
                // lands on the actual person is a field observation. The `geom` line is the
                // SAME format a pin gets, so a contact's placement can be read straight against
                // a dropped pin's. One line carrying both ran past 300 characters and wrapped
                // in the log, which is where a number gets misread.
                val who = "contact='${u.callsign ?: u.uid}'"
                AppLog.d(
                    TAG,
                    "$who type=${u.type} height: " +
                        "dzReported=%s dzTerrain=%.1fm | elevReported=%s elevTerrain=%.1f | using=%s".format(
                            dzReported?.let { "%.1fm".format(it) } ?: "none",
                            dzTerrain,
                            elevRep?.let { "%.1f".format(it) } ?: "none",
                            elevTer,
                            if (preferTerrain) "terrain(ground)"
                            else if (dzReported != null) "reported" else "terrain",
                        ),
                )
                AppLog.d(
                    TAG,
                    "$who geom: " + geometryTrace(
                        pose, groundDist, dz, bearing, dBearing, elevDeg, xy,
                    ),
                )
            }

            if (xy == null) {
                offFrame++
                drawEdgeArrow(canvas, dBearing, elevDeg, pose.pitchDeg, TakMapMarkers.teamColor(u.team))
                continue
            }
            // Label budget: icons stay (they're the position information), but past this many
            // on-screen contacts the name+range plates overlap into an unreadable mass and
            // start obscuring the video, which is a flight-safety regression rather than an
            // aesthetic one. Nearest are drawn first, so the ones that lose their label are the
            // furthest away.
            drawContact(canvas, xy.first, xy.second, u, category, dz, dzIsTrusted, withLabel = drawn < MAX_LABELS)
            drawn++
        }

        // A count for the rest: a busy TAK picture is a dozen-plus contacts, and tracing every
        // one at 1Hz would bury the per-pin detail that actually needs reading.
        if (logThisPass && users.isNotEmpty()) {
            AppLog.d(TAG, "contacts: ${users.size} known, $drawn drawn, $offFrame off-frame, " +
                "$skipped skipped" +
                // ⚠ WHERE THE ARROW ACTUALLY LANDED. An edge arrow that is clamped off the
                // visible area is INVISIBLE rather than misplaced, which looks exactly like an
                // arrow that was never drawn. The count alone could not
                // tell those apart; this can.
                if (offFrame > 0) " | last arrow %.0f,%.0f in %.0fx%.0f"
                    .format(lastArrowX, lastArrowY, width.toFloat(), height.toFloat()) else "")
        }
    }

    /**
     * Air traffic: a diamond rather than a dot or a 2525 ground frame, so it cannot be confused
     * with anything on the ground, plus its altitude in the label.
     *
     * **Altitude is the point** for an aircraft — the pilot's question is "how far above me is
     * that" — so it earns label space where a ground contact's range did not. Taken from the CoT
     * `hae`; the ADS-B gateway sources that from BAROMETRIC altitude, so treat it as good to a
     * couple of hundred feet rather than exact.
     *
     * **The number is only printed when [dzIsTrusted]** — the DTED-backed computation, both our
     * own MSL and theirs differenced properly. Without DTED, [dzMeters] still places the icon
     * (the flat-ground ballpark in [drawContacts] beats pinning every aircraft to our own
     * level), but that ballpark silently assumes our takeoff point sits at the same true
     * elevation their altimeter is referenced to — close enough to point the icon at roughly the
     * right spot, not close enough to print as a number implying precision that isn't there.
     * Callsign only in that case.
     *
     * No heading rotation, by choice: CotParser keeps the course (and the speed, used to move
     * the track to now), but a symmetric diamond reads cleanly at this size.
     */
    private fun drawAircraft(
        canvas: Canvas, x: Float, y: Float, u: TakUser, dzMeters: Double, dzIsTrusted: Boolean,
        withLabel: Boolean,
    ) {
        val r = 6f * d
        arrowPath.reset()
        arrowPath.moveTo(x, y - r)
        arrowPath.lineTo(x + r, y)
        arrowPath.lineTo(x, y + r)
        arrowPath.lineTo(x - r, y)
        arrowPath.close()
        dotFill(if (u.isStale) Color.GRAY else AIRCRAFT_COLOR)
        canvas.drawPath(arrowPath, dotPaint)
        canvas.drawPath(arrowPath, dotRing)
        if (!withLabel) return
        val callsign = labelSafe(u.callsign ?: u.uid, MAX_CALLSIGN_CHARS)
        // RELATIVE height, signed — "+2400 ft" is the question a pilot is actually asking of
        // another aircraft, and unlike its MSL altitude it is self-checking: a track labelled
        // +2400 ft that renders near the horizon is visibly wrong, where "2900 ft" looks
        // plausible whatever the icon does.
        val text = if (dzIsTrusted) {
            "%s  %s%s".format(callsign, if (dzMeters >= 0) "+" else "-", Units.feet(abs(dzMeters)))
        } else {
            callsign
        }
        drawLabel(canvas, x, y + r, text)
    }

    /**
     * Is a CoT-reported altitude actually a number we can use?
     *
     * **`9999999.0` is the TAK convention for "unknown"**, not a real height, and common
     * gateways emit it: a METAR weather gateway may set `hae="9999999.0"` on every station
     * marker, as may a seismic gateway. Taken literally that places a marker ~10,000 km
     * overhead, far outside any field of view, and turns every nearby weather station into a
     * bogus edge arrow pinned to the top of the frame. A city can have several reporting
     * stations inside AR range, so this is not a theoretical case.
     *
     * Also rejects exact 0.0, which CoT uses about as loosely — CloudTAK self-markers publish it
     * (`"center": [lon, lat, 0]`) when they have no altitude fix.
     */
    private fun isUsableAltitude(alt: Double): Boolean =
        alt.isFinite() && alt != 0.0 && abs(alt) < UNKNOWN_ALT_SENTINEL

    /** 2525 frame for a placed marker; team-coloured dot for a PLI/unit. Grey when stale. */
    private fun drawContact(
        canvas: Canvas, x: Float, y: Float, u: TakUser, category: ArSettings.Category,
        dzMeters: Double, dzIsTrusted: Boolean, withLabel: Boolean,
    ) {
        val label = labelSafe(u.callsign ?: u.uid, MAX_CALLSIGN_CHARS)
        if (category == ArSettings.Category.AIRCRAFT) {
            drawAircraft(canvas, x, y, u, dzMeters, dzIsTrusted, withLabel)
            return
        }
        // A live client is a position and not a placed marker. It takes a dot, never a frame.
        // The parser decides what is a live client — see CotParser.isLiveClient. Same rule as
        // the map.
        val milRes = if (u.isLiveClient) null else TakMapMarkers.milMarkerRes(u.type)
        if (milRes != null) {
            val size = (ICON_DP * d).toInt()
            val bmp = iconCache.getOrPut(milRes) {
                TakMapMarkers.drawableToBitmap(context, milRes, size) ?: run {
                    AppLog.w(TAG, "contact icon failed to rasterise — not drawn")
                    return
                }
            }
            canvas.drawBitmap(bmp, x - size / 2f, y - size / 2f,
                if (u.isStale) staleIconPaint else iconPaint)
            if (withLabel) drawLabel(canvas, x, y + size / 2f, label)
        } else {
            val r = 5.25f * d
            dotFill(if (u.isStale) Color.GRAY else TakMapMarkers.teamColor(u.team))
            canvas.drawCircle(x, y, r, dotPaint)
            canvas.drawCircle(x, y, r, dotRing)
            if (withLabel) drawLabel(canvas, x, y + r, label)
        }
    }

    /**
     * The target's pixel in the picture, or null if it is behind the camera or outside the frame.
     *
     * [dBearingDeg] is the target's bearing minus the camera's, [elevDeg] the target's elevation
     * from the camera and [pitchDeg] the camera's pitch, both down-negative. The geometry is
     * [ArProjection]'s: the target's direction rotated into the camera frame and divided by its
     * forward component (the class note, items 1 and 4). Taking the rotation from
     * [cameraFrameAngles] and the divide as `tan` of its two angles gives the same number for
     * anything in front of the camera, but needs a ±85° guard to stand in for "behind the
     * camera"; ArProjection answers that from the forward component instead.
     */
    private fun project(dBearingDeg: Double, elevDeg: Double, pitchDeg: Double): Pair<Float, Float>? {
        val zoom = TakBridgeHolder.currentZoomFactor
        val n = ArProjection.project(
            dBearingDeg, elevDeg, pitchDeg, AutelTakBridge.hFovDeg(zoom), AutelTakBridge.vFovDeg(zoom),
        ) ?: return null
        val (nx, ny) = n
        if (abs(nx) > 1.0 || abs(ny) > 1.0) return null   // outside the camera's field of view

        val x = videoRect.centerX() + (nx * videoRect.width() / 2.0).toFloat()
        // Screen Y grows downward, camera elevation grows upward — hence the subtraction.
        val y = videoRect.centerY() - (ny * videoRect.height() / 2.0).toFloat()

        // TWO DIFFERENT TESTS, and conflating them was a bug.
        //
        // The check above asks "is this inside the camera's FIELD OF VIEW" and must be measured
        // against videoRect, which is the whole video frame. The check below asks "can the pilot
        // actually SEE it", which is a different question as soon as the frame is cropped to fill
        // the screen: videoRect deliberately overflows the view, so a target in a cropped-away
        // side band passes the first test and lands at a coordinate like x=-232.
        //
        // Without this it was drawn there — invisibly — instead of becoming an edge arrow, so
        // contacts in the crop bands silently vanished while the log cheerfully counted them as
        // "drawn". Returning null hands them to the same edge-arrow path as anything else the
        // pilot cannot see, which is what an arrow is FOR: it points at what is off screen.
        if (x < 0f || y < 0f || x > width || y > height) return null
        return x to y
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, pin: TakDropMarkers.PinInfo) {
        val size = (ICON_DP * d).toInt()
        // MUST rasterise through the drawable, not BitmapFactory. The affiliation markers are
        // VectorDrawable XML, and BitmapFactory.decodeResource returns null for those — the
        // original version of this silently drew nothing while the projection logged a correct
        // on-screen position, which is about the most misleading failure available. The mini-map
        // has always done it this way; reuse it rather than keeping a second rasteriser.
        val bmp = iconCache.getOrPut(pin.affiliation.res) {
            TakMapMarkers.drawableToBitmap(context, pin.affiliation.res, size)
                ?: run {
                    // Loud, not silent: a marker the pilot cannot see is a marker they will
                    // assume is not there.
                    AppLog.w(TAG, "icon ${pin.affiliation.label} failed to rasterise — not drawn")
                    return
                }
        }
        canvas.drawBitmap(bmp, x - size / 2f, y - size / 2f, iconPaint)
        drawLabel(canvas, x, y + size / 2f, pin.name)
    }

    /**
     * Marker for something outside the frame, pinned to the edge in its direction.
     *
     * Worth having rather than just dropping off-frame targets: "there is a marker 40° to your
     * left" is the cue that tells a pilot which way to yaw. Without it the overlay is silent
     * about everything it can't currently see, which reads as "there is nothing there".
     *
     * Clamped into the video rect with a margin so an arrow never lands under the toolbar or
     * outside the image, and drawn as a triangle pointing outward along the direction to the
     * target rather than a plain dot, so the direction is readable at a glance.
     */
    /** Where the last edge arrow was drawn, for the diagnostic line — see the contacts summary. */
    private var lastArrowX = Float.NaN
    private var lastArrowY = Float.NaN

    private fun drawEdgeArrow(canvas: Canvas, dBearingDeg: Double, elevDeg: Double, pitchDeg: Double, color: Int) {
        val zoom = TakBridgeHolder.currentZoomFactor
        // ONE DIRECTION, NOT TWO CLAMPED AXES. Clamping each axis on its own puts every target
        // behind the camera in a corner, and an arrow can then point up to 61° away from its
        // target. The direction is THE SAME CAMERA-FRAME VECTOR [project] USES
        // (ArProjection.edgeDirection): in front, the projected point pushed out along its ray to
        // the edge; behind, the vector's lateral and up components still say which way to turn,
        // where a perspective divide would blow up. Rebuilding it from the two angles
        // (R ∝ sin az·|cos el|, U ∝ sin el·|cos az|) gives the same ray in front of the camera.
        val dir = ArProjection.edgeDirection(
            dBearingDeg, elevDeg, pitchDeg, AutelTakBridge.hFovDeg(zoom), AutelTakBridge.vFovDeg(zoom),
        )
        val nx = dir.first
        var ny = -dir.second   // screen y grows downward
        if (abs(nx) < 1e-9 && abs(ny) < 1e-9) ny = 1.0   // dead behind: point down, the turn is either way
        val margin = 16f * d
        val cx = videoRect.centerX()
        val cy = videoRect.centerY()
        // ⚠ **CLAMP TO THE VIEW, NOT TO videoRect.** This is the whole point of an edge arrow
        // and it is easy to get wrong.
        //
        // videoRect is the WHOLE video frame including the part cropped away by the fill — it
        // DELIBERATELY overflows the view, which is what lets a cropped-away target project
        // outside and be called off-frame (see FlightActivity's setVideoRect). Measured on the
        // controller: `AR video rect: 2730x1535 in view 2048x1536`, so its edges sit 341px off
        // EITHER side of the screen. An arrow clamped to that rect is clamped to somewhere the
        // pilot cannot see, and an invisible arrow looks exactly like an arrow that was never
        // drawn.
        //
        // A wide right-hand inset can mask the fault on that edge, but nothing masks it on the
        // left. It only LOOKS correct in photo mode, where the frame aspect matches the view,
        // scale is 1.0 and videoRect is the view.
        //
        // So: intersect with the view first, then apply the chrome rules. Seen in the field:
        // air traffic directly overhead produced an above-frame arrow the pilot could never
        // see, which is the one case the indicator matters most.
        val visLeft = maxOf(videoRect.left, 0f, chromeInsetLeft) + margin
        // ⚠ THE RIGHT-HAND MARGIN IS THE COLUMN'S OWN PADDING, HALVED — the arrow rides in the
        // clear strip beside the readouts rather than in their glyphs. Halved because the
        // margin places the arrow's CENTRE and the arrow has a radius: centre at half the gap
        // puts both of its edges inside the strip. A gap too narrow to hold the whole arrow
        // would push it back into the text, thus the ordinary margin is kept in that case and
        // the arrow overlaps the text — visibly, rather than hidden.
        val rightMargin = if (chromeRightGap >= 2f * ARROW_RADIUS_DP * d) chromeRightGap / 2f
                          else margin
        val visRight = minOf(videoRect.right, width.toFloat()) - rightMargin
        val visTop = maxOf(videoRect.top, 0f) + chromeInsetTop + margin
        val visBottom = minOf(videoRect.bottom, height.toFloat()) - margin
        // A view too small to hold the margins would make coerceIn throw (min > max). Nothing
        // useful can be drawn there anyway.
        if (visRight <= visLeft || visBottom <= visTop) return
        // ⚠ THE ARROW GOES ALL THE WAY TO THE RIGHT EDGE, UNDER THE HUD COLUMN (design
        // decision). That column is outlined text with no panels behind it, so an arrow
        // behind it is visible; a full-width inset would cost a fifth of the screen on the edge
        // a pilot scans most. See setChromeInsets for what is still excluded and why.
        var x = (cx + nx.toFloat() * (videoRect.width() / 2f - margin)).coerceIn(visLeft, visRight)
        var y = (cy + ny.toFloat() * (videoRect.height() / 2f - margin)).coerceIn(visTop, visBottom)
        // THE MINI-MAP IS THE ONE OPAQUE THING LEFT ON THIS EDGE. Lift the arrow to just above
        // it rather than pushing it inboard: it keeps its place on the right edge, where its
        // direction still reads, instead of moving a fifth of the screen away from the edge it
        // is pointing at.
        if (x > chromeMapLeft && y > chromeMapTop) y = chromeMapTop - margin
        lastArrowX = x
        lastArrowY = y

        val angle = atan2((y - cy).toDouble(), (x - cx).toDouble())
        val r = ARROW_RADIUS_DP * d
        arrowPath.reset()
        arrowPath.moveTo(
            x + (r * kotlin.math.cos(angle)).toFloat(),
            y + (r * kotlin.math.sin(angle)).toFloat(),
        )
        val spread = Math.toRadians(140.0)
        arrowPath.lineTo(
            x + (r * kotlin.math.cos(angle + spread)).toFloat(),
            y + (r * kotlin.math.sin(angle + spread)).toFloat(),
        )
        arrowPath.lineTo(
            x + (r * kotlin.math.cos(angle - spread)).toFloat(),
            y + (r * kotlin.math.sin(angle - spread)).toFloat(),
        )
        arrowPath.close()
        dotFill(color)
        canvas.drawPath(arrowPath, dotPaint)
        canvas.drawPath(arrowPath, dotRing)
    }

    /**
     * Name plate, hung just below the symbol. Shared by pins and contacts so the two can't
     * drift apart visually. [symbolBottom] is the bottom edge of whatever was drawn.
     *
     * Name only — range is deliberately left off (design decision): it roughly
     * doubles the plate width, and in a crowded picture the plates are the thing that overlaps
     * and starts hiding the video. Range is still available on the mini-map and, for our own
     * pins, in the markers list.
     */
    /**
     * Trims network-sourced text to something drawable: length, which
     * makes a label span the video, and control characters. A clipped callsign ends in an
     * ellipsis so it is visibly clipped — a pilot must not read it as the whole one.
     */
    private fun labelSafe(raw: String, max: Int): String {
        val cleaned = if (raw.any { it < ' ' }) {
            buildString(raw.length) { for (c in raw) append(if (c < ' ') ' ' else c) }
        } else raw
        return if (cleaned.length <= max) cleaned else cleaned.take(max - 1) + "…"
    }

    private fun drawLabel(canvas: Canvas, x: Float, symbolBottom: Float, name: String) {
        labelPaint.textSize = LABEL_SP * d
        // Backstop: every label funnels through here.
        val text = labelSafe(name, MAX_LABEL_CHARS)
        val tw = labelPaint.measureText(text)
        val fm = labelPaint.fontMetrics
        val top = symbolBottom + 4 * d
        canvas.drawRoundRect(
            x - tw / 2 - 5 * d, top, x + tw / 2 + 5 * d, top + (fm.descent - fm.ascent) + 3 * d,
            3 * d, 3 * d, labelBg,
        )
        canvas.drawText(text, x, top - fm.ascent + 1.5f * d, labelPaint)
    }

    /**
     * Per-pin projection trace, throttled to once a second.
     *
     * Exists because a pin that projects outside the frame is otherwise discarded in total
     * silence — the overlay looks identical whether the maths is right and the target is
     * genuinely off-screen, or the maths is wrong. These are the numbers needed to tell those
     * apart: if `dBrg` is near zero the camera really is pointed at the pin, so an off-frame
     * result means the FOV or the projection is at fault, not the pose.
     */
    /**
     * The placement geometry of ONE target, in the one format both traces use.
     *
     * ⚠ **PINS AND CONTACTS PRINT THE SAME FIELDS, SO THE TWO LINES CAN BE READ AGAINST
     * EACH OTHER** (design decision). Without that, the one question a pilot actually has in
     * the air, "is a marker somebody ELSE placed landing where one of mine would," cannot be
     * answered from the log at all. A pin is the ground truth (it is
     * placed at [TakBridgeHolder.lookPoint], derived from this same pose, so it must render
     * under the crosshair); a contact at the same spot must produce the same camera-frame
     * angles. Differing fields made that comparison impossible to eyeball.
     *
     * `tgtBrg`/`tgtElev` are deliberately named for the TARGET rather than `pinBrg`/`pinElev`,
     * because one name across both lines is the entire point.
     *
     * `roll` is the gimbal's reported roll, printed raw. It is here to be READ, not acted on:
     * nothing projects with it, and a test with the gimbal rolled is what settles its sign. See [AutelTakBridge.liveGimbalRoll].
     */
    private fun geometryTrace(
        pose: AutelTakBridge.CameraPose,
        groundDist: Double,
        dz: Double,
        bearing: Double,
        dBearing: Double,
        elevDeg: Double,
        xy: Pair<Float, Float>?,
    ): String {
        // The camera-frame angles are printed for READING; the projection takes the world
        // angles and the pitch and does this rotation itself.
        val (azCam, elCam) = cameraFrameAngles(dBearing, elevDeg, pose.pitchDeg)
        val roll = TakBridgeHolder.gimbalRollDeg()
        return "gDist=%.1fm dz=%.1fm | camBrg=%.1f tgtBrg=%.1f dBrg=%.1f | ".format(
            groundDist, dz, pose.bearingDeg, bearing, dBearing,
        ) + "camPitch=%.1f roll=%s tgtElev=%.1f | cam az=%.1f el=%.1f | fov=%.0fx%.0f | %s".format(
            pose.pitchDeg,
            roll?.let { "%.1f".format(it) } ?: "n/a",
            elevDeg, azCam, elCam,
            // EFFECTIVE fov, zoom included — printing the 1x base while zoomed is
            // actively misleading during calibration, which is when this gets read.
            AutelTakBridge.hFovDeg(TakBridgeHolder.currentZoomFactor),
            AutelTakBridge.vFovDeg(TakBridgeHolder.currentZoomFactor),
            if (xy == null) "OFF-FRAME (not drawn)"
            else "drawn at %.0f,%.0f in rect %.0f,%.0f-%.0f,%.0f".format(
                xy.first, xy.second,
                videoRect.left, videoRect.top, videoRect.right, videoRect.bottom,
            ),
        )
    }

    private fun diag(
        pin: TakDropMarkers.PinInfo,
        pose: AutelTakBridge.CameraPose,
        groundDist: Double,
        dz: Double,
        bearing: Double,
        dBearing: Double,
        elevDeg: Double,
        xy: Pair<Float, Float>?,
    ) {
        AppLog.d(
            TAG,
            "pin='${pin.name}' " +
                geometryTrace(pose, groundDist, dz, bearing, dBearing, elevDeg, xy),
        )
    }

    private var lastDiagMs = 0L

    private fun skipped(reason: String) {
        if (lastSkipReason != reason) {
            lastSkipReason = reason
            AppLog.v(TAG, "AR not drawing: $reason")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
        iconCache.clear()
    }

    companion object {
        private const val TAG = "TP2Ar"

        /** Process-wide mirror of [isRunning] — only one ArOverlayView instance exists at a
         *  time (the flight screen's), but callers elsewhere in the app (video-health logging)
         *  shouldn't need a view reference just to ask "is AR compositing over the video right
         *  now," the same way [VideoStreamerHolder]'s state is queried
         *  from anywhere without a streamer reference. */
        @Volatile
        var isRunningAnywhere: Boolean = false

        private const val REFRESH_MS = 100L
        // A QUARTER SMALLER than 26 dp and 11 sp, which crowd the picture when three markers
        // share a yard. The team dot and the air-traffic diamond (drawContact, drawAircraft) are
        // a quarter smaller too; the edge arrows are not — they are cues, not markers. ⚠ Sized
        // on a phone screen; not yet checked on the Smart Controller.
        private const val ICON_DP = 19.5f
        /** The edge arrow's radius. Named because the right-hand clearance is sized from it —
         *  see the right margin in [drawEdgeArrow]. */
        private const val ARROW_RADIUS_DP = 7f
        private const val LABEL_SP = 8.25f
        /** Slant range below which the pin is effectively at the camera and the angles stop
         *  meaning anything. Deliberately compared against slant range, not ground distance —
         *  see the loop. */
        private const val MIN_RANGE_M = 2.0
        // Range caps all live in ArSettings.rangeMeters() — ground fixed, air pilot-selectable.
        /** TAK's "unknown altitude" sentinel — see isUsableAltitude. */
        private const val UNKNOWN_ALT_SENTINEL = 999_999.0
        private const val MAX_CALLSIGN_CHARS = 24
        private const val MAX_LABEL_CHARS = 40
        /** Longest an air track is dead-reckoned forward. */
        private const val AIR_PROPAGATE_MAX_S = 8.0
        /** Projection trace cadence — the draw loop runs at 10Hz, which is far too fast to log. */
        private const val DIAG_INTERVAL_MS = 1000L
        /** How many contacts get a full trace line per pass; the rest are counted. */
        private const val CONTACT_DETAIL_LIMIT = 3
        /** On-screen contacts that get a name+range plate; the rest keep just their icon. */
        private const val MAX_LABELS = 6

        /**
         * Opacity of everything this overlay draws, 0-255 (design decision).
         *
         * The overlay composites on top of live video the pilot is flying from, and a screen with
         * several contacts on it was hiding real ground. Semi-transparent keeps the markers
         * readable while leaving the picture underneath visible — the same trade the HUD panels
         * make (see bg_hud_panel.xml).
         *
         * Applied at every paint rather than via canvas.saveLayerAlpha: a full-screen offscreen
         * layer allocated ten times a second is real GPU cost on this hardware, and the FPV
         * pipeline is the one thing that must not regress.
         */
        private const val OVERLAY_ALPHA = 190
        /** Edge arrows for our own pins use the app's marker-drop accent, not a team colour. */
    }
}
