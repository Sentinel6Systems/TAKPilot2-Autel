package com.autel.sdksample.tak

import android.app.Activity
import android.app.AlertDialog
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.Switch
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * The confirmation in front of every locked setting: the dialog says what unlocking does, and its
 * confirm button stays disabled until the switch in it is turned on.
 *
 * ⚠ **IT STOPS A CHANGE MADE BY ACCIDENT, AND NOTHING ELSE.** A stray tap, a glove, a curious
 * hand. Two separate actions — throw the switch, then press the button — are what an accident
 * does not do. It is not access control: anyone who means to change a setting can, and that is
 * by design, so a pilot in the field can always recover a controller. There is deliberately no
 * password: a shared one protects nothing once it is known, and a per-device one locks out the
 * pilot who needs it.
 *
 * Built in code rather than from a layout: one switch, several call sites, and a layout file
 * would imply this dialog can grow. A programmatic view takes the PLATFORM's colours rather than
 * the app theme's, so the text colour is set explicitly or it renders black-on-black.
 */
object UnlockConfirm {

    /** The switch's label wherever the caller does not name the change more precisely. */
    const val DEFAULT_SWITCH_LABEL = "I want to change these settings"

    /**
     * Shows the confirmation. [onConfirmed] runs only when the switch is on and [confirmLabel]
     * is pressed. Cancel, Back and a touch outside the dialog all run [onCancelled] instead, so
     * the only way out with the change made is the switch and the button.
     */
    fun show(
        activity: Activity,
        title: CharSequence,
        message: CharSequence?,
        confirmLabel: String,
        switchLabel: String = DEFAULT_SWITCH_LABEL,
        onCancelled: () -> Unit = {},
        onConfirmed: () -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        // Themed as the dialog, so the switch takes the dialog's accent and not the screen's.
        val toggle = Switch(ContextThemeWrapper(activity, R.style.TakDialogTheme_Destructive)).apply {
            text = switchLabel
            textSize = 15f
            isChecked = false
            setTextColor(ContextCompat.getColor(activity, R.color.tp_text_primary))
            val pad = (8 * density).toInt()
            setPadding(0, pad, 0, pad)
        }
        val wrap = FrameLayout(activity).apply {
            val padH = (16 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(toggle)
        }
        var confirmed = false
        val dialog = AlertDialog.Builder(activity, R.style.TakDialogTheme_Destructive)
            .setTitle(title)
            .setMessage(message)
            .setView(wrap)
            .setPositiveButton(confirmLabel) { _, _ ->
                if (toggle.isChecked) {
                    confirmed = true
                    onConfirmed()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        // The button exists only once the dialog is shown. It starts disabled and follows the
        // switch, so the confirm cannot be pressed until the switch has been thrown.
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.isEnabled = toggle.isChecked
            toggle.setOnCheckedChangeListener { _, on -> confirm.isEnabled = on }
        }
        // Dismiss covers Cancel, Back and a touch outside. It also follows a confirm, which the
        // flag tells apart.
        dialog.setOnDismissListener { if (!confirmed) onCancelled() }
        dialog.show()
    }
}
