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

class GeoPack(val land: LandMask, val borders: List<Border>, val cities: List<City>)

/** Reads the three map files (see tools/make_geodata.py for their layout). */
object GeoData {
    fun read(land: InputStream, borders: InputStream, cities: InputStream): GeoPack =
        GeoPack(readLand(land), readBorders(borders), readCities(cities))

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
