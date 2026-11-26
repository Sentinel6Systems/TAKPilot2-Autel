package com.autel.sdksample.tak

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autel.sdksample.R
import com.taklite.util.AppLog

/**
 * Pilot field guide: what each control does, in tabs the pilot opens with one touch.
 *
 * **Audience is the pilot, not a developer.** No class names, no file paths, no SDK talk. A
 * limit that changes a flight decision IS stated plainly.
 *
 * ## The tabs
 *
 * The guide has the same left rail as Pre-Flight Setup. [GuideTab] holds the tabs in rail
 * order, and the SETUP tabs carry the names and the order of the Pre-Flight rail
 * ([FieldGuideTabsTest] holds that). [buildTab] maps each tab to its `tab…` function. The
 * content is THIS airframe's: the EVO II controls and the Smart Controller buttons. This app
 * has no printed handout and no generator.
 *
 * A topic is: a short Title Case heading (the screen label where there is one), one line that
 * says what it is, then numbered [steps] if there is an action. **One path for each action.**
 * A fact that is true on one tab only is written on that tab only; the Troubleshooting tab
 * repeats facts on purpose and adds none of its own.
 *
 * ## The wording rules
 *
 * **Every pilot-facing string is ASD-STE100 Simplified Technical English, at about 80 %.**
 *  - **One word, one meaning.** A short screen press is "touch", a long one "touch and hold".
 *    A controller button is "press". The airframe is the "aircraft". A marker is a "marker".
 *  - **Approved words**: "make sure" not ensure, "about" not approximately, "let" not allow.
 *  - **Active voice, simple tenses, no -ing forms.** "The app sends the marker", not "the
 *    marker is sent" and not "sending the marker". A noun that is a name stays ("recording").
 *  - **Length**: 20 words at most for an instruction, 25 for a description. Six sentences at
 *    most in a paragraph.
 *  - **Conditions come first**: "If the signal is weak, select Low".
 *  - **A warning opens with the command**, then gives the reason.
 *  - **No idiom, metaphor or humour.** US spelling, as on the screens.
 *  - **On-screen labels are quoted exactly**, also when they are not STE ("Enroll & Connect").
 *  - **Capitals only where the screen has capitals** (LIVE, REC, PIP, DIFFERENT).
 *
 * **Facts that must not be trimmed**, because each one changes what a pilot does: the crosshair
 * angles and the error with height, the marker refusal conditions, what a marker Delete does
 * NOT do, the camera lock while the aircraft records, the shutter's two photos, the certificate
 * rule on channels, the FAA "not an approval" text, and the obstacle "no mark is not clear" text.
 *
 * ## Buttons of the controller
 *
 * WHICH button does a thing is written in the Controller tab (the one place a hardware mapping
 * is written down). Another tab names the function and says "See the Controller tab". ⚠ ONE
 * EXCEPTION, A DESIGN DECISION: the Quick Marker
 * and Static Marker entries name C2 too. A change of the marker button changes both places. `FlightActivity.installHardwareButtonListener` and [ZoomLadder] are the
 * ground truth for that tab.
 *
 * ## The icon examples are live views, not pictures
 *
 * Each one is the real toolbar widget ([BatteryGaugeView], [SignalBarsView], [LiveToggleView],
 * [RecordToggleView], the TAK badge) driven into the state the text describes. A screenshot
 * goes stale when an icon changes; these cannot.
 *
 * ## The mode gates
 *
 * The guide must not describe a control that the application mode removes, so the Elevated
 * parts are inside `if (AppMode.…Allowed(…))`, and each OPENS with "Elevated mode only.", so
 * the text says which mode it belongs to wherever it is read.
 */
class FieldGuideActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private val railItems = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_guide)
        AppLog.v(TAG, "field guide opened")
        // Menu button on the left of the action bar, matching the flight screen and Pre-Flight.
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        content = findViewById(R.id.fieldGuideContent)
        buildRail()

        // A deep link names an entry, and ANCHOR_TABS says which tab holds it. The extra is
        // used one time: after it, the tab in the intent is the pilot's own choice.
        val anchor = intent.getStringExtra(EXTRA_SCROLL_TO)
        intent.removeExtra(EXTRA_SCROLL_TO)
        showTab(anchor?.let { ANCHOR_TABS[it] }?.ordinal ?: intent.getIntExtra(EXTRA_TAB, 0))
        scrollToAnchor(anchor)
    }

    // ---------------------------------------------------------------- the rail

    /** One rail item for each [GuideTab], and a small heading where a group starts. */
    private fun buildRail() {
        val rail = findViewById<LinearLayout>(R.id.fieldGuideRail)
        var group: String? = null
        for (tab in GuideTab.values()) {
            if (tab.group != null && tab.group != group) {
                rail.addView(TextView(this).apply {
                    text = tab.group
                    setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_muted))
                    textSize = 12f
                    letterSpacing = 0.08f
                    setPadding(dp(14), dp(12), dp(8), dp(2))
                })
            }
            group = tab.group
            // The Pre-Flight rail's own style, so the two rails have one definition.
            val item = TextView(this, null, 0, R.style.pfRailItem).apply {
                text = tab.label
                setOnClickListener {
                    AppLog.v(TAG, "tap: guide tab ${tab.ordinal}")
                    showTab(tab.ordinal)
                }
            }
            railItems += item
            rail.addView(item, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /**
     * Shows one tab and marks its rail item, as the Pre-Flight rail does. An index out of
     * range shows the first tab. The index stays in the intent, as in Pre-Flight, so a
     * rebuilt screen opens on the same tab.
     */
    private fun showTab(requested: Int) {
        val tabs = GuideTab.values()
        val index = if (requested in tabs.indices) requested else 0
        intent.putExtra(EXTRA_TAB, index)
        for ((i, item) in railItems.withIndex()) item.markRailItem(i == index)
        // The rail can be longer than the panel is high. A tab that a deep link or a rebuilt
        // screen opens can be below its edge, so the rail moves to show the marked item.
        // Posted: at the first call the rail has no layout yet.
        railItems[index].let { item ->
            item.post {
                item.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, item.width, item.height), true)
            }
        }
        content.removeAllViews()
        // The Start tab opens with the guide's title; every other tab with its own name.
        if (tabs[index] != GuideTab.START) section(tabs[index].label)
        buildTab(tabs[index])
        spacer(24)
        findViewById<android.widget.ScrollView>(R.id.fieldGuideScroll).scrollTo(0, 0)
    }

    private fun buildTab(tab: GuideTab) = when (tab) {
        GuideTab.START -> tabStart()
        GuideTab.AIRCRAFT -> tabAircraft()
        GuideTab.MEMORY_CARD -> tabMemoryCard()
        GuideTab.TAK -> tabTak()
        GuideTab.VIDEO -> tabVideo()
        GuideTab.MAP_DATA -> tabMapData()
        GuideTab.APPLICATION_MODE -> tabApplicationMode()
        GuideTab.FLIGHT_SCREEN -> tabFlightScreen()
        GuideTab.ACTION_BUTTONS -> tabActionButtons()
        GuideTab.MARKERS -> tabMarkers()
        GuideTab.CONTROLLER -> tabController()
        GuideTab.WARNINGS -> tabWarnings()
        GuideTab.TROUBLESHOOTING -> tabTroubleshooting()
        GuideTab.FLIGHT_RECORDS -> tabFlightRecords()
    }

    /** The store every [AppMode] predicate in this guide reads. */
    private fun modePrefs(): android.content.SharedPreferences =
        getSharedPreferences(TakConnectActivity.PREFS, MODE_PRIVATE)

    // ---------------------------------------------------------------- Start

    private fun tabStart() {
        title("TAKPilot2 Field Guide")
        lede("Touch a name at the left to open that part of the guide. Read the guide before " +
            "you fly. This guide is for the Autel EVO II.")

        sub("What the App Does")
        body("TAKPilot2 flies your Autel EVO II and puts it on the TAK map of your team.")
        bullet("Your team sees the position, heading and altitude of the aircraft, and the " +
            "point on the ground where the camera looks.")
        bullet("You put markers on what you see. Your team gets them in a few seconds.")
        bullet("The app can send live video to a server of your team.")
        note("Do the firmware updates, the compass calibration, the gimbal calibration and " +
            "the aircraft registration in the Autel app first. Not here.")
        body("A red TAK icon means that your team cannot see the aircraft or your markers. " +
            "The aircraft still flies correctly.")

        sub("Home")
        table(
            "Button | Function",
            "ENTER FLIGHT | Opens the flight screen.",
            "Pre-Flight Setup | The settings. Do them on the ground.",
            "Field Guide | This guide.",
            "Data Sync | Data that your team shares on the TAK server.",
            "Debug Log | A record of what the app does, to send with a fault report. Also " +
                "the switch that shows system resources on the flight screen, and the video " +
                "delay for SRT.",
            "Stop / Quit | Stops the app.",
        )
        body("The ENTER FLIGHT card shows what the app reads now: the permissions, the " +
            "network, the TAK connection, the video server and the settings that are in the " +
            "aircraft. Read the card before each flight. A dash means that the app does not " +
            "know that value yet.")

        divider()
        body("If this guide does not agree with the aircraft, obey the aircraft. Then tell " +
            "the person who maintains the app.")
    }

    // ---------------------------------------------------------------- Setup: Aircraft

    private fun tabAircraft() {
        body("Pre-Flight Setup > Aircraft. Do this on the ground.")

        sub("Connected Aircraft")
        body("The first line shows which aircraft is connected. With no aircraft, the app " +
            "keeps the settings and sends them when the aircraft connects.")

        sub("Aircraft Settings")
        bullet("Max altitude (ft): the highest altitude that the aircraft flies.")
        bullet("Max distance (ft): the farthest distance from the home point that the " +
            "aircraft flies.")
        bullet("Return-home altitude (ft): set it below the Max altitude, and above the " +
            "highest obstacle between you and the aircraft.")
        body("An empty field keeps the value that is in the aircraft. If the aircraft cannot " +
            "accept a value, a red line below the fields tells you.")

        sub("Control Response")
        body("Normal or Precision. Precision makes the camera dial and the turn of the " +
            "aircraft slower, for small movements. The change applies when you touch it.")

        sub("Stick Mode")
        body("This sets the function of the sticks. Mode 2 is the usual mode. The change " +
            "applies when you touch it. The line \"Controller:\" shows the mode that the " +
            "controller uses now.")

        sub("Obstacle Avoidance")
        body("The line shows if obstacle avoidance is ON or OFF in the aircraft. The three " +
            "boxes show System, During Return to Home and Landing protection. Touch a box to " +
            "change it. The change applies immediately. The app sets it again each time the " +
            "aircraft connects.")
        body("If the line says \"Wait\", the aircraft did not send its settings yet. Do not " +
            "read it as off.")

        sub("Battery Levels")
        body("Battery Warning (%) is the level where the aircraft warns you. Battery Critical " +
            "(%) is the level where the aircraft returns home. Lock battery levels prevents a " +
            "change by accident.")
        body("Battery Warning must be higher than Battery Critical. If it is not, the app " +
            "does not send the two levels.")

        sub("Send the Settings")
        steps(
            "Type the values.",
            "Touch Apply Updated Settings to Aircraft.",
            "Read the line \"Aircraft:\" below each field.",
        )
        table(
            "Line | Meaning | Do this",
            "Green | The aircraft holds your value. | Nothing.",
            "Amber, DIFFERENT | The aircraft holds a different value. | Touch Apply again.",
            "No color | The field is empty. The line shows the value in the aircraft. | " +
                "Nothing.",
            "Dash | The app does not know the value in the aircraft. | Make sure that the " +
                "aircraft is connected. The first line of the screen tells you.",
        )
        body("The app also sends the settings each time the aircraft connects.")
    }

    // ---------------------------------------------------------------- Setup: Memory Card

    private fun tabMemoryCard() {
        body("Pre-Flight Setup > Memory Card.")
        body("This shows the memory card in the aircraft and its free space. To erase the " +
            "card:")
        steps(
            "Land the aircraft.",
            "Stop the recording.",
            "Touch Format Card.",
            "Read the message, then confirm.",
        )
        body("Format Card erases all data on the card.")
        body("If you cannot touch Format Card, the line below the button gives the cause, " +
            "for example \"No card\".")
    }

    // ---------------------------------------------------------------- Setup: TAK

    private fun tabTak() {
        body("Pre-Flight Setup > TAK. The first lines show who you are connected as, which " +
            "channels are active, and where the live-video link goes.")

        sub("Connect")
        steps(
            "Type the Server host and the two ports.",
            "Type your Username and your Password.",
            "Type the Aircraft callsign.",
            "Touch Enroll & Connect.",
        )
        body("To see the password that you typed, touch the eye at the end of the field.")

        sub("Aircraft Callsign")
        body("The callsign takes letters, digits, - and _ only. Do not use spaces. The " +
            "callsign is also the video Broadcast ID, unless you set a different one.")
        body("To change the callsign while you are connected:")
        steps(
            "Type the new callsign.",
            "Touch Enroll & Connect.",
        )
        body("You do not have to log out or type the password. The connection stops for a " +
            "moment and starts again with the new callsign. You cannot do this while the " +
            "aircraft flies or while LIVE is on.")

        sub("Lock Configuration")
        body("Lock configuration is at the right of the title. It prevents a change by " +
            "accident. To remove the lock in Elevated mode, turn on the switch and touch " +
            "Unlock. Basic mode removes the lock with one touch.")

        sub("My Channels")
        body("The TAK server holds the channels, not the app. A change applies immediately. " +
            "A channel marked \"Rx Only\" sends you data, but you cannot send to it.")
        body("The channels belong to your TAK user name, not to this controller. If two " +
            "controllers sign in with the same user name, a change on one also changes the " +
            "other.")
        body("To change the channels in flight, touch and hold the TAK icon.")

        if (AppMode.elevatedAccountAllowed(modePrefs())) {
        sub("Elevated Account")
        body("Elevated mode only. The Elevated account is a shared second TAK account. " +
            "It is optional. Only the channel of this account gets the live-video " +
            "link. All other channels still get the position and the markers.")
        body("Elevated mode only. After Log Out, enroll the Elevated account again. If you " +
            "do not, the video link goes to every channel.")
        body("Elevated mode only. Clear the tick of a channel to remove it from this " +
            "account. The aircraft then does not go to that channel, and you get no data from " +
            "it. This is the only way to stop data from one channel.")
        warn("Elevated mode only. Keep the tick of the video channel of the Elevated " +
            "account. Every controller shares this account. If you clear the tick, no " +
            "controller sends video to that channel until a person sets the tick again.")
        }
    }

    // ---------------------------------------------------------------- Setup: Video

    private fun tabVideo() {
        body("Pre-Flight Setup > Video. Optional. These settings do not start the video. Use " +
            "the LIVE button in flight.")

        sub("Stream Quality")
        body("Select Low, Standard or High. If the connection is weak, select Low. The lock " +
            "does not lock the Stream quality.")

        sub("Video Server")
        steps(
            "Type the Host and the login of the video server of your team.",
            "Select the Encoding H.264. More TAK apps can show it.",
            "Select the Video Publish Protocol.",
            "Make sure that the port of that protocol is correct.",
        )
        table(
            "Protocol | Use it on",
            "RTSP | A reliable network. It gives the lowest delay.",
            "SRT | A less reliable network, for example a cellular network.",
        )
        body("The Broadcast ID takes letters, digits, - and _ only. If it is empty, it is " +
            "the aircraft callsign from the TAK group.")
        body("If your team cannot see the video and this screen shows no fault, select " +
            "H.264. A TAK app that cannot show H.265 gives no error on this screen.")

        sub("Lock Configuration")
        body("Lock configuration is at the right of the title. The eye at the end of a " +
            "password or passphrase field works only while the video settings are unlocked.")

        if (AppMode.secondVideoServerAllowed(modePrefs())) {
            sub("Active Server")
            body("Elevated mode only. Active server selects Server 1 or Server 2. Each " +
                "server has its own address, login and encoding.")
        }
    }

    // ---------------------------------------------------------------- Setup: Map Data

    private fun tabMapData() {
        body("Pre-Flight Setup > Map Data. Do the downloads on Wi-Fi before you go.")

        sub("Terrain Elevation")
        body("Terrain data makes the markers more accurate and gives the true altitude above " +
            "the ground. Without it, the altitude is the height above your takeoff point.")
        steps(
            "Touch + Import Region.",
            "Select the .zip file of the area.",
        )
        body("Use one .zip file for each area. The app does not accept a single terrain file. A second " +
            "import of the same area replaces the first.")

        sub("FAA Ceilings")
        body("With this data, the flight screen shows the FAA ceiling at the position of the " +
            "aircraft.")
        steps(
            "Touch Use My Location, or type the center of the area.",
            "Type the Radius (mi).",
            "Touch Download.",
        )
        body("Do not use this data as an approval to fly. It can be out of date. You must " +
            "get your own airspace approval.")

        sub("Map Display")
        body("This sets the small map on the flight screen: Street, Hybrid (satellite pictures " +
            "with street names) or Custom, the map server of your team. It changes your map " +
            "only, not the map of your team.")
        body("Street and Hybrid are saved when you touch them. For Custom:")
        steps(
            "Touch Custom.",
            "Type the address of your map server.",
            "Touch Save Map Address.",
        )
        body("The address must start with https:// and contain {z}, {x} and {y}. If it does " +
            "not, the app does not save it and tells you why.")

        sub("Offline Map")
        body("Offline Map saves the custom map of an area on the controller. The flight " +
            "screen then shows the map with no network. It shows only with Custom.")
        steps(
            "Touch Save Map Address first.",
            "Type a name for the area.",
            "Touch Use My Location, or type the center of the area.",
            "Type a radius of 15 miles or less.",
            "Touch Download Map. Use Wi-Fi.",
        )
        body("One download is 1 GB at most. Each area shows in a list with its name, its " +
            "radius and the date of the download. To remove one area, touch Delete. The " +
            "other areas stay. A download for a new map address removes the old areas.")
    }

    // ---------------------------------------------------------------- Setup: Application Mode

    // In BOTH modes: the control exists in both, and the "Elevated mode only" lines need
    // something to refer to.
    private fun tabApplicationMode() {
        table(
            "Function | Elevated | Basic",
            "TAK accounts | Two | One",
            "Video servers | Two | One",
            "Emergency Broadcast | Yes | No",
            "Confirmation for the locks | Yes | No",
        )
        body("In Basic mode, every active channel gets the aircraft and, when a video " +
            "address is on, its video link.")

        sub("Change Mode")
        steps(
            "Land the aircraft and stop LIVE.",
            "Touch Change Mode.",
            "Read who will receive the aircraft after the change.",
            "Turn on the switch, then touch Change Mode.",
        )
        body("A change asks you to confirm in the two directions.")
    }

    // ---------------------------------------------------------------- Flight: Flight Screen

    private fun tabFlightScreen() {
        body("The live camera image fills the screen. The status icons are at the top left. " +
            "The action buttons are in a column below them. The readout is at the right " +
            "side, and the small map is at the bottom right. Warnings show at the bottom " +
            "left.")

        sub("Status Icons")

        entry(
            listOf(icon(R.drawable.ic_menu) to "Menu"),
            "Menu",
            "Closes the flight screen and shows Home. The aircraft continues to fly and " +
                "stays connected to TAK.",
        )

        entry(
            listOf(
                takBadge(connected = true) to "Connected",
                takBadge(connected = false) to "Not connected",
            ),
            "TAK Connection",
            "Green: your aircraft is on the TAK map of your team. Red: it is not. You can " +
                "fly, but your team cannot see the aircraft.\n\n" +
                "Touch: connect or disconnect.\n" +
                "Touch and hold: the TAK channels. If they are locked, touch Unlock. In " +
                "Elevated mode, turn on the switch and touch Unlock again.",
        )

        entry(
            listOf(
                battery(85) to "85%",
                battery(24) to "24%",
                battery(9) to "9%",
            ),
            "Battery",
            "The charge in the battery of the aircraft. Land the aircraft when the ring is " +
                "yellow. Do not wait for red.",
        )

        entry(
            listOf(
                signal(90) to "Strong",
                signal(60) to "Medium",
                signal(20) to "Weak",
            ),
            "Controller Signal",
            "The strength of the signal between the controller and the aircraft. If the bars " +
                "decrease, fly the aircraft nearer or move away from other radio signals. If " +
                "the aircraft loses the signal, it returns home.\n\n" +
                "The bars are gray at \"—%\" until the aircraft connects. If they stay gray " +
                "when the aircraft is connected, close the Autel Explorer app. Only one app " +
                "at a time can read the signal.",
        )

        entry(
            listOf(
                gps(hasFix = true) to "Position",
                gps(hasFix = false) to "No position",
            ),
            "GPS Satellites",
            "The quantity of satellites that the aircraft receives. Green: the aircraft has " +
                "its position. Wait for green before you take off. Without a position the " +
                "aircraft cannot hold its position, set a home point, or come home.",
        )

        entry(
            listOf(
                image(R.drawable.ic_rth_home_set) to "Home set",
                image(R.drawable.ic_rth) to "No home",
            ),
            "Return to Home",
            "The house is green when the home point is set.\n\n" +
                "Touch: sends the aircraft home. The app asks you to confirm.\n" +
                "Touch and hold, or touch during a return: the Return to Home menu.\n\n" +
                "Cancel Return shows only while the aircraft returns. It stops the return " +
                "immediately and gives you the sticks. The app does not ask you to confirm. " +
                "The app tells you if the return did not stop.\n\n" +
                "Reset Home Point moves the home point to your position, from the GPS of the " +
                "controller. Use it if you moved away from the takeoff point. The app shows " +
                "the coordinates and asks you to confirm.",
        )

        sub("Readout")

        entry(
            emptyList(),
            "Exposure Slider",
            "At the top right. Makes the image brighter or darker. Use it when the camera " +
                "makes the image too dark or too bright, for example a dark object against snow.",
        )

        entry(
            emptyList(),
            "Clock",
            "Below the slider. The time of the controller. Use it to give a time to your " +
                "team.",
        )

        entry(
            emptyList(),
            "Aircraft Data",
            "The callsign and speed, the height above the ground and above sea level, then " +
                "the latitude and longitude. GIMBAL shows the camera angle.\n\n" +
                "AGL is the true height above the ground and needs terrain data. Without " +
                "it, ALT shows: the height above your takeoff point. MSL is the height " +
                "above sea level, which airspace limits use.",
        )

        entry(
            emptyList(),
            "RTH and HOME Lines",
            "RTH is the return altitude that the aircraft holds now. Two dashes mean that " +
                "the app does not know it, not that it is zero. HOME is the distance and " +
                "direction to the home point, in degrees from true north.\n\n" +
                "RTH shows the aircraft, not what you typed in Pre-Flight Setup. If the two " +
                "are different, send the settings again with Apply.",
        )

        entry(
            emptyList(),
            "FAA Ceiling Line",
            "The published ceiling at the position of the aircraft. It becomes red if you " +
                "fly above it. It shows AGL, thus compare it with the AGL line and not " +
                "MSL.\n\n" +
                "Class G: the FAA has no ceiling map here. The usual 400 ft limit applies.\n" +
                "FAA --- ft AGL: the app does not know. You did not download the data, or " +
                "you flew out of the area that you downloaded.\n" +
                "FAA — no fix: the aircraft does not have its GPS position yet.",
        )

        sub("Map")

        entry(
            emptyList(),
            "Map",
            "North is at the top and the aircraft is in the center. The red line goes from " +
                "the home point to the aircraft. The map also shows the markers of your " +
                "team.\n\n" +
                "The button on the map gives two zoom levels: WIDE is the full distance that " +
                "you can fly, NEAR shows more detail. Touch the map two times to make it " +
                "large, and again to make it small.\n\n" +
                "To change the map, see Map Display in the Map Data tab.\n\n" +
                "Map data: OpenStreetMap contributors. Satellite pictures: Esri World Imagery.",
        )

        entry(
            emptyList(),
            "System Resources",
            "Off until you set it to on in Debug Log. It shows how much memory the " +
                "controller (SYS) and the app (APP) use, how busy the controller is (CPU and " +
                "GPU), and how many team members and aircraft the app knows (TAK).\n\n" +
                "Watch TAK: it must go up and down with the persons and aircraft near you. " +
                "If it only goes up during a flight, report it.",
        )
    }

    // ---------------------------------------------------------------- Flight: Action Buttons

    private fun tabActionButtons() {
        body("The action buttons are in a column at the left of the flight screen, from top " +
            "to bottom as in this table. The camera controls are at the right.")
        // ALL OF THEM, IN COLUMN ORDER (design decision): a list that leaves out the marker
        // and AR buttons or the thermal controls is not complete.
        table(
            "Button | Touch | Touch and hold",
            "Marker | Puts a marker at the crosshair. See the Markers tab. | The Markers list.",
            "Lights | Lights on or off. | —",
            "AR | Markers on the video, on or off. See the Markers tab. | The AR settings.",
            "IR | Changes between the normal camera and full-screen IR. | —",
            "PIP | Shows only with IR on. Changes between full-screen IR and PIP. | —",
            "Zoom | 1X or 2X. | 4X.",
            "LIVE | Video to your team, on or off. | The video quality.",
            "REC | Recording on the aircraft card, on or off. | —",
        )
        table(
            "At the right | Function",
            "Exposure slider | The image brighter or darker. See the Flight Screen tab.",
            "Camera mode switch | Video or photos.",
            "Thermal color button | Shows only with IR on. Changes the colors of the thermal " +
                "image.",
            "PIP slider | Shows only in PIP, beside the column. Sets how much of the thermal " +
                "image shows.",
        )

        entry(
            listOf(
                lightsPill(false) to "Lights on",
                lightsPill(true) to "Lights off",
                lightsPill(null) to "Not known",
            ),
            "Lights: Exterior Lights",
            "Touch: turns the navigation lights of the aircraft on or off. Turn them off to " +
                "make the aircraft difficult to see at night.\n\n" +
                "The button shows what the aircraft reports, not what you asked for. Amber " +
                "means that the aircraft did not answer yet. Do not read it as off.",
        )

        entry(
            listOf(modePill("IR", false) to "Normal", modePill("IR", true) to "IR"),
            "IR: Thermal Camera",
            "Touch to change between the normal camera and the full-screen thermal image. " +
                "The button is green while the thermal image shows. From PIP, a touch goes " +
                "back to the normal camera.\n\n" +
                "When the thermal image shows, a color button shows at the right. Touch it " +
                "to change the thermal colors: white hot, black hot, ironbow.",
            caveats = listOf(
                "You cannot change the camera while the aircraft records. Select the camera " +
                    "before you start to record.",
            ),
        )

        entry(
            listOf(modePill("PIP", false) to "IR", modePill("PIP", true) to "PIP"),
            "PIP: Thermal in the Normal Image",
            "Shows below the IR button only while the thermal image shows. Touch it to " +
                "enter PIP: the thermal image in the center of the normal image. Touch it " +
                "again to go back to the full-screen thermal image.\n\n" +
                "In PIP, a slider beside the column sets how much of the thermal image " +
                "shows. Up is more thermal. Zoom does not work in PIP.\n\n" +
                "In PIP the aircraft records three files: normal, thermal and PIP.",
        )

        entry(
            listOf(zoomPill("1X") to "Normal", zoomPill("2X") to "2X view"),
            "Zoom",
            "Touch: changes between 1X and 2X. Touch and hold: 4X. From 4X, a touch goes " +
                "back to 1X, so one touch always gives the widest view.\n\n" +
                "For the other zoom levels, use the zoom rocker. See the Controller tab.",
        )

        entry(
            listOf(
                live(LiveToggleView.State.OFF) to "Off",
                live(LiveToggleView.State.LIVE) to "Video on",
                live(LiveToggleView.State.RECONNECTING) to "Retrying",
            ),
            "LIVE: Video to Your Team",
            "Touch: starts and stops the live video to the video server of your team. Set " +
                "the server in Pre-Flight Setup first.\n" +
                "Touch and hold: the video quality.\n\n" +
                "If the connection stops, the button flashes amber and shows SYNC. The app " +
                "tries to connect again by itself. Do not touch the button. If you touch it, " +
                "the app stops trying.",
        )

        // Its OWN predicate, not elevatedAccountAllowed: gating this entry on the Elevated
        // predicate works only while every predicate happens to agree.
        if (AppMode.emergencyBroadcastAllowed(modePrefs())) {
        entry(
            emptyList(),
            "Emergency Broadcast",
            "Elevated mode only. Every channel gets the live-video link for 15 minutes.\n\n" +
                "To start: touch and hold LIVE, then touch Start Emergency Broadcast. It " +
                "shows only when the Elevated account is on. If LIVE is off, the app starts " +
                "LIVE and asks for permission to send the screen. Accept it. If you refuse, the " +
                "broadcast stops.\n" +
                "To add 15 minutes: touch the notice at the top of the flight screen.\n" +
                "To stop: touch and hold the notice.\n\n" +
                "The notice is amber and reads NO VIDEO STREAM while no video goes out. Your team " +
                "gets the video link from the aircraft marker, so the aircraft must have a " +
                "GPS position. The flight record keeps the time of each start, renewal and stop.",
        )
        }

        entry(
            listOf(
                rec(recording = false) to "Off",
                rec(recording = true) to "Recording",
            ),
            "REC: Record to the Aircraft",
            "Records video to the card in the aircraft. It does not change the live video to " +
                "your team. The card keeps the full quality, with no text on the image.\n\n" +
                "REC records from the photo mode too: it changes the camera to video, then " +
                "starts. The camera stays in video after you stop.",
        )

        entry(
            emptyList(),
            "Camera Mode Switch",
            "A small switch below the exposure slider shows what the camera saves: video or " +
                "photos. Touch the other side to change the mode. This takes no picture and " +
                "starts no recording.",
            caveats = listOf(
                "The switch does not move while the camera records. Stop the recording first.",
            ),
        )
    }

    // ---------------------------------------------------------------- Flight: Markers

    private fun tabMarkers() {
        body("A marker goes to the point at the center of the image. Point the camera at " +
            "the target first.")

        entry(
            emptyList(),
            "Crosshair",
            // No fixed foot figures per colour: they hold only near 100 ft AGL, and at 400 ft
            // a GREEN drop measured about 70 ft off.
            "The center of the image, and where a marker goes. The color of the ring shows " +
                "how much the camera angle limits the accuracy.\n\n" +
                "With terrain data: green 25° down or more, yellow 10° to 25° down.\n" +
                "Without terrain data: green 30° down or more, yellow 15° to 30° down.\n" +
                "Red: the app does not put a marker. Point the camera down more, or fly " +
                "nearer.\n\n" +
                "The error also increases with your height and the distance. At 400 ft above " +
                "the target a green marker can be 70 ft off. The compass error moves a marker " +
                "to the side: at 1,000 ft it can be 80 ft. Compare a marker with the map " +
                "before you send it.",
        )

        entry(
            emptyList(),
            "Quick Marker",
            "Touch the crosshair. The app puts a marker immediately, with no questions. The " +
                "name is always ${TakDropMarkers.QUICK_NAME}.\n\n" +
                "There is only one quick marker. Each new touch moves it to the new point, " +
                "on the screens of all your team. Use it to show your team what you look at " +
                "now.\n\n" +
                "Press C2 on the controller for the same function.",
        )

        entry(
            emptyList(),
            "Static Marker",
            "Touch and hold the crosshair, then select the type: Unknown, Hostile, " +
                "Friendly or Neutral. The app puts a static marker where the crosshair was " +
                "when you started to hold, and sends it. This marker does not move. A " +
                "second touch and hold puts a second " +
                "marker. The name is the callsign of the aircraft and a number, for example " +
                "UAS-ALPHA-07.\n\n" +
                "Use it to keep a record of a position. Press and hold C2 on the controller " +
                "for the same function.",
        )

        entry(
            listOf(image(R.drawable.ic_drop_pin) to "Marker"),
            "Marker Button",
            "Touch: puts a marker at the center of the image. Then select the type: " +
                "Unknown, Hostile, Friendly or Neutral.\n" +
                "Touch and hold: the Markers list.\n\n" +
                "In the list, touch rows to select them. Then Resend or Delete all of them " +
                "at one time. Touch and hold one row to change your own marker, or to send a " +
                "marker of your team again. Clear All removes all your markers.\n\n" +
                "The app does not put a marker in these conditions:\n" +
                "- The crosshair ring is red.\n" +
                "- The aircraft has no GPS position.\n" +
                "- The aircraft is less than 25 ft above the ground.\n\n" +
                "Point the camera down more, or climb.",
            listOf(
                "Delete removes a marker from this aircraft only. It stays on the screens " +
                    "of your team for about 3 days.",
            ),
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR: Markers on the Video",
            "Draws the markers on the live image near their positions. The button is green " +
                "when AR is on. AR is on when you open the flight screen. A marker outside " +
                "the image shows as an arrow at the edge: turn the camera that way.\n\n" +
                "Touch and hold: the AR settings. Select what the app draws and how far away it " +
                "shows other aircraft. Calibrate FOV (the width of the camera view) and Aim " +
                "Offsets are also there.",
            listOf(
                "The AR view is not accurate for a point. It shows the general area of a " +
                    "marker. Do not use it to choose between objects that are close " +
                    "together, such as one house in a row of houses.",
            ),
            anchor = ANCHOR_AR,
        )

        sub("Aim Offsets")
        body("If the aim of the camera has a small error, all the markers go to a position " +
            "that is not correct, in the same direction. Aim Offsets corrects this. The " +
            "error belongs to the aircraft, not to the app.")
        body("Do this one time for each aircraft. Do it again after a hard landing, after a " +
            "repair of the gimbal or the camera, or when you use a different aircraft.")
        body("You need a second device with iTAK, ATAK, TAK Aware or CloudTAK that can see " +
            "the markers of the aircraft.")
        steps(
            "Select a target that you see clearly in the video and can find on the map of " +
                "the second device. A mark on a road or a corner of a building is good.",
            "Point the camera about 25 degrees down, with the crosshair on the target. Do " +
                "not point it straight down: the error does not show there.",
            "On the second device, find the point where the camera of the aircraft looks. " +
                "Its name ends with \"-SPI\".",
            "Touch and hold the AR button, then touch Aim Offsets.",
            "Change one setting at a time. The point moves a few seconds after each change.",
            "Stop when the point is on the target.",
        )
        body("The thermal camera has its own offsets. Do the same steps with the thermal " +
            "image on. The title of the window then says \"Thermal\".")
    }

    // ---------------------------------------------------------------- Flight: Controller

    /**
     * The hardware controls, in ONE place. Ground truth is
     * `FlightActivity.installHardwareButtonListener` and [ZoomLadder], NOT older guide text.
     * The SDK names the custom buttons CUSTOM_BUTTON_{SHORT,LONG}_{A,B}; which letter is which
     * physical key is a constant in that file.
     */
    private fun tabController() {
        body("Most of these buttons do the same functions as buttons on the screen. The " +
            "shutter button and the RTH button have no button on the screen.")

        sub("Buttons")
        table(
            "Control | Press | Press and hold",
            "C1 | Changes between the normal camera and full-screen IR, the same as the IR " +
                "button. | Changes the thermal colors.",
            "C2 | Puts the quick marker, or moves it to what the camera looks at now. | " +
                "Puts a new static marker.",
            "Zoom rocker (right side) | One zoom level: 1X, 2X, 3X, 4X, 6X, 8X, 10X, 12X, " +
                "16X. | The zoom moves through the levels. Release it to stop.",
            "Shutter button | Takes a photo. | —",
            "Record button | Starts and stops the recording, the same as REC. | —",
            "RTH button | Sends the aircraft home. This is a function of the controller. | —",
        )

        sub("Shutter Button")
        body("The app saves the photo to the card in the aircraft, not to the controller. The " +
            "screen shows \"Photo Saved\". If the screen shows \"The photo did not save\", " +
            "the photo is not on the card. Take it again.")
        body("One press saves a normal photo and a thermal photo, also for the camera that " +
            "you do not see.")
        warn("While the aircraft records, one press saves the normal photo only. You get no " +
            "thermal photo until the recording stops.")
    }

    // ---------------------------------------------------------------- Flight: Warnings

    private fun tabWarnings() {
        entry(
            emptyList(),
            "Warning Box",
            "A box at the bottom left shows a warning. Red means act now. Amber means know " +
                "it. If the motors do not start, read this box first.\n\n" +
                "The warnings are for the compass, the GPS, the battery, the flight limits, " +
                "the wind, no home point, and an automatic return home. The box shows the " +
                "most important one, with a count if there are more.\n\n" +
                "Touch the box to read all of them, the most serious at the top. Touch it again to " +
                "close the list. Touch the X to hide this set of warnings. The box comes back " +
                "when the warnings change.\n\n" +
                "DEBUG LOG ON is an amber warning from the app. The app records a detailed log, " +
                "and the screen can become less smooth. The X does not hide it. Set the log " +
                "to off on the Debug Log screen.",
        )

        entry(
            emptyList(),
            "Obstacle Distances",
            "A color grows in from the edge of the video nearest to an obstacle, with the " +
                "distance in feet. REAR shows an obstacle behind the aircraft. It shows at " +
                "about 39 ft in yellow, then red at 13 ft or less.",
            listOf(
                "An edge with no color does not mean that the direction is clear. It can " +
                    "also mean that the aircraft has no sensor there, or that obstacle " +
                    "avoidance is off.",
            ),
        )

        // On this screen the arc is at the left edge of the small map.
        entry(
            emptyList(),
            "Antenna Aim",
            "A small arc at the left edge of the small map shows where the aircraft is, " +
                "relative to where the controller points. The antennas of the controller are " +
                "directional. The signal is strongest when they face the aircraft.\n\n" +
                "If you cannot see the aircraft, turn your body until the marker is at the " +
                "top of the arc. Green means near enough.\n\n" +
                "A red arc means that the compass in the controller gives a wrong direction. This " +
                "occurs in a vehicle and near steel. Do not use the arc while it is red. " +
                "Move away from the vehicle.\n\n" +
                "The arc shows only when the controller knows its direction and the app knows " +
                "where the controller and the aircraft are.",
        )
    }

    // ---------------------------------------------------------------- Help: Troubleshooting

    // ⚠ EVERY ROW REPEATS A FACT FROM ANOTHER TAB. Add no advice here that the tab of the
    // control does not give: a row that says more than its tab is a second source of truth.
    private fun tabTroubleshooting() {
        sub("Before the Flight")
        table(
            "What you see | What to do",
            "The line \"Aircraft:\" shows DIFFERENT. | Touch Apply Updated Settings to " +
                "Aircraft.",
            "A red line says that the aircraft will refuse a value. | Correct the value. " +
                "If you do not, the aircraft keeps its setting.",
            "Obstacle avoidance says \"Wait\". | The aircraft did not send its settings yet. " +
                "Do not read it as off.",
            "You cannot touch Format Card. | Read the line below the button. It gives the " +
                "cause.",
            "The GPS icon is not green. | Wait. Do not take off without a position.",
            "The motors do not start. | Read the warning box at the bottom left.",
        )

        sub("TAK and Video")
        table(
            "What you see | What to do",
            "The TAK icon is red. | Your team cannot see the aircraft. Touch the TAK icon " +
                "to connect. The aircraft still flies correctly.",
            "Your team cannot see the video, and LIVE shows no fault. | In Pre-Flight Setup " +
                "> Video, select the Encoding H.264.",
            "LIVE flashes amber and shows SYNC. | The app tries to connect again. Do not " +
                "touch LIVE.",
            "The signal bars stay gray with the aircraft connected. | Close the Autel " +
                "Explorer app.",
        )

        sub("Flight Screen")
        table(
            "What you see | What to do",
            "A button is amber. | The aircraft did not answer yet. Do not read it as off.",
            "The app does not change the camera. | Stop the recording first.",
            "There is no PIP button. | Touch IR first. PIP shows only with IR on.",
            "The FAA line shows FAA --- ft AGL. | You did not download the data, or you " +
                "flew out of the area that you downloaded.",
        )

        sub("Markers")
        table(
            "What you see | What to do",
            "The app does not put a marker. | Point the camera down more, or climb. The " +
                "aircraft must have a GPS position.",
            "All markers are wrong in the same direction. | Do the Aim Offsets procedure in " +
                "the Markers tab.",
        )
    }

    // ---------------------------------------------------------------- Help: Flight Records

    // A DESCRIPTION of automatic behaviour, not a control reference: there is no switch, and
    // the tab says so first, because a pilot who reads about a function looks for its setting.
    private fun tabFlightRecords() {
        // The motors, not the height (AirborneRule).
        body("The app records each flight automatically, with no switch and no TAK server. " +
            "The record starts when the motors start. It stops when the motors are off for " +
            "10 seconds.")
        body("The files are in Downloads/TAKPilotFlights on the controller. Each flight " +
            "makes two files:")
        table(
            "File | Contents | Open it in",
            ".gpx | The track. | ATAK or Google Earth.",
            ".csv | One row each second: time, position, altitude, speed, heading, battery " +
                "and satellites. | A spreadsheet.",
        )
        body("Without a GPS position the app records nothing for that time. It does not " +
            "write a false position.")
        body("The folder keeps about 50 MB, then deletes the oldest files. To keep a " +
            "record, copy it to a different location.")
    }

    /** Action-bar back arrow behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Jumps to the entry tagged [anchor], for a deep link from elsewhere in the app.
     *
     * Posted rather than called directly: nothing has been measured or laid out yet, so the
     * card's y position is still 0 and an immediate scroll would silently do nothing.
     *
     * Fails OPEN — an absent or unrecognised anchor leaves the tab at the top, which is what
     * a reader wants if a link ever goes stale.
     */
    private fun scrollToAnchor(anchor: String?) {
        if (anchor.isNullOrEmpty()) return
        val scroll = findViewById<android.widget.ScrollView>(R.id.fieldGuideScroll) ?: return
        val target = content.findViewWithTag<View>(anchor)
        if (target == null) {
            AppLog.w(TAG, "field guide anchor '$anchor' not found — opening at the top")
            return
        }
        scroll.post {
            // Offset up a little so the reader lands with the entry's title in view.
            scroll.scrollTo(0, (target.top - dp(12)).coerceAtLeast(0))
            AppLog.v(TAG, "field guide opened at '$anchor'")
        }
    }

    // ------------------------------------------------------- content builders

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 24f
        setTypeface(null, android.graphics.Typeface.BOLD)
    })

    private fun lede(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)); textSize = 14f
        setPadding(0, dp(6), 0, dp(4))
    })

    /** The name of the tab, at its top. [showTab] calls this; a tab function does not. */
    private fun section(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun sub(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent)); textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.03f
        setPadding(0, dp(14), 0, dp(6))
    })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun bullet(text: String) = content.addView(TextView(this).apply {
        this.text = "•  $text"
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(8), 0, 0, dp(6))
    })

    /** A numbered procedure: one instruction for each step, in the order the pilot does them. */
    private fun steps(vararg lines: String) {
        for ((i, line) in lines.withIndex()) {
            content.addView(TextView(this).apply {
                text = "${i + 1}.  $line"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(8), 0, 0, dp(if (i == lines.lastIndex) 8 else 4))
            })
        }
    }

    /**
     * A small table. Each argument is one row and " | " divides its cells; the first row is
     * the heading. The first column is narrower than the others, because it holds a name.
     * Plain strings, so a row can be copied as text.
     */
    private fun table(vararg rows: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(2); bottomMargin = dp(10)
            }
        }
        for ((r, row) in rows.withIndex()) {
            if (r > 0) box.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_border))
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
            })
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            for ((c, cell) in row.split(" | ").withIndex()) {
                line.addView(TextView(this).apply {
                    text = cell
                    textSize = 13f
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setTextColor(if (r == 0) Color.WHITE
                        else ContextCompat.getColor(applicationContext, R.color.tp_text_light))
                    if (r == 0 || c == 0) setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, dp(10), 0)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, if (c == 0) 2f else 3f)
                })
            }
            box.addView(line)
        }
        content.addView(box)
    }

    /** Neutral aside — worth knowing, not a hazard. */
    private fun note(text: String) =
        calloutView(text, R.color.tp_accent, R.color.tp_surface_guide_note)

    /** Something that can bite you in the air or on the ground. */
    private fun warn(text: String) =
        calloutView(text, R.color.tp_btn_danger_dialog, R.color.tp_surface_guide_warn)

    /**
     * Callout row: a coloured tint bar against a low-saturation background of the same hue.
     *
     * Takes colour RESOURCES, not hex strings — a colour literal in Kotlin is easy to reach for
     * and easy to miss, so this file follows the same colour-token rule as the layouts.
     */
    private fun calloutView(text: String, @ColorRes barColor: Int, @ColorRes bgColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, bgColor))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
        }
        row.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(applicationContext, barColor))
            layoutParams = LinearLayout.LayoutParams(dp(3), MATCH)
        })
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_dim)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        content.addView(row)
    }

    private fun divider() = content.addView(View(this).apply {
        setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_border))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(20); bottomMargin = dp(12)
        }
    })

    private fun spacer(heightDp: Int) = content.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    })

    /**
     * One control: its icon in each state worth recognising, its name, what it does, and any
     * caveats. [icons] may be empty for parts of the screen that aren't a button.
     */
    private fun entry(
        icons: List<Pair<View, String>>,
        name: String,
        what: String,
        caveats: List<String> = emptyList(),
        anchor: String? = null,
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            // Deep-link target — see EXTRA_SCROLL_TO. A tag rather than a generated view id
            // because the whole guide is built in code and ids would have to be kept unique
            // by hand against a resource file that has no other reason to exist.
            if (anchor != null) tag = anchor
        }

        if (icons.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(10)
                }
            }
            for ((view, caption) in icons) {
                val captionView = TextView(this@FieldGuideActivity).apply {
                    text = caption
                    setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_muted)); textSize = 11f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setPadding(0, dp(5), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                }
                // Size the chip from the MEASURED caption rather than leaving it to wrap_content.
                // Letting the layout work it out doesn't survive here: the icon is a fixed-width
                // child, so the cell settles on the icon's width and a longer caption
                // ("Not connected") gets silently cut to something that reads as a different
                // state ("Not conn"). Measuring the text and flooring the chip to it is the only
                // version that can't clip.
                val captionWidth = captionView.paint.measureText(caption).toInt()
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    // Toolbar-dark chip behind each example: these icons are drawn to sit on
                    // the flight toolbar, and judging them against a lighter card would be
                    // misleading about how they actually read in the air.
                    setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide_code))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    minimumWidth = captionWidth + dp(20)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        rightMargin = dp(8)
                    }
                }
                cell.addView(view)
                cell.addView(captionView)
                strip.addView(cell)
            }
            card.addView(strip)
        }

        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        card.addView(TextView(this).apply {
            text = what
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        for (c in caveats) {
            card.addView(TextView(this).apply {
                text = "!  $c"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_warn)); textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card)
    }

    // ------------------------------------------------- live icon examples

    private fun iconParams(sizeDp: Int = 34) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))

    private fun icon(res: Int) = image(res)

    private fun image(res: Int): View = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = iconParams()
    }

    // Colour tokens, not hex literals (the same colour-token rule as the layouts).
    // Instance-level because resolving a token needs a Context.
    private val connectedGreen by lazy { ContextCompat.getColor(this, R.color.tp_state_go) }
    private val disconnectedRed by lazy { ContextCompat.getColor(this, R.color.tp_state_danger) }
    private val noFixGrey by lazy { ContextCompat.getColor(this, R.color.tp_text_secondary) }

    /** The TAK badge exactly as the toolbar builds it, dot tinted to the state described. */
    private fun takBadge(connected: Boolean): View {
        val frame = android.widget.FrameLayout(this).apply { layoutParams = iconParams() }
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tak_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(34), dp(34))
        })
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.bg_status_dot)
            setColorFilter(if (connected) connectedGreen else disconnectedRed)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        })
        return frame
    }

    private fun battery(pct: Int): View =
        BatteryGaugeView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun signal(pct: Int?): View =
        SignalBarsView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun rec(recording: Boolean): View =
        RecordToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(34))
            setRecording(recording)
        }

    private fun zoomPill(label: String): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_zoom_pill)
        setTextColor(Color.WHITE); textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The IR pill in a state, built from the same drawables the toolbar uses. The label
     *  follows the view the way the zoom pill's label follows its level: "IR" lit for thermal,
     *  "PIP" lit for thermal in the centre. */
    private fun modePill(label: String, on: Boolean): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) connectedGreen else Color.WHITE)
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The AR pill in either state, built from the same drawables the toolbar uses. */
    private fun arPill(on: Boolean): View = TextView(this).apply {
        text = "AR"
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) connectedGreen else Color.WHITE)
        alpha = if (on) 1f else 0.45f
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /**
     * The exterior-lights pill in all THREE of its states, built from the same drawables and
     * the same colours the toolbar uses.
     *
     * Not two bare white bulbs labelled "Lights on" / "Lights off": the button says its state
     * with colour, like AR and IR beside it, so a guide that drew a white bulb for "on" would
     * disagree with the screen the pilot is holding.
     *
     * The amber state is the reason this is worth a helper rather than a tint on an icon: it is
     * the only pill on the flight screen that can say "the aircraft has not answered", and a
     * pilot who has never been shown it has no way to read it at night.
     *
     * ⚠ The lit and unknown states share the PLAIN bulb and take a tint; the dark state keeps
     * the slashed bulb UNTINTED. See FlightActivity.renderLightsButton for why those two facts
     * must stay together.
     */
    private fun lightsPill(dark: Boolean?): View = ImageView(this).apply {
        setImageResource(if (dark == true) R.drawable.ic_led_off else R.drawable.ic_led_on)
        setBackgroundResource(when (dark) {
            false -> R.drawable.bg_pill_active
            true -> R.drawable.bg_zoom_pill
            null -> R.drawable.bg_pill_unknown
        })
        imageTintList = when (dark) {
            false -> androidx.core.content.ContextCompat.getColorStateList(
                this@FieldGuideActivity, R.color.tp_state_go)
            null -> androidx.core.content.ContextCompat.getColorStateList(
                this@FieldGuideActivity, R.color.tp_state_unknown)
            true -> null
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    private fun gps(hasFix: Boolean): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_gps)
        setColorFilter(if (hasFix) connectedGreen else noFixGrey)
        layoutParams = iconParams()
    }

    private fun live(state: LiveToggleView.State): View =
        LiveToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(34))
            setState(state)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TP2Guide"

        /**
         * Optional intent extra: open the guide scrolled to one entry rather than at the top.
         * Value is one of the ANCHOR_* constants, matched against the tag [entry] puts on its
         * card. An unknown or absent value opens at the top, which is the safe default — a
         * deep link that stops matching must not open a blank-looking screen.
         */
        const val EXTRA_SCROLL_TO = "scrollTo"

        /** Which tab is open: an index into [GuideTab]. See [showTab]. */
        private const val EXTRA_TAB = "guide_tab"

        /** The AR overlay entry, reached from the flight screen's AR menu. */
        const val ANCHOR_AR = "ar"

        /**
         * The tab that holds each anchor. A new ANCHOR_* constant gets its line here; one
         * with no line opens the guide on the tab in the intent, with no scroll.
         */
        private val ANCHOR_TABS = mapOf(ANCHOR_AR to GuideTab.MARKERS)

        /** Opens the guide at [anchor] (or the top if null). */
        @JvmStatic
        fun intent(context: android.content.Context, anchor: String? = null) =
            android.content.Intent(context, FieldGuideActivity::class.java).apply {
                if (anchor != null) putExtra(EXTRA_SCROLL_TO, anchor)
            }
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    }
}
