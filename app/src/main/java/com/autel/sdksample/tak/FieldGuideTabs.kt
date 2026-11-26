package com.autel.sdksample.tak

/**
 * THE TABS OF THE FIELD GUIDE, in the order of its left rail.
 *
 * [label] is the text of the rail item and the heading of the tab. [group] is the small
 * heading above the first tab of a group; the Start tab has none.
 *
 * ⚠ THE SETUP TABS ARE THE PRE-FLIGHT RAIL: the same names in the same order, so a pilot who
 * knows one screen knows the other. A group that is added to Pre-Flight is added here. This
 * app has no Speaker group, so it has no Speaker tab.
 *
 * NO ANDROID IMPORTS, ON PURPOSE: [FieldGuideTabsTest] holds the labels against the Pre-Flight
 * layout. `FieldGuideActivity.buildTab` gives each tab its text, and the `when` there does not
 * compile with a tab missing.
 */
enum class GuideTab(val label: String, val group: String?) {
    START("Start", null),

    AIRCRAFT("Aircraft", SETUP),
    MEMORY_CARD("Memory Card", SETUP),
    TAK("TAK", SETUP),
    VIDEO("Video", SETUP),
    MAP_DATA("Map Data", SETUP),
    APPLICATION_MODE("Application Mode", SETUP),

    FLIGHT_SCREEN("Flight Screen", FLIGHT),
    ACTION_BUTTONS("Action Buttons", FLIGHT),
    MARKERS("Markers", FLIGHT),
    CONTROLLER("Controller", FLIGHT),
    WARNINGS("Warnings", FLIGHT),

    TROUBLESHOOTING("Troubleshooting", HELP),
    FLIGHT_RECORDS("Flight Records", HELP),
}

private const val SETUP = "SETUP"
private const val FLIGHT = "FLIGHT"
private const val HELP = "HELP"
