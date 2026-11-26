package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.ExpirableBitmapDrawable
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import java.util.concurrent.atomic.AtomicReference

/**
 * THE OFFLINE MAP ON THE FLIGHT SCREEN: an osmdroid module that reads the tiles
 * [CustomMapTiles] saved.
 *
 * ⚠ THE SAVED TILES ARE DRAWN UNDER THE ONLINE MAP, BY osmdroid's OWN RULES. The module sits
 * just before the downloader, and:
 *  - a saved tile of the zoom asked for is returned EXPIRED: osmdroid draws it AND still asks
 *    the downloader, which replaces it when there is a network;
 *  - with only a lower zoom saved (the download stops at [CustomMapTiles.TOP_ZOOM] and the near
 *    view is zoom 18), the right part of the saved tile is cut out, enlarged and returned
 *    SCALED, which osmdroid also treats as "draw this, keep looking".
 * With no network the downloader fails and the saved tile stays on the map.
 *
 * Only for the Custom source, and only when the saved tiles are for its address
 * ([CustomMapTiles.fileFor] checks that).
 */
class SavedMapTiles(private val context: Context) :
    MapTileModuleProviderBase(1, 40) {

    private val template = AtomicReference<String?>(null)

    override fun getName() = "Saved custom map"
    override fun getThreadGroupName() = "savedmap"
    override fun getUsesDataConnection() = false
    override fun getMinimumZoomLevel() = CustomMapTiles.BASE_ZOOM
    override fun getMaximumZoomLevel() = 22

    override fun setTileSource(source: ITileSource?) {
        template.set((source as? MapSourceTiles.CustomSource)?.template)
    }

    override fun getTileLoader(): TileLoader = Loader()

    private inner class Loader : TileLoader() {
        override fun loadTile(index: Long): Drawable? {
            val tmpl = template.get() ?: return null
            val asked = CustomMapTiles.Tile(MapTileIndex.getZoom(index),
                MapTileIndex.getX(index), MapTileIndex.getY(index))
            // The tile itself, else the nearest saved zoom below it.
            for (z in asked.z downTo CustomMapTiles.BASE_ZOOM) {
                val src = CustomMapTiles.ancestor(asked, z)
                val file = CustomMapTiles.fileFor(context, tmpl, src) ?: continue
                val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
                if (z == asked.z) {
                    return ExpirableBitmapDrawable(bitmap).also {
                        ExpirableBitmapDrawable.setState(it, ExpirableBitmapDrawable.EXPIRED)
                    }
                }
                val (col, row, n) = CustomMapTiles.partOf(asked, z)
                val side = bitmap.width / n
                if (side < 1) return null
                val part = Bitmap.createBitmap(bitmap, col * side, row * side, side, side)
                val size = 256
                val scaled = Bitmap.createScaledBitmap(part, size, size, true)
                return ExpirableBitmapDrawable(scaled).also {
                    ExpirableBitmapDrawable.setState(it, ExpirableBitmapDrawable.SCALED)
                }
            }
            return null
        }
    }

    companion object {
        /**
         * A tile provider with this module just before the downloader. The rest is osmdroid's
         * default chain (its own cache first), so Street and Hybrid are not changed by it.
         */
        fun provider(context: Context): MapTileProviderBasic =
            object : MapTileProviderBasic(context.applicationContext) {
                init {
                    val at = mTileProviderList.indexOfFirst { it is MapTileDownloader }
                    val module = SavedMapTiles(context.applicationContext)
                    if (at >= 0) mTileProviderList.add(at, module) else mTileProviderList.add(module)
                }
            }
    }
}
