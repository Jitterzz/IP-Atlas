package app.ipatlas.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on screen, in pixels. */
data class Pt(val x: Float, val y: Float)

/** Equal Earth projection (Šavrič, Patterson & Jenny, 2018): the whole world, areas true to size. */
object EqualEarth {
    private const val A1 = 1.340264
    private const val A2 = -0.081106
    private const val A3 = 0.000893
    private const val A4 = 0.003796
    private val M = sqrt(3.0) / 2

    /** x at 180°, y at 90°: the size of the whole map in projection units. */
    val MAX_X = 2 * sqrt(3.0) * PI / (3 * A1)

    fun x(thetaRad: Double, lonDeg: Double): Double {
        val t2 = thetaRad * thetaRad
        val t6 = t2 * t2 * t2
        return 2 * sqrt(3.0) * Math.toRadians(lonDeg) * cos(thetaRad) / (3 * (9 * A4 * t6 * t2 + 7 * A3 * t6 + 3 * A2 * t2 + A1))
    }

    fun y(thetaRad: Double): Double {
        val t2 = thetaRad * thetaRad
        val t6 = t2 * t2 * t2
        return thetaRad * (A1 + A2 * t2 + t6 * (A3 + A4 * t2))
    }

    fun theta(latDeg: Double): Double = asin(M * sin(Math.toRadians(latDeg)))

    /** The θ for a projected y (Newton's method). */
    fun thetaForY(y: Double): Double {
        var t = y / A1
        repeat(10) {
            val t2 = t * t
            val t6 = t2 * t2 * t2
            val f = t * (A1 + A2 * t2 + t6 * (A3 + A4 * t2)) - y
            val fp = A1 + 3 * A2 * t2 + t6 * (7 * A3 + 9 * A4 * t2)
            val d = f / fp
            t -= d
            if (abs(d) < 1e-9) return t
        }
        return t
    }

    fun latForTheta(t: Double): Double = Math.toDegrees(asin((sin(t) / M).coerceIn(-1.0, 1.0)))

    fun lonFor(x: Double, t: Double): Double {
        val t2 = t * t
        val t6 = t2 * t2 * t2
        return Math.toDegrees(3 * x * (9 * A4 * t6 * t2 + 7 * A3 * t6 + 3 * A2 * t2 + A1) / (2 * sqrt(3.0) * cos(t)))
    }
}

/** The world map: Equal Earth, cropped to [topLat]..[bottomLat], fitted inside width × height. */
class WorldFrame(val width: Float, val height: Float, val topLat: Double = 84.0, val bottomLat: Double = -58.0) {
    private val yTop = EqualEarth.y(EqualEarth.theta(topLat))
    private val yBottom = EqualEarth.y(EqualEarth.theta(bottomLat))
    val scale = min(width / (2 * EqualEarth.MAX_X), height / (yTop - yBottom))
    private val ox = width / 2.0
    private val oy = (height - (yTop - yBottom) * scale) / 2 + yTop * scale

    fun project(lat: Double, lon: Double): Pt {
        val t = EqualEarth.theta(lat.coerceIn(-89.9, 89.9))
        return Pt((ox + EqualEarth.x(t, lon) * scale).toFloat(), (oy - EqualEarth.y(t) * scale).toFloat())
    }

    /** Land dots on an even screen grid, as x,y pairs. */
    fun dots(land: LandMask, spacing: Float): FloatArray {
        val out = FloatList()
        val rows = (height / spacing).toInt()
        val cols = (width / spacing).toInt()
        val x0 = (width - (cols - 1) * spacing) / 2
        val y0 = (height - (rows - 1) * spacing) / 2
        for (r in 0 until rows) {
            val sy = y0 + r * spacing
            val y = (oy - sy) / scale
            if (y > yTop || y < yBottom) continue
            val t = EqualEarth.thetaForY(y)
            val lat = EqualEarth.latForTheta(t)
            // Every other row sits half a step over, so the dots pack like a honeycomb.
            val shift = if (r % 2 == 1) spacing / 2 else 0f
            for (c in 0 until cols) {
                val sx = x0 + c * spacing + shift
                val lon = EqualEarth.lonFor((sx - ox) / scale, t)
                if (abs(lon) > 180.0) continue
                if (land.isLand(lat, lon)) out.add(sx, sy)
            }
        }
        return out.toArray()
    }

