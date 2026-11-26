package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import com.taklite.util.AppLog

/**
 * The osmdroid half of [MapSource]: puts the chosen source on a map.
 *
 * - **Street**: OpenStreetMap ([TileSourceFactory.MAPNIK]), as the flight map always was.
 * - **Hybrid**: Esri World Imagery under a 35 % OpenStreetMap layer:
 *   satellite pictures with the streets and their names.
 * - **Custom**: the pilot's own {z}/{x}/{y} address.
 *
 * ⚠ EACH SOURCE HAS ITS OWN NAME, AND THE NAME IS THE TILE CACHE KEY. A custom source is named
 * from its address, so a new address never shows the tiles of the old one.
 */
object MapSourceTiles {

    private const val TAG = "MapSource"

    /** The Hybrid streets layer on this map, kept so a change of source can remove it. */
    private val overlays = java.util.WeakHashMap<MapView, TilesOverlay>()

    /**
     * Sets the map's source to [effective] (from [MapSource.effective]). Safe to call again:
     * the Hybrid layer of an earlier call is removed first.
     */
    fun apply(context: Context, map: MapView, effective: String) {
        overlays.remove(map)?.let { old ->
            map.overlays.remove(old)
            old.onDetach(map)
        }
        when {
            effective == MapSource.HYBRID -> {
                map.setTileSource(ESRI_IMAGERY)
                val streets = TilesOverlay(
                    MapTileProviderBasic(context.applicationContext, TileSourceFactory.MAPNIK),
                    context.applicationContext,
                ).apply {
                    loadingBackgroundColor = Color.TRANSPARENT
                    loadingLineColor = Color.TRANSPARENT
                    // 35 % opacity: the streets over the picture.
                    setColorFilter(ColorMatrixColorFilter(ColorMatrix().apply {
                        setScale(1f, 1f, 1f, 0.35f)
                    }))
                }
                // FIRST, under the home line and the markers.
                map.overlays.add(0, streets)
                overlays[map] = streets
            }
            effective.startsWith(MapSource.CUSTOM + ":") ->
                map.setTileSource(custom(effective.removePrefix(MapSource.CUSTOM + ":")))
            else -> map.setTileSource(TileSourceFactory.MAPNIK)
        }
        map.invalidate()
        AppLog.i(TAG, "flight map source: ${effective.substringBefore(':')}")
    }

    /** Esri World Imagery. Its address takes the row before the column: {z}/{y}/{x}. */
    private val ESRI_IMAGERY = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "© Esri",
    ) {
        override fun getTileURLString(index: Long): String = baseUrl +
            "${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}"
    }

    private fun custom(template: String): OnlineTileSourceBase = CustomSource(template.trim())

    /**
     * A source for the pilot's own address. The file ending follows the address. [template]
     * is public because [SavedMapTiles] reads the saved tiles only for this address.
     */
    class CustomSource(val template: String) : OnlineTileSourceBase(
        "Custom-" + Integer.toHexString(template.hashCode()), 0, 19, 256,
        if (template.contains(".jpg", ignoreCase = true) ||
            template.contains(".jpeg", ignoreCase = true)) ".jpg" else ".png",
        arrayOf(template),
    ) {
        override fun getTileURLString(index: Long): String = MapSource.tileUrl(
            template, MapTileIndex.getZoom(index), MapTileIndex.getX(index),
            MapTileIndex.getY(index))
    }
}
