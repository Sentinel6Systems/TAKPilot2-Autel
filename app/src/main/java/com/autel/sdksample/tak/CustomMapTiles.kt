package com.autel.sdksample.tak

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * THE CUSTOM MAP, DOWNLOADED FOR USE WITH NO NETWORK.
 *
 * The pilot gives a center and a radius. The pieces of the custom map for that circle are
 * saved on the controller, and [MapSourceTiles] puts them UNDER the online map: with a network
 * the online map replaces them, with no network they are what the flight screen shows.
 *
 * ⚠ HOW THE MAP READS THE FILES: osmdroid takes a tile provider module, [SavedMapTiles],
 * which reads [fileFor].
 *
 * ONLY THE CUSTOM MAP. Street and Hybrid come from public map services, and their terms do
 * not allow a bulk download. The custom map is the user's own server.
 *
 * ## The design decisions
 *
 *  - Area: a center and a radius, [MAX_RADIUS_MI] (15) miles at most.
 *  - Detail: NO CHOICE, and no words about it on the screen. A download goes to zoom
 *    [TOP_ZOOM] (16) and takes a lower zoom only if that would pass [LIMIT_BYTES] (1 GB). The
 *    near view of the flight map asks for zoom [TOP_ZOOM]; a large radius gets less, and the
 *    map engine enlarges what is there, so the map is soft and not empty.
 *  - Areas add up, and each has a name. The pilot sees the list and deletes one area at a
 *    time, as with the terrain regions.
 *
 * ## What a piece is
 *
 * One image of the standard web-map grid, named by zoom / column / row. This file calls it a
 * "tile", as map software does. The pilot's text says "map pieces".
 *
 * The first part of this file is arithmetic only and has no Android in it, so
 * `CustomMapTilesTest` holds it.
 */
object CustomMapTiles {

    // ------------------------------------------------------------------ the arithmetic

    data class Tile(val z: Int, val x: Int, val y: Int)