    /** The outline of the map (the curved edge of the world), as x,y pairs. */
    fun outline(steps: Int = 72): FloatArray {
        val out = FloatList()
        for (i in 0..steps) out.add(project(topLat - (topLat - bottomLat) * i / steps, 180.0))
        for (i in 0..steps) out.add(project(bottomLat + (topLat - bottomLat) * i / steps, -180.0))
        return out.toArray()
    }
}

/**
 * A close-up map: equidistant cylindrical centred on ([lat0], [lon0]), [kmAcross] wide. Distances come out
 * right near the middle, which is all a few hundred kilometers of map needs.
 */
class RegionFrame(
    val width: Float,
    val height: Float,
    val lat0: Double,
    val lon0: Double,
    val kmAcross: Double,
    /** Where on screen the centre point goes, top to bottom (the middle unless something covers part of the map). */
    val centerY: Float = height / 2,
) {
    /** Pixels per degree of latitude. */
    val s = width / (kmAcross / 111.32)
    private val c = max(0.2, cos(Math.toRadians(lat0)))

    val spanLat: Double get() = height / s
    val spanLon: Double get() = width / (s * c)

    fun project(lat: Double, lon: Double): Pt {
        val dLon = wrap(lon - lon0)
        return Pt((width / 2 + dLon * s * c).toFloat(), (centerY - (lat - lat0) * s).toFloat())
    }

    fun latAt(y: Float): Double = lat0 - (y - centerY) / s

    fun lonAt(x: Float): Double = wrap(lon0 + (x - width / 2) / (s * c))

    fun dots(land: LandMask, spacing: Float): FloatArray {
        val out = FloatList()
        val cols = (width / spacing).toInt() + 1
        val rows = (height / spacing).toInt() + 1
        val x0 = (width - (cols - 1) * spacing) / 2
        val y0 = (height - (rows - 1) * spacing) / 2
        for (r in 0 until rows) {
            val y = y0 + r * spacing
            val lat = latAt(y)
            if (lat > 90 || lat < -90) continue
            val shift = if (r % 2 == 1) spacing / 2 else 0f
            for (col in 0 until cols) {
                val x = x0 + col * spacing + shift
                if (land.isLand(lat, lonAt(x))) out.add(x, y)
            }
        }
        return out.toArray()
    }

    /** Lines of latitude and longitude to draw: values in degrees, spaced to give 2–4 lines across. */
    fun graticule(): Pair<List<Double>, List<Double>> {
        val latStep = gridStep(spanLat)
        val lonStep = gridStep(spanLon)
        val lats = steps(latAt(height), latAt(0f), latStep).filter { it > -90 && it < 90 }
        val lons = steps(lon0 - spanLon / 2, lon0 + spanLon / 2, lonStep).map { wrap(it) }
        return lats to lons
    }

    companion object {
        fun wrap(lon: Double): Double {
            var v = (lon + 180.0) % 360.0
            if (v < 0) v += 360.0
            return v - 180.0
        }

        fun gridStep(spanDeg: Double): Double {
            for (step in doubleArrayOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0)) {
                if (spanDeg / step <= 4.5) return step
            }
            return 30.0
        }

        private fun steps(from: Double, to: Double, step: Double): List<Double> {
            val out = ArrayList<Double>()
            var v = floor(from / step) * step + step
            while (v < to) {
                out.add(v)
                v += step
            }
            return out
        }
    }
}

/** "38°N", "122.5°W" */
fun degreeLabel(value: Double, isLat: Boolean): String {
    val hemi = if (isLat) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "W")
    val a = abs(value)
    val num = if (abs(a - Math.round(a)) < 1e-6) Math.round(a).toString() else {
        val s = String.format(java.util.Locale.US, "%.2f", a).trimEnd('0').trimEnd('.')
        s
    }
    return "$num°$hemi"
}

/** A growable list of floats (x,y pairs), without boxing. */
class FloatList {
    private var data = FloatArray(512)
    private var size = 0

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }

    fun add(p: Pt) = add(p.x, p.y)

    fun toArray(): FloatArray = data.copyOf(size)
}
