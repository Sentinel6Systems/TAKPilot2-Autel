package com.autel.sdksample.tak

import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.EditText

/** Puts [SafeName] on an input field. */
object SafeNameField {

    /**
     * Call AFTER the field is filled: a saved name that breaks the rule is shown as it is, with
     * an error that names the bad characters, instead of being changed silently. From then on,
     * typing and pasting drop the characters that are not allowed.
     */
    fun attach(field: EditText) {
        field.filters = field.filters + InputFilter { source, start, end, _, _, _ ->
            val part = source.subSequence(start, end)
            if (SafeName.isValid(part.toString())) null else SafeName.clean(part)
        }
        fun check() {
            val name = field.text.toString()
            field.error = if (SafeName.isValid(name)) null
                else "Not allowed: ${SafeName.badChars(name)}. Use letters, digits, - and _ only."
        }
        check()
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = check()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
}
