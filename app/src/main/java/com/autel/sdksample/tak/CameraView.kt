package com.autel.sdksample.tak

/**
 * What the camera SHOWS: the visible lens, the thermal lens, or the thermal lens drawn into
 * the centre of the visible picture. One value drives the wire, the fill rule and the pill.
 *
 * ⚠ **NOT A BOOLEAN.** A single `irOn` flag cannot be the lens model, because a blend mode is
 * neither true nor false. Instead of "is IR on", every consumer asks the question it really
 * has: [expectsThermalFrame] for the frame-shape check, [fitsWhole] for the fill rule,
 * [hasThermal] for the palette, [lens] for the bridge.
 *
 * ⚠ **PIP IS THE ONLY BLEND THIS FIRMWARE ACCEPTS** (XL726, firmware V5.0.6.49, measured). [s6-allow: a firmware version, not an IP address]
 * `SetDisplayMode Overlap` answers status -1 in every spelling, while
 * `PictureInPicture` answers 0 and reads back. The SDK enum still lists OVERLAP; a camera that
 * reports it (another firmware, Autel Explorer) is treated as this view, because it is a
 * blend with the video's shape and the visible lens's field, which is all this application
 * needs to know about it.
 *
 * **What PIP is on this camera, measured.** The thermal picture is drawn INTO THE CENTRE of
 * the visible frame at the visible lens's angular scale — the fence line, the tree trunks and
 * the utility boxes in the window line up with the same objects outside it. That is what
 * makes it a "meshed" view rather than a corner box. The registration is the camera's; this
 * application writes no offset (`SetIrPosition` exists, is clamped to ±20 px, and was 0,0 and
 * correct as measured). The downlink stays one 1280x720 stream, so the application cannot
 * composite this itself — it can only ask the camera to.
 *
 * No SDK import on purpose, so the mapping is testable without the aar's Android half — see
 * [LensFramePolicy] for the same convention.
 */
enum class CameraView(
    /** The SDK `DisplayMode` constant name this view is written and read as. */
    val displayModeName: String,
    /** The bridge's lens: what the published camera-point cone is based on. */
    val lens: AutelTakBridge.Lens,
) {
    /** The visible (RGB) camera. The IR pill is unlit and the PIP pill is gone. */
    VISIBLE("VISIBLE", AutelTakBridge.Lens.EO),
    /** Full-screen thermal. The IR pill is lit and the PIP pill shows, unlit. */
    IR("IR", AutelTakBridge.Lens.IR),
    /** Thermal drawn into the centre of the visible picture, by the camera. Both pills lit. */
    PIP("PICTURE_IN_PICTURE", AutelTakBridge.Lens.BLEND);

    /** The thermal sensor is on screen, thus its palette applies. In PIP the palette colours
     *  the centre window (white hot as measured). */
    val hasThermal: Boolean get() = this != VISIBLE

    /** Shown whole (FIT) rather than filled: only the bare thermal sensor, which has no
     *  resolution to give away. PIP is a 16:9 composite and fills like the visible modes. */
    val fitsWhole: Boolean get() = this == IR

    /** The shape the frame-size witness expects — see [lensAgreesWithFrame]. PIP arrives as
     *  the 1280x720 composite, measured (`MAX_0004` 1280x720p25). */
    val expectsThermalFrame: Boolean get() = this == IR

    /** The IR pill is lit (green) when the thermal sensor is on screen: full IR or PIP. */
    val irLit: Boolean get() = hasThermal

    /**
     * THE TWO PILLS (design decision). This replaces an earlier PIP pill and
     * its expand / contract pill (a three-step cycle through all views was also tried and
     * found to be a chore).
     *
     * [next] is the IR pill and the C1 key: a TWO-WAY toggle, RGB ↔ full-screen IR. From PIP
     * it goes back to RGB: the IR pill turns the thermal camera off, whatever view it is in.
     */
    val next: CameraView get() = when (this) {
        VISIBLE -> IR
        IR -> VISIBLE
        PIP -> VISIBLE
    }

    /**
     * The view the PIP pill goes to. The pill shows only while the thermal sensor is on
     * screen: from full IR it enters PIP, and from PIP it goes back to full IR. Null in RGB,
     * where the pill is gone.
     */
    val pipTarget: CameraView? get() = when (this) {
        IR -> PIP
        PIP -> IR
        VISIBLE -> null
    }

    /** The PIP pill is lit (green) in PIP only. */
    val pipLit: Boolean get() = this == PIP

    companion object {
        /**
         * The view for a `DisplayMode` the camera reported, by the constant's name.
         * OVERLAP maps to [PIP] (see the class note); UNKNOWN and null map to [VISIBLE], the
         * state the buttons showed before the camera answered, so a failed read changes nothing.
         */
        fun fromDisplayModeName(name: String?): CameraView = when (name) {
            "IR" -> IR
            "PICTURE_IN_PICTURE", "OVERLAP" -> PIP
            else -> VISIBLE
        }
    }
}
