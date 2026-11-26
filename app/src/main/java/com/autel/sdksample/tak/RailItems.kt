package com.autel.sdksample.tak

import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * THE LOOK OF A LEFT-RAIL ITEM THAT IS SELECTED, AND OF ONE THAT IS NOT. Two screens have a
 * rail, Pre-Flight Setup and the Field Guide, and the two must look the same. The item itself
 * is `@style/pfRailItem`; this is the part that a style cannot hold, because it changes with
 * each touch.
 */
fun TextView.markRailItem(selected: Boolean) {
    if (selected) setBackgroundResource(R.drawable.bg_rail_item_selected)
    else setBackgroundColor(ContextCompat.getColor(context.applicationContext,
        android.R.color.transparent))
    setTextColor(ContextCompat.getColor(context.applicationContext,
        if (selected) R.color.tp_text_primary else R.color.tp_text_secondary))
    setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
}
