package com.autel.sdksample.tak

import android.content.Context

/**
 * THE SOURCE OF THE SMALL MAP ON THE FLIGHT SCREEN: Street, Hybrid or Custom (design
 * decision).
 *
 * ⚠ osmdroid TAKES CUSTOM TILES. An earlier version had no Map Display section, because a
 * custom map was thought to need a map engine other than osmdroid. That was wrong: osmdroid takes any
 * {z}/{x}/{y} address through an online tile source, which is what [MapSourceTiles] builds.
 *
 * The preference keys and the values are stored words: do not rename them.
 * The default is "street", the OpenStreetMap the flight map always showed here: an upgrade
 * changes nothing until a pilot chooses.
 *
 * No Android imports past [Context] and no osmdroid: the address rules are pure and tested
 * ([MapSourceTest]). The osmdroid half is in [MapSourceTiles].
 */
object MapSource {

    const val STREET = "street"
    const val HYBRID = "hybrid"
    const val CUSTOM = "custom"

    private const val PREFS = "takpilot2_tak"
    private const val KEY_STYLE = "map_style"
    private const val KEY_CUSTOM_URL = "map_custom_url"

    fun savedChoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STYLE, STREET)
            ?: STREET

    fun savedCustomUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_URL, "")
            ?: ""

    fun save(context: Context, choice: String, customUrl: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STYLE, choice)
            .putString(KEY_CUSTOM_URL, customUrl.trim())
            .apply()
    }

    /**
     * What the flight map shows: the choice, or Street for a Custom with no address saved (an
     * empty tile source is a blank map). One string, so the flight screen can tell that the
     * choice changed while it was away.
     */
    fun effective(choice: String, customUrl: String): String =
        if (choice == CUSTOM && validateCustomUrl(customUrl) != null) STREET
        else if (choice == CUSTOM) "$CUSTOM:${customUrl.trim()}"
        else if (choice == HYBRID) HYBRID
        else STREET

    /**
     * Checks a pilot-typed tile address. Returns a plain sentence for the problem, or null.
     * The rules: an http:// address is blocked by this controller
     * (targetSdk 29 refuses cleartext), and an address with no {z}/{x}/{y} can never name a
     * tile. Both left a blank map with no word of why.
     */
    fun validateCustomUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "Type the address of your map server."
        if (url.startsWith("http://", ignoreCase = true)) {
            return "The address must start with https://. This controller blocks http, and the map would stay empty."
        }
        if (!url.startsWith("https://", ignoreCase = true)) {
            return "The address must start with https://"
        }
        val missing = listOf("{z}", "{x}", "{y}").filterNot { url.contains(it, ignoreCase = true) }
        if (missing.isNotEmpty()) {
            return "The address must contain ${missing.joinToString(" and ")}, " +
                "for example https://example.com/tiles/{z}/{x}/{y}.png"
        }
        return null
    }

    /** The address of one tile: {z}, {x} and {y} put in, in any letter case. */
    fun tileUrl(template: String, z: Int, x: Int, y: Int): String =
        template.trim()
            .replace("{z}", z.toString(), ignoreCase = true)
            .replace("{x}", x.toString(), ignoreCase = true)
            .replace("{y}", y.toString(), ignoreCase = true)
}
