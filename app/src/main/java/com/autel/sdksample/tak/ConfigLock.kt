package com.autel.sdksample.tak

import android.app.Activity
import android.view.View
import com.taklite.util.AppLog

/**
 * The configuration lock — one implementation, used by every screen that has one.
 *
 * It was a private pair of methods on [TakConnectActivity] until the TAK server
 * configuration moved to [TakServerActivity] and a second screen needed the same lock.
 * Copying it would have been the obvious thing and the wrong one: the two rules below are
 * SAFETY rules that were each arrived at from a real fault, and a copy is how one screen keeps
 * them while the other quietly stops.
 *
 * ⚠ **RULE 1: LOCKED IS NOT HIDDEN.** A lock stops a CHANGE. It never stops a pilot READING the
 * state — the scope of this aircraft, where its video goes, what the aircraft was told. Any
 * treatment that makes the current value harder to read is wrong, however tidy it looks.
 *
 * ⚠ **RULE 2: A CONTROL WHOSE STATE IS ITS TICK MUST NOT BE DIMMED.** [apply] dims to 45 %,
 * which on a check box or a radio button greys the TICK as well as the label, and the tick is
 * the information. Such controls are kept OUT of the field list and are locked by dropping
 * their `isClickable`/`isFocusable` instead — see the `afterChange` hook, which exists for
 * exactly that. This was found twice: once on the channel rows, once on the active-server
 * toggle.
 *
 * Unlocking asks for confirmation; locking does not. The asymmetry is deliberate — locking is
 * the safe direction, and gating it would just train people to dismiss dialogs. See
 * [UnlockConfirm] for what the confirmation is and is not protecting against.
 */
object ConfigLock {

    /**
     * Wires one lock check box to one set of fields.
     *
     * @param afterChange run after the lock state settles, for controls [apply] cannot reach by
     *   id — rows built in code, and the controls rule 2 keeps out of [fieldIds].
     */
    fun install(
        activity: Activity,
        checkBoxId: Int,
        prefKey: String,
        fieldIds: List<Int>,
        confirmTitle: String,
        confirmBody: String,
        afterChange: (Boolean) -> Unit = {},
    ) {
        val box = activity.findViewById<android.widget.CheckBox>(checkBoxId) ?: return
        val prefs = activity.getSharedPreferences(
            TakConnectActivity.PREFS, Activity.MODE_PRIVATE)
        // Default UNLOCKED on a fresh install — a first-run pilot must not have to discover a
        // lock before they can type anything.
        val locked = prefs.getBoolean(prefKey, false)
        box.isChecked = locked
        apply(activity, fieldIds, locked)
        afterChange(locked)

        box.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                prefs.edit().putBoolean(prefKey, true).apply()
                apply(activity, fieldIds, true)
                afterChange(true)
                AppLog.v(TAG, "config locked: $prefKey")
                return@setOnCheckedChangeListener
            }
            // ⚠ BASIC MODE UNLOCKS WITH NO CONFIRMATION (design decision). On a
            // single-pilot aircraft the tick alone is what the lock is for — it stops an
            // accidental tap. The confirmation survives on the ONE control that changes who
            // receives this aircraft, which is the mode itself: see
            // AppMode.unlockNeedsConfirmation, and note that gating the mode in BOTH directions
            // is what stops this becoming a free route to an unlocked Elevated-mode controller.
            // Order: the pref FIRST — TakServerActivity's rows read it at paint time through
            // afterChange, and apply() updates the in-memory value at once.
            // ⚠ confirmBody is NOT shown on this path. For the battery lock it carries safety
            // text; that follows from the "tick alone" decision, recorded so it is not mistaken
            // for an oversight.
            if (!AppMode.unlockNeedsConfirmation(prefs)) {
                prefs.edit().putBoolean(prefKey, false).apply()
                apply(activity, fieldIds, false)
                afterChange(false)
                AppLog.i(TAG, "config unlocked (Basic mode, no confirmation): $prefKey")
                return@setOnCheckedChangeListener
            }
            // Unlocking: ask for confirmation, and put the box BACK unless it is given. Our own
            // revert would re-enter this listener, so it is detached around it (inside revert()).
            //
            // Cancel, Back and a touch outside take the same path on purpose: the only way out
            // of this dialog with the fields editable is the switch and the Unlock button.
            val revert = {
                box.setOnCheckedChangeListener(null)
                box.isChecked = true
                install(activity, checkBoxId, prefKey, fieldIds, confirmTitle, confirmBody,
                    afterChange)
            }
            UnlockConfirm.show(activity, confirmTitle, confirmBody, "Unlock",
                onCancelled = revert) {
                prefs.edit().putBoolean(prefKey, false).apply()
                apply(activity, fieldIds, false)
                afterChange(false)
                AppLog.i(TAG, "config UNLOCKED: $prefKey")
            }
        }
    }

    /**
     * Greys out and disables a set of views. `isEnabled = false` also makes them unfocusable, so
     * the keyboard cannot be raised on a locked field — read-only in the way a pilot means it —
     * and a disabled Button stops responding to taps.
     *
     * Typed as View, not EditText: a lock covers buttons and switches as well as fields.
     *
     * ⚠ Read rule 2 in the class note before adding a check box or a radio button to a field
     * list. The 45 % dim greys the tick, and the tick is what the pilot came to read.
     */
    fun apply(activity: Activity, fieldIds: List<Int>, locked: Boolean) {
        for (id in fieldIds) {
            activity.findViewById<View>(id)?.apply {
                isEnabled = !locked
                alpha = if (locked) 0.45f else 1.0f
            }
        }
    }

    private const val TAG = "ConfigLock"
}