    /** West, south, east, north, in degrees. */
    data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
        fun union(o: Bounds) = Bounds(min(west, o.west), min(south, o.south),
            max(east, o.east), max(north, o.north))
    }

    /** 15 miles (design decision). */
    const val MAX_RADIUS_MI = 15.0
    /**
     * 1 GB (design decision). A 15-mile circle to zoom 16 is usually a few hundred MB
     * or less, so the limit is far away in normal use. A server with
     * much larger tiles gets a lower zoom, and the download stops itself at the limit.
     */
    const val LIMIT_BYTES = 1024L * 1024 * 1024

    /** How many tiles are measured for the size estimate of one zoom level. */
    private const val SAMPLE_TILES = 16

    /** Storage that a download must leave free on the controller, as the terrain import does. */
    const val FREE_SPACE_RESERVE_BYTES = 500L * 1024 * 1024

    /**
     * THE HIGHEST ZOOM A DOWNLOAD TAKES: 16 (design decision: zoom 16 is enough, and zoom 17
     * is left out).
     *
     * The near view of the flight map is zoom 18 (FlightActivity.MAP_ZOOM_NEAR) and the wide
     * view 15.5. With no network [SavedMapTiles] enlarges the zoom-16 tiles for the near view.
     *
     * ⚠ THE SCREEN SAYS NOTHING ABOUT DETAIL (design decision). No "full", "less" or
     * "soft": the pilot gets a map, and words about its sharpness are not wanted.
     */
    const val TOP_ZOOM = 16

    /** The download does not go below this detail. A radius that cannot fit even this is refused. */
    const val FLOOR_ZOOM = 13

    /** Lower zooms are a few pieces each. They let the map draw at once when it opens. */
    const val BASE_ZOOM = 6

    private const val METERS_PER_MILE = 1609.344
    private const val EARTH_M = 6_371_000.0

    fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val rad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return floor((1.0 - asinh(tan(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun tileLon(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
    private fun tileLat(y: Double, z: Int) =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / (1 shl z)))))

    /** The box around the circle. The download works in this box and keeps what is in the circle. */
    fun boundsOf(lat: Double, lon: Double, radiusMi: Double): Bounds {
        val r = radiusMi * METERS_PER_MILE
        val dLat = Math.toDegrees(r / EARTH_M)
        val dLon = Math.toDegrees(r / (EARTH_M * max(0.01, cos(Math.toRadians(lat)))))
        return Bounds(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
    }

    /**
     * Every tile of zoom [z] that touches the circle.
     *
     * A tile is kept when its CENTER is not farther from the circle's center than the radius
     * plus half the tile's diagonal. That keeps every tile the circle touches and a few more
     * at the edge, which is the safe side for a map.
     */
    fun tilesInCircle(lat: Double, lon: Double, radiusMi: Double, z: Int): List<Tile> {
        val b = boundsOf(lat, lon, radiusMi)
        val x0 = tileX(b.west, z); val x1 = tileX(b.east, z)
        val y0 = tileY(b.north, z); val y1 = tileY(b.south, z)
        val r = radiusMi * METERS_PER_MILE
        val out = ArrayList<Tile>()
        for (x in x0..x1) for (y in y0..y1) {
            val cLat = tileLat(y + 0.5, z); val cLon = tileLon(x + 0.5, z)
            val heightM = Math.toRadians(tileLat(y.toDouble(), z) - tileLat(y + 1.0, z)) * EARTH_M
            val widthM = Math.toRadians(360.0 / (1 shl z)) * EARTH_M * cos(Math.toRadians(cLat))
            val halfDiagonal = sqrt(heightM * heightM + widthM * widthM) / 2.0
            if (distanceM(lat, lon, cLat, cLon) <= r + halfDiagonal) out.add(Tile(z, x, y))
        }
        return out
    }

    /** Flat-earth distance. Good to well under one percent at 25 miles, and the limit is 15. */
    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1) * EARTH_M
        val dLon = Math.toRadians(lon2 - lon1) * EARTH_M * cos(Math.toRadians((lat1 + lat2) / 2.0))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    /** How many tiles the zooms [BASE_ZOOM]..[maxZoom] hold for the circle. */
    fun countUpTo(lat: Double, lon: Double, radiusMi: Double, maxZoom: Int): Int =
        (BASE_ZOOM..maxZoom).sumOf { tilesInCircle(lat, lon, radiusMi, it).size }

    /**
     * The best detail that fits the limit: the highest zoom, from [TOP_ZOOM] down to
     * [FLOOR_ZOOM], for which the whole download is expected to be under [limitBytes].
     *
     * @param bytesPerTile the size of one tile at a zoom, measured on the server. Tiles are
     *   not all one size, so this is an estimate; the download also stops itself at the limit.
     * @return null when not even [FLOOR_ZOOM] fits. The pilot must use a smaller radius.
     */
    fun pickMaxZoom(
        lat: Double, lon: Double, radiusMi: Double,
        limitBytes: Long = LIMIT_BYTES,
        bytesPerTile: (zoom: Int) -> Long,
    ): Int? {
        for (z in TOP_ZOOM downTo FLOOR_ZOOM) {
            val expected = countUpTo(lat, lon, radiusMi, z).toLong() * bytesPerTile(z)
            if (expected <= limitBytes) return z
        }
        return null
    }

    /** The tile of zoom [z] (not above [t]'s) that holds [t]. */
    fun ancestor(t: Tile, z: Int): Tile {
        val dz = t.z - z
        return Tile(z, t.x shr dz, t.y shr dz)
    }

    /** Which part of [ancestor] holds [t]: its column, its row, and the parts on a side. */
    fun partOf(t: Tile, z: Int): Triple<Int, Int, Int> {
        val n = 1 shl (t.z - z)
        return Triple(t.x and (n - 1), t.y and (n - 1), n)
    }

    /** The address of one tile. The three names are replaced in any letter case. */
    fun urlFor(template: String, t: Tile): String = template.trim()
        .replace("{z}", t.z.toString(), ignoreCase = true)
        .replace("{x}", t.x.toString(), ignoreCase = true)
        .replace("{y}", t.y.toString(), ignoreCase = true)

    // ------------------------------------------------------------------ the store

    private const val TAG = "CustomMapTiles"
    private const val PREFS = "takpilot2_tak"
    private const val DIR = "custom_map_tiles"
    private const val KEY_TEMPLATE = "map_dl_template"
    private const val KEY_AREAS = "map_dl_areas"

    /**
     * ONE DOWNLOADED AREA, WITH THE NAME THE PILOT GAVE IT (design decision). The pilot
     * must be able to see what is on the controller and when it was saved, and to delete one
     * area and keep the others, as with the terrain regions.
     *
     * The tiles of all areas are in ONE pool of files. An area is its circle, not a folder:
     * two areas that overlap share the tiles in the overlap, and a tile is removed only when
     * no area that is left needs it. See [prune].
     */
    data class Area(
        val id: Long, val name: String, val lat: Double, val lon: Double, val radiusMi: Double,
        val maxZoom: Int, val count: Int, val bytes: Long, val timeMs: Long,
    )

    /** All the areas together. */
    data class Saved(val template: String, val maxZoom: Int, val bounds: Bounds)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(context: Context) = File(context.filesDir, DIR)
    private fun file(root: File, t: Tile) = File(root, "${t.z}/${t.x}/${t.y}.tile")

    /** The map address the saved areas are for, or null when no area is saved. */
    fun savedTemplate(context: Context): String? =
        if (areas(context).isEmpty()) null else prefs(context).getString(KEY_TEMPLATE, null)

    /** The downloaded areas, the newest first. */
    fun areas(context: Context): List<Area> {
        val text = prefs(context).getString(KEY_AREAS, null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Area(o.getLong("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"),
                    o.getDouble("radiusMi"), o.getInt("maxZoom"), o.getInt("count"),
                    o.getLong("bytes"), o.getLong("timeMs"))
            }.sortedByDescending { it.timeMs }
        } catch (t: Throwable) {
            AppLog.w(TAG, "the list of areas could not be read: ${t.message}")
            emptyList()
        }
    }

    private fun writeAreas(context: Context, template: String, list: List<Area>) {
        val array = org.json.JSONArray()
        for (a in list) array.put(org.json.JSONObject()
            .put("id", a.id).put("name", a.name).put("lat", a.lat).put("lon", a.lon)
            .put("radiusMi", a.radiusMi).put("maxZoom", a.maxZoom).put("count", a.count)
            .put("bytes", a.bytes).put("timeMs", a.timeMs))
        prefs(context).edit().putString(KEY_TEMPLATE, template)
            .putString(KEY_AREAS, array.toString()).apply()
    }

    /** What the map engine needs, or null when no area is saved. */
    fun saved(context: Context): Saved? {
        val list = areas(context)
        if (list.isEmpty()) return null
        val template = prefs(context).getString(KEY_TEMPLATE, null) ?: return null
        val box = list.map { boundsOf(it.lat, it.lon, it.radiusMi) }.reduce { a, b -> a.union(b) }
        return Saved(template, list.maxOf { it.maxZoom }, box)
    }

    /**
     * The saved file of one tile, or null when there is none or the saved tiles are not for
     * [template]. Tiles of one server must not show as the map of another. [SavedMapTiles]
     * reads this on its own thread, so it reads the list of areas from [saved] each time.
     */
    fun fileFor(context: Context, template: String, t: Tile): File? {
        val s = saved(context) ?: return null
        if (s.template != template.trim()) return null
        return file(dir(context), t).takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Removes every tile file that no area in [keep] needs. This is the one place a tile is
     * deleted, so an area that is deleted cannot take a tile that its neighbor still uses.
     */
    private fun prune(context: Context, keep: List<Area>) {
        val root = dir(context)
        if (!root.isDirectory) return
        if (keep.isEmpty()) { root.deleteRecursively(); return }
        val needed = HashSet<Long>()
        for (a in keep) for (z in BASE_ZOOM..a.maxZoom) {
            for (t in tilesInCircle(a.lat, a.lon, a.radiusMi, z)) needed.add(key(t))
        }
        var removed = 0
        root.walkTopDown().filter { it.isFile }.toList().forEach { f ->
            val t = tileOf(root, f)
            if (t == null || key(t) !in needed) { if (f.delete()) removed++ }
        }
        if (removed > 0) AppLog.i(TAG, "removed $removed map files that no area uses")
    }

    private fun key(t: Tile): Long = (t.z.toLong() shl 50) or (t.x.toLong() shl 25) or t.y.toLong()

    /** The tile a file holds, from its path z/x/y.tile, or null for any other file. */
    private fun tileOf(root: File, f: File): Tile? {
        if (!f.name.endsWith(".tile")) return null
        val y = f.name.removeSuffix(".tile").toIntOrNull() ?: return null
        val x = f.parentFile?.name?.toIntOrNull() ?: return null
        val z = f.parentFile?.parentFile?.takeIf { it.parentFile == root }?.name?.toIntOrNull()
            ?: return null
        return Tile(z, x, y)
    }

    /** Deletes one area and the tiles that only it used. [onDone] comes on the main thread. */
    fun deleteArea(context: Context, id: Long, onDone: () -> Unit) {
        // ⚠ NOT WHILE A DOWNLOAD RUNS: the files of a download are not in the list yet, so
        // the clean-up after a delete would remove them.
        if (running) { main.post(onDone); return }
        val app = context.applicationContext
        Thread({
            val left = areas(app).filter { it.id != id }
            val template = prefs(app).getString(KEY_TEMPLATE, "") ?: ""
            writeAreas(app, template, left)
            runCatching { prune(app, left) }
            AppLog.i(TAG, "area #$id deleted, ${left.size} left")
            main.post(onDone)
        }, "custom-map-delete").start()
    }

    // ------------------------------------------------------------------ the download

    data class Progress(val done: Int, val total: Int, val bytes: Long, val maxZoom: Int)

    data class Result(
        val saved: Int, val alreadyThere: Int, val notOnServer: Int, val failed: Int,
        val bytes: Long, val maxZoom: Int,
        val stoppedByPilot: Boolean, val stoppedAtLimit: Boolean, val error: String?,
    )

    /** True while a download runs. It runs on with the screen closed. */
    @Volatile var running = false
        private set
    @Volatile var progress: Progress? = null
        private set
    /** The screen that shows the download. One slot: the owner clears only its own. */
    @Volatile var onChanged: (() -> Unit)? = null

    private val stop = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private fun changed() = main.post { onChanged?.invoke() }

    /** Asks a running download to stop. The tiles it saved stay. */
    fun stop() { stop.set(true) }

    /**
     * Downloads the custom map for a circle and adds it to the list as [name]. An area that
     * has the same name already is replaced. [onDone] comes on the main thread.
     *
     * A tile that is on the controller already is not asked for again. A tile the server
     * does not have (it answers 404 or 204) is counted and is not an error: a map does not
     * have to cover the whole circle.
     */
    fun download(
        context: Context, template: String, name: String,
        lat: Double, lon: Double, radiusMi: Double,
        onDone: (Result) -> Unit,
    ) {
        if (running) return
        running = true
        stop.set(false)
        progress = null
        val app = context.applicationContext
        Thread({
            val result = try {
                run(app, template.trim(), name.trim(), lat, lon, radiusMi)
            } catch (t: Throwable) {
                AppLog.e(TAG, "download failed: ${t.message}", t)
                Result(0, 0, 0, 0, 0L, 0, false, false, t.message ?: "The download stopped.")
            }
            running = false
            // The screen's own state first, then the result: the result line must be the
            // last thing painted, or the state line covers "The download is complete."
            main.post { onChanged?.invoke(); onDone(result) }
        }, "custom-map-download").start()
    }

    private fun run(
        app: Context, template: String, name: String,
        lat: Double, lon: Double, radiusMi: Double,
    ): Result {
        // Areas of a different address are not this map. They go before the new one comes.
        var before = areas(app)
        if (before.isNotEmpty() && prefs(app).getString(KEY_TEMPLATE, null) != template) {
            writeAreas(app, template, emptyList())
            prune(app, emptyList())
            before = emptyList()
        }

        // The size of a tile, for the estimate: the AVERAGE of [SAMPLE_TILES] tiles spread
        // over the circle, at each zoom that is tried.
        // ⚠ NOT THE ONE TILE AT THE CENTER. The center is where the pilot is, often a built-up
        // area, and a built-up area's tile can be about twice the size of a tile of open
        // country. Estimated from that one tile, a circle can be put far over the limit and get
        // one level less detail although full detail would fit well inside it.
        val sizes = HashMap<Int, Long>()
        val maxZoom = pickMaxZoom(lat, lon, radiusMi) { z ->
            sizes.getOrPut(z) {
                val all = tilesInCircle(lat, lon, radiusMi, z)
                val step = (all.size / SAMPLE_TILES).coerceAtLeast(1)
                val sample = all.filterIndexed { i, _ -> i % step == step / 2 }.take(SAMPLE_TILES)
                // A tile the server does not have costs nothing, and is counted as nothing.
                val total = sample.sumOf { fetchOrMissing(urlFor(template, it))?.size?.toLong() ?: 0L }
                (total / sample.size.coerceAtLeast(1)).coerceAtLeast(1_000L)
            }
        } ?: return Result(0, 0, 0, 0, 0L, 0, false, false,
            "This radius is too large to save. Use a smaller radius.")

        val tiles = (BASE_ZOOM..maxZoom).flatMap { tilesInCircle(lat, lon, radiusMi, it) }
        val root = dir(app)
        // A controller that fills up in the field cannot record a flight. The download is
        // refused when it would not leave the reserve free.
        val expected = tiles.size.toLong() * (sizes[maxZoom] ?: 4_000L)   // at the top zoom's size: the safe side
        if (app.filesDir.usableSpace < expected + FREE_SPACE_RESERVE_BYTES) {
            return Result(0, 0, 0, 0, 0L, maxZoom, false, false,
                "The controller does not have enough free storage for this area. " +
                    "Delete an area or use a smaller radius.")
        }
        val done = AtomicInteger(0); val saved = AtomicInteger(0); val there = AtomicInteger(0)
        val missing = AtomicInteger(0); val failed = AtomicInteger(0)
        val bytes = AtomicLong(0L); val atLimit = AtomicBoolean(false)
        AppLog.i(TAG, "download: ${tiles.size} tiles to zoom $maxZoom, radius $radiusMi mi")

        val pool = Executors.newFixedThreadPool(4)
        for (t in tiles) pool.execute {
            if (stop.get() || atLimit.get()) return@execute
            val f = file(root, t)
            if (f.isFile && f.length() > 0) {
                there.incrementAndGet()
            } else when (val body = fetchOrMissing(urlFor(template, t))) {
                null -> failed.incrementAndGet()
                EMPTY -> missing.incrementAndGet()
                else -> {
                    // A temporary file first, then the name: a download that is stopped must
                    // not leave half a picture where the map engine will read it.
                    f.parentFile?.mkdirs()
                    val tmp = File(f.path + ".part")
                    tmp.writeBytes(body)
                    if (tmp.renameTo(f)) saved.incrementAndGet() else failed.incrementAndGet()
                    if (bytes.addAndGet(body.size.toLong()) >= LIMIT_BYTES) atLimit.set(true)
                }
            }
            val n = done.incrementAndGet()
            if (n % 25 == 0 || n == tiles.size) {
                progress = Progress(n, tiles.size, bytes.get(), maxZoom)
                changed()
            }
            // Many failures and nothing saved mean no network or a wrong address.
            if (failed.get() >= 40 && saved.get() == 0) stop.set(true)
        }
        pool.shutdown()
        pool.awaitTermination(6, TimeUnit.HOURS)

        // The area is what its circle holds on the controller NOW, counted from the files:
        // a download that was stopped keeps what it saved, and says how much that is.
        var count = 0; var total = 0L
        for (t in tiles) file(root, t).let { if (it.isFile) { count++; total += it.length() } }
        if (count > 0) {
            val area = Area(System.currentTimeMillis(), name, lat, lon, radiusMi, maxZoom,
                count, total, System.currentTimeMillis())
            val list = before.filter { !it.name.equals(name, ignoreCase = true) } + area
            writeAreas(app, template, list)
            // An area of the same name that was replaced can leave tiles that nothing uses,
            // and so can a download that was stopped before it was put in the list.
            runCatching { prune(app, list) }
        }
        val noNetwork = failed.get() >= 40 && saved.get() == 0
        return Result(saved.get(), there.get(), missing.get(), failed.get(), bytes.get(), maxZoom,
            stoppedByPilot = stop.get() && !noNetwork, stoppedAtLimit = atLimit.get(),
            error = if (noNetwork) "The app could not get the map from the server. " +
                "Check the network and the address." else null)
    }

    /** Marks "the server has no such tile". Compared by identity. */
    private val EMPTY = ByteArray(0)

    /** The tile, [EMPTY] when the server has none, null when the request failed. */
    private fun fetchOrMissing(url: String): ByteArray? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 20_000
                setRequestProperty("User-Agent", "TAKPilot2")
            }
            when (c.responseCode) {
                200 -> c.inputStream.use { it.readBytes() }.takeIf { it.isNotEmpty() } ?: EMPTY
                204, 404 -> EMPTY
                else -> null
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    private fun fetch(url: String): ByteArray? = fetchOrMissing(url)?.takeIf { it !== EMPTY }
}
