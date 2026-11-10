package app.ipatlas.geo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Loads the map data from the app's assets once (about 1.5 MB) and keeps it for every chart. */
object GeoCache {
    @Volatile
    private var pack: GeoPack? = null
    private val lock = Mutex()

    fun peek(): GeoPack? = pack

    suspend fun get(context: Context): GeoPack {
        pack?.let { return it }
        return lock.withLock {
            pack ?: withContext(Dispatchers.IO) {
                val assets = context.applicationContext.assets
                GeoData.read(
                    assets.open("geo/land.bin"),
                    assets.open("geo/borders.bin"),
                    assets.open("geo/states.bin"),
                    assets.open("geo/regions.bin"),
                    assets.open("geo/regions.tsv"),
                    assets.open("geo/cities.tsv"),
                )
            }.also { pack = it }
        }
    }
}
