package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Field Guide's SETUP tabs are the Pre-Flight rail: the same names in the same order.
 * The rail's names are read from the Pre-Flight layout, so a group that is added, renamed or
 * moved there fails here until the guide follows.
 */
class FieldGuideTabsTest {

    /** The texts of the `pfRail…` items of the Pre-Flight layout, in file order. */
    private fun preFlightRail(): List<String> {
        val layout = listOf("src/main/res/layout", "app/src/main/res/layout")
            .map { File(it, "activity_tak_connect.xml") }
            .first { it.exists() }
            .readText()
        return Regex("""android:id="@\+id/pfRail\w+"\s+style="@style/pfRailItem"\s+android:text="([^"]*)"""")
            .findAll(layout)
            .map { it.groupValues[1].replace("\\n", " ") }
            .toList()
    }

    @Test
    fun `the setup tabs are the Pre-Flight rail`() {
        val rail = preFlightRail()
        assertTrue("no rail items found in the Pre-Flight layout", rail.isNotEmpty())
        assertEquals(rail, GuideTab.values().filter { it.group == "SETUP" }.map { it.label })
    }

    @Test
    fun `the guide opens on Start, and only Start has no group`() {
        assertEquals(GuideTab.START, GuideTab.values().first())
        assertEquals(listOf(GuideTab.START), GuideTab.values().filter { it.group == null })
    }

    @Test
    fun `each group is one run of tabs`() {
        val groups = GuideTab.values().mapNotNull { it.group }
        val runs = groups.filterIndexed { i, g -> i == 0 || groups[i - 1] != g }
        assertEquals(listOf("SETUP", "FLIGHT", "HELP"), runs)
    }

    @Test
    fun `no two tabs have the same name`() {
        val labels = GuideTab.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }
}
