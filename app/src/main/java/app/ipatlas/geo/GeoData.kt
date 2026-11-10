package app.ipatlas.geo

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream
import kotlin.math.floor

/** Land or water for every 1/20 degree cell of the world (about 5 km). Built by tools/make_geodata.py. */
class LandMask(val width: Int, val height: Int, private val bits: ByteArray) {
    private val cellsPerDegree = width / 360

    fun isLand(lat: Double, lon: Double): Boolean {
        val r = ((90.0 - lat) * cellsPerDegree).toInt().coerceIn(0, height - 1)
        var c = floor((lon + 180.0) * cellsPerDegree).toInt() % width
        if (c < 0) c += width
        val i = r * width + c
        return (bits[i ushr 3].toInt() shr (7 - (i and 7)) and 1) == 1
    }
}

/** A border between two countries, as longitude/latitude points. */
class Border(val lons: FloatArray, val lats: FloatArray) {
    val minLon = lons.min()
    val maxLon = lons.max()
    val minLat = lats.min()
    val maxLat = lats.max()
}

class City(val name: String, val lat: Double, val lon: Double, val population: Int, val countryCode: String)

/**
 * Which country and which state or province (United States, Canada, Australia, Brazil) each 1/4 degree cell
 * is in, with their names. 0 means none (sea, or a country without states in the data).
 */
class RegionMask(
    val width: Int,
    val height: Int,
    private val countries: ByteArray,
    private val states: ByteArray,
    val countryNames: Array<String?>,
    val countryCodes: Array<String?>,
    val stateNames: Array<String?>,
    val stateCountries: Array<String?>,
) {
    private val cellsPerDegree = width / 360.0

    private fun cell(lat: Double, lon: Double): Int {
        val r = ((90.0 - lat) * cellsPerDegree).toInt().coerceIn(0, height - 1)
        var c = floor((lon + 180.0) * cellsPerDegree).toInt() % width
        if (c < 0) c += width
        return r * width + c
    }

    fun countryAt(lat: Double, lon: Double): Int = countries[cell(lat, lon)].toInt() and 0xff

    fun stateAt(lat: Double, lon: Double): Int = states[cell(lat, lon)].toInt() and 0xff

    /** Countries that have states or provinces in the data (their names give way to the state names up close). */
    val countriesWithStates: Set<String> = stateCountries.filterNotNull().toSet()
}

class GeoPack(
    val land: LandMask,
    val borders: List<Border>,
    val states: List<Border>,
    val regions: RegionMask,
    val cities: List<City>,
) {
    /**
     * A town of 15,000+ people to name a spot by, when a service gave no city: the biggest one within 15 km,
     * or else the nearest one within [maxKm].
     */
    fun nearestCity(lat: Double, lon: Double, maxKm: Double): City? {
        var nearest: City? = null
        var nearestKm = maxKm
        var biggest: City? = null
        val dLatMax = maxKm / 111.0
        for (c in cities) {
            if (kotlin.math.abs(c.lat - lat) > dLatMax) continue
            val km = app.ipatlas.data.Geo.distanceKm(lat, lon, c.lat, c.lon)
            if (km < nearestKm) {
                nearestKm = km
                nearest = c
            }
            if (km < 15.0 && (biggest == null || c.population > biggest.population)) biggest = c
        }
        return biggest ?: nearest
    }

    /** The state or province at a spot, if the data has states for that country. */
    fun stateName(lat: Double, lon: Double): String? = regions.stateNames.getOrNull(regions.stateAt(lat, lon))

    fun countryName(lat: Double, lon: Double): String? = regions.countryNames.getOrNull(regions.countryAt(lat, lon))

    fun countryCode(lat: Double, lon: Double): String? = regions.countryCodes.getOrNull(regions.countryAt(lat, lon))
}

/** Reads the three map files (see tools/make_geodata.py for their layout). */
object GeoData {
    fun read(
        land: InputStream,
        borders: InputStream,
        states: InputStream,
        regions: InputStream,
        regionNames: InputStream,
        cities: InputStream,
    ): GeoPack = GeoPack(readLand(land), readBorders(borders), readBorders(states), readRegions(regions, regionNames), readCities(cities))

    private fun readRegions(input: InputStream, names: InputStream): RegionMask {
        val (w, h, grids) = DataInputStream(BufferedInputStream(input)).use { d ->
            val magic = ByteArray(4)
            d.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "RGNS") { "Not a region file" }
            d.readInt() // version
            val w = d.readInt()
            val h = d.readInt()
            Triple(w, h, InflaterInputStream(d).readBytes())
        }
        require(grids.size == 2 * w * h) { "Region file is the wrong size" }
        val countryNames = arrayOfNulls<String>(256)
        val countryCodes = arrayOfNulls<String>(256)
        val stateNames = arrayOfNulls<String>(256)
        val stateCountries = arrayOfNulls<String>(256)
        names.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 4) continue
                val i = p[1].toIntOrNull()?.takeIf { it in 1..255 } ?: continue
                if (p[0] == "C") {
                    countryCodes[i] = p[2]
                    countryNames[i] = p[3]
                } else if (p[0] == "S") {
                    stateCountries[i] = p[2]
                    stateNames[i] = p[3]
                }
            }
        }
        return RegionMask(
            w, h, grids.copyOfRange(0, w * h), grids.copyOfRange(w * h, 2 * w * h),
            countryNames, countryCodes, stateNames, stateCountries,
        )
    }

    private fun readLand(input: InputStream): LandMask = DataInputStream(BufferedInputStream(input)).use { d ->
        val magic = ByteArray(4)
        d.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == "LAND") { "Not a land mask" }
        d.readInt() // version
        val w = d.readInt()
        val h = d.readInt()
        val bits = InflaterInputStream(d).readBytes()
        require(bits.size == w * h / 8) { "Land mask is the wrong size" }
        LandMask(w, h, bits)
    }

    private fun readBorders(input: InputStream): List<Border> = DataInputStream(BufferedInputStream(input)).use { d ->
        val magic = ByteArray(4)
        d.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == "BRDR") { "Not a border file" }
        d.readInt() // version
        val count = d.readInt()
        List(count) {
            val n = d.readInt()
            val lons = FloatArray(n)
            val lats = FloatArray(n)
            for (i in 0 until n) {
                lons[i] = d.readShort() / 100f
                lats[i] = d.readShort() / 100f
            }
            Border(lons, lats)
        }
    }

    private fun readCities(input: InputStream): List<City> = input.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5) return@mapNotNull null
            val lat = p[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
            City(p[0], lat, lon, p[3].toIntOrNull() ?: 0, p[4])
        }.toList()
    }
}
