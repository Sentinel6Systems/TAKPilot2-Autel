package com.autel.sdksample.tak

import android.annotation.SuppressLint
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.widget.EditText
import com.autel.sdksample.R

/**
 * The eye at the end of a password or passphrase field: a touch shows what is typed, a second
 * touch hides it again.
 *
 * It replaces the "Show passwords" check box. One box for a whole page was off the screen from
 * most of the fields it governed, so a pilot could not see the field and the box together.
 *
 * ⚠ A FIELD THAT IS DISABLED DOES NOT SHOW ITS TEXT. Android does not call a touch listener on
 * a disabled view, and a configuration lock disables its fields, so a locked field cannot be
 * shown. The owner of the lock also calls [mask] when it locks, so a field that was shown
 * does not stay shown behind the lock.
 *
 * Nothing is saved: a field is masked each time its screen is built.
 */
object PasswordEye {

    /** Width of the touch zone at the end of the field, in dp. Wider than the icon: gloves. */
    private const val ZONE_DP = 48

    @SuppressLint("ClickableViewAccessibility")
    fun attach(field: EditText) {
        mask(field)
        val zonePx = ZONE_DP * field.resources.displayMetrics.density
        field.setOnTouchListener { _, event ->
            // Left-to-right layouts only, as the rest of this application.
            val inZone = event.x >= field.width - zonePx
            if (!inZone) return@setOnTouchListener false
            if (event.action == MotionEvent.ACTION_UP) {
                if (field.transformationMethod == null) mask(field) else show(field)
            }
            // The touch is used up, so it does not also move the cursor or open the keyboard.
            true
        }
    }

    /** Hides the text and draws the plain eye. */
    fun mask(field: EditText) = set(field, shown = false)

    private fun show(field: EditText) = set(field, shown = true)

    private fun set(field: EditText, shown: Boolean) {
        val cursor = field.selectionEnd
        field.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
        if (cursor >= 0) field.setSelection(cursor.coerceAtMost(field.text.length))
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye, 0)
    }
}
