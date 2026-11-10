@file:OptIn(ExperimentalTextApi::class)

package app.ipatlas.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ipatlas.geo.Border
import app.ipatlas.geo.GeoCache
import app.ipatlas.geo.GeoPack
import app.ipatlas.geo.RegionFrame
import app.ipatlas.geo.WorldFrame
import app.ipatlas.geo.degreeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

/** Map distances to choose from with the zoom buttons, in kilometers across the screen. */
val ZoomSteps = listOf(250.0, 600.0, 1500.0, 3500.0, 8000.0)

@Composable
fun rememberGeo(): GeoPack? {
    val context = LocalContext.current
    val geo by produceState(GeoCache.peek(), context) {
        if (value == null) value = GeoCache.get(context)
    }
    return geo
}

/**
 * The whole world as a dotted chart (Equal Earth projection), drawn inside the box between [top] and
 * [bottom] padding. [pinLat]/[pinLon] marks a place with the chart's magenta light.
 */
@Composable
fun WorldChart(
    modifier: Modifier = Modifier,
    pinLat: Double? = null,
    pinLon: Double? = null,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    side: Dp = 12.dp,
    dotSpacing: Dp = 3.2.dp,
    scanning: Boolean = false,
    pingKey: Any? = null,
) {
    val p = LocalPalette.current
    val geo = rememberGeo()
    BoxWithConstraints(modifier.background(p.chart)) {
        val density = LocalDensity.current
        val w = with(density) { (maxWidth - side * 2).toPx() }
        val h = with(density) { (maxHeight - top - bottom).toPx() }
        val left = with(density) { side.toPx() }
        val topPx = with(density) { top.toPx() }
        val spacing = with(density) { dotSpacing.toPx() }
        if (w > 0 && h > 0) {
            val frame = remember(w, h) { WorldFrame(w, h) }
            val dots by produceState<List<Offset>?>(null, geo, frame, spacing) {
                val g = geo ?: return@produceState
                value = withContext(Dispatchers.Default) { frame.dots(g.land, spacing).toOffsets(left, topPx) }
            }
            val outline = remember(frame) { frame.outline().toOffsets(left, topPx) }
            val graticule = remember(frame) { worldGraticule(frame, left, topPx) }
            Canvas(Modifier.fillMaxSize()) {
                val hair = 1.dp.toPx() * 0.8f
                for (line in graticule) drawPoints(line, PointMode.Polygon, p.graticule, strokeWidth = hair)
                drawPoints(outline, PointMode.Polygon, p.graticule, strokeWidth = hair)
                dots?.let { drawPoints(it, PointMode.Points, p.land.copy(alpha = if (p.dark) 0.6f else 0.8f), strokeWidth = spacing * 0.5f, cap = StrokeCap.Round) }
            }
            if (scanning) ScanSweep(left, topPx, w, h)
            if (pinLat != null && pinLon != null) {
                val pt = frame.project(pinLat, pinLon)
                PinLayer(Offset(left + pt.x, topPx + pt.y), flareLength = 17.dp, dotRadius = 3.5.dp, pingKey = pingKey)
            }
        }
    }
}

private fun FloatArray.toOffsets(dx: Float, dy: Float): List<Offset> = List(size / 2) { Offset(this[2 * it] + dx, this[2 * it + 1] + dy) }

private fun worldGraticule(frame: WorldFrame, dx: Float, dy: Float): List<List<Offset>> {
    val lines = ArrayList<List<Offset>>()
    for (lon in -150..150 step 30) {
        lines += (0..71).map { i ->
            val lat = frame.topLat - (frame.topLat - frame.bottomLat) * i / 71.0
            frame.project(lat, lon.toDouble()).let { Offset(it.x + dx, it.y + dy) }
        }
    }
    for (lat in listOf(-30.0, 0.0, 30.0, 60.0)) {
        lines += (0..72).map { i -> frame.project(lat, -180.0 + i * 5.0).let { Offset(it.x + dx, it.y + dy) } }
    }
    return lines
}

/** A slow meridian line sweeping across the world while a lookup runs. */
@Composable
private fun ScanSweep(left: Float, top: Float, w: Float, h: Float) {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "scan")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Canvas(Modifier.fillMaxSize()) {
        val sx = left + x * w
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, p.flare.copy(alpha = 0.14f)), startX = sx - 60.dp.toPx(), endX = sx),
            topLeft = Offset(sx - 60.dp.toPx(), top),
            size = androidx.compose.ui.geometry.Size(60.dp.toPx(), h),
        )
        drawLine(p.flare.copy(alpha = 0.55f), Offset(sx, top), Offset(sx, top + h), strokeWidth = 1.dp.toPx())
    }
}

/**
 * A close-up chart around ([lat], [lon]): latitude/longitude lines, country borders, dotted land, nearby
 * cities, a scale bar, and the magenta light on the spot. [top] and [bottom] are the parts covered by other
 * things (status bar, buttons, the fade into the page), so the spot is centred in what's left.
 */
@Composable
fun RegionChart(
    lat: Double,
    lon: Double,
    kmAcross: Double,
    label: String?,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    pingKey: Any? = null,
) {
    val p = LocalPalette.current
    val geo = rememberGeo()
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.background(p.chart)) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val topPx = with(density) { top.toPx() }
        val bottomPx = with(density) { bottom.toPx() }
        val spacing = with(density) { 4.2.dp.toPx() }
        if (w > 0 && h > 0) {
            val centerY = topPx + (h - topPx - bottomPx) * 0.5f
            val frame = remember(w, h, lat, lon, kmAcross, centerY) { RegionFrame(w, h, lat, lon, kmAcross, centerY) }
            val dots by produceState<List<Offset>?>(null, geo, frame, spacing) {
                val g = geo ?: return@produceState
                value = withContext(Dispatchers.Default) { frame.dots(g.land, spacing).toOffsets(0f, 0f) }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRegionGridLines(frame, p)
                dots?.let { drawPoints(it, PointMode.Points, p.land.copy(alpha = if (p.dark) 0.62f else 0.82f), strokeWidth = spacing * 0.5f, cap = StrokeCap.Round) }
                // State lines (dashed) and country borders (dash-dot) on top of the land, each on a thin band of
                // water color so they stand out from the dots.
                geo?.let {
                    drawLines(it.states, frame, p.ink2.copy(alpha = 0.7f), 1f, floatArrayOf(4f, 2.5f), p.chart, 3f)
                    drawLines(it.borders, frame, p.ink2.copy(alpha = 0.9f), 1.3f, floatArrayOf(7f, 2.5f, 1.5f, 2.5f), p.chart, 4f)
                }
                drawRegionGridLabels(frame, measurer, p, topPx, bottomPx)
                val pin = frame.project(lat, lon).let { Offset(it.x, it.y) }
                geo?.let { drawPlaceNames(it, frame, measurer, p, pin, label, topPx, bottomPx) }
                // The page fades in under the bottom of the chart.
                if (bottomPx > 0f) {
                    drawRect(
                        Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg), startY = h - bottomPx * 0.9f, endY = h),
                        topLeft = Offset(0f, h - bottomPx * 0.9f),
                        size = androidx.compose.ui.geometry.Size(w, bottomPx * 0.9f),
                    )
                }
                if (topPx > 0f) {
                    drawRect(
                        Brush.verticalGradient(listOf(p.chart.copy(alpha = 0.92f), p.chart.copy(alpha = 0f)), startY = 0f, endY = topPx * 1.4f),
                        size = androidx.compose.ui.geometry.Size(w, topPx * 1.4f),
                    )
                }
                drawScaleBar(frame, measurer, p, bottomPx)
            }
            val pt = frame.project(lat, lon)
            PinLayer(Offset(pt.x, pt.y), flareLength = 24.dp, dotRadius = 4.5.dp, pingKey = pingKey, label = label, measurer = measurer)
        }
    }
}

private fun DrawScope.drawRegionGridLines(frame: RegionFrame, p: Palette) {
    val (lats, lons) = frame.graticule()
    val hair = 1.dp.toPx() * 0.8f
    for (la in lats) {
        val y = frame.project(la, frame.lon0).y
        drawLine(p.graticule, Offset(0f, y), Offset(size.width, y), strokeWidth = hair)
    }
    for (lo in lons) {
        val x = frame.project(frame.lat0, lo).x
        drawLine(p.graticule, Offset(x, 0f), Offset(x, size.height), strokeWidth = hair)
    }
}

/** Degree labels along the right edge (latitude) and above the fade (longitude), drawn on top of the land. */
private fun DrawScope.drawRegionGridLabels(frame: RegionFrame, measurer: TextMeasurer, p: Palette, topPx: Float, bottomPx: Float) {
    val (lats, lons) = frame.graticule()
    val style = Type.mapGrid.copy(color = p.ink3)
    for (la in lats) {
        val y = frame.project(la, frame.lon0).y
        if (y > topPx + 14.dp.toPx() && y < size.height - bottomPx - 4.dp.toPx()) {
            val t = measurer.measure(degreeLabel(la, true), style)
            drawText(t, topLeft = Offset(size.width - t.size.width - 6.dp.toPx(), y - t.size.height - 1.dp.toPx()))
        }
    }
    for (lo in lons) {
        val x = frame.project(frame.lat0, lo).x
        val t = measurer.measure(degreeLabel(lo, false), style)
        val y = size.height - bottomPx - t.size.height - 2.dp.toPx()
        if (x + t.size.width + 4.dp.toPx() < size.width) drawText(t, topLeft = Offset(x + 3.dp.toPx(), y))
    }
}

/** Country borders or state lines: a [halo] band under a dashed line ([dash] and widths in dp). */
private fun DrawScope.drawLines(
    lines: List<Border>,
    frame: RegionFrame,
    color: Color,
    widthDp: Float,
    dash: FloatArray,
    halo: Color,
    haloWidthDp: Float,
) {
    val marginLat = frame.spanLat
    val marginLon = frame.spanLon
    val effect = PathEffect.dashPathEffect(FloatArray(dash.size) { dash[it] * 1.dp.toPx() }, 0f)
    for (b in lines) {
        if (b.maxLat < frame.lat0 - marginLat || b.minLat > frame.lat0 + marginLat) continue
        if (abs(RegionFrame.wrap((b.minLon + b.maxLon) / 2.0 - frame.lon0)) > marginLon + (b.maxLon - b.minLon)) continue
        val path = Path()
        for (i in b.lons.indices) {
            val pt = frame.project(b.lats[i].toDouble(), b.lons[i].toDouble())
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(path, halo.copy(alpha = 0.85f), style = Stroke(width = haloWidthDp * 1.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, color, style = Stroke(width = widthDp * 1.dp.toPx(), pathEffect = effect))
    }
}

/**
 * Names on the chart: towns and cities first (biggest first, more of them closer in), then the states,
 * provinces and countries in the room that's left, each at the middle of its part of the chart.
 */
private fun DrawScope.drawPlaceNames(
    geo: GeoPack,
    frame: RegionFrame,
    measurer: TextMeasurer,
    p: Palette,
    pin: Offset,
    label: String?,
    topPx: Float,
    bottomPx: Float,
) {
    val dp = 1.dp.toPx()
    val placed = ArrayList<Rect>()
    // Keep clear of the light and its label.
    placed += Rect(pin.x - 8 * dp, pin.y - 24 * dp, pin.x + 22 * dp, pin.y + 8 * dp)
    if (label != null) {
        val t = measurer.measure(label, Type.mapPlace)
        placed += Rect(pin.x + 8 * dp, pin.y + 2 * dp, pin.x + 14 * dp + t.size.width, pin.y + 8 * dp + t.size.height)
    }
    val top = topPx + 8 * dp
    val bottom = size.height - bottomPx - 18 * dp

    // Cities and towns.
    val maxCities = when {
        frame.kmAcross <= 300 -> 12
        frame.kmAcross <= 700 -> 10
        frame.kmAcross <= 1600 -> 9
        else -> 8
    }
    val cityStyle = Type.mapLabel.copy(color = p.ink2)
    val cities = ArrayList<Triple<Offset, TextLayoutResult, Float>>()
    for (c in geo.cities) {
        if (cities.size >= maxCities) break
        val pt = frame.project(c.lat, c.lon)
        if (pt.x < 6 * dp || pt.x > size.width - 6 * dp || pt.y < top || pt.y > bottom) continue
        if (hypot(pt.x - pin.x, pt.y - pin.y) < 22 * dp) continue
        val t = measurer.measure(c.name, cityStyle)
        val tw = t.size.width
        val th = t.size.height
        // Name to the right of the dot if there's room, otherwise to the left; skip the city if neither fits.
        val right = Rect(pt.x - 3 * dp, pt.y - th / 2f, pt.x + 6 * dp + tw, pt.y + th / 2f)
        val left = Rect(pt.x - 6 * dp - tw, pt.y - th / 2f, pt.x + 3 * dp, pt.y + th / 2f)
        val fitsRight = right.right <= size.width - 2 * dp && placed.none { it.overlaps(right.inflate(2 * dp)) }
        val fitsLeft = left.left >= 2 * dp && placed.none { it.overlaps(left.inflate(2 * dp)) }
        if (!fitsRight && !fitsLeft) continue
        val leftSide = !fitsRight
        placed += if (leftSide) left else right
        cities += Triple(Offset(pt.x, pt.y), t, if (leftSide) pt.x - 5 * dp - tw else pt.x + 5 * dp)
    }

    // States, provinces and countries: find where each one's cells fall on screen, then put its name as near
    // their middle as there's room for.
    val step = 14 * dp
    val showStates = frame.kmAcross <= 3600
    val showAllCountries = frame.kmAcross >= 3000
    fun regionKey(la: Double, lo: Double): Int {
        if (la <= -85 || la >= 85) return 0
        val s = if (showStates) geo.regions.stateAt(la, lo) else 0
        if (s != 0) return 1000 + s
        val c = geo.regions.countryAt(la, lo)
        return if (c != 0 && (showAllCountries || geo.regions.countryCodes[c] !in geo.regions.countriesWithStates)) c else 0
    }
    val cells = HashMap<Int, ArrayList<Offset>>()
    var y = top + step / 2
    while (y < bottom) {
        var x = step / 2
        while (x < size.width) {
            val key = regionKey(frame.latAt(y), frame.lonAt(x))
            if (key != 0) cells.getOrPut(key) { ArrayList() }.add(Offset(x, y))
            x += step
        }
        y += step
    }
    val regionStyle = Type.mapRegion.copy(color = p.ink2)
    val regions = ArrayList<Pair<Offset, TextLayoutResult>>()
    for ((key, pts) in cells.entries.sortedByDescending { it.value.size }) {
        if (regions.size >= 6 || pts.size < 4) break
        val name = (if (key > 1000) geo.regions.stateNames[key - 1000] else geo.regions.countryNames[key]) ?: continue
        val t = measurer.measure(name.uppercase(), regionStyle)
        val tw = t.size.width
        val th = t.size.height
        val mx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
        val my = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
        val middle = Offset(mx, my)
        val inside = regionKey(frame.latAt(my), frame.lonAt(mx)) == key
        val candidates = (if (inside) listOf(middle) else emptyList()) +
            pts.sortedBy { (it.x - mx) * (it.x - mx) + (it.y - my) * (it.y - my) }
        for (c in candidates) {
            val cx = c.x.coerceIn(tw / 2f + 4 * dp, size.width - tw / 2f - 4 * dp)
            val cy = c.y.coerceIn(top + th / 2f, bottom - th / 2f)
            val rect = Rect(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
            if (placed.any { it.overlaps(rect.inflate(3 * dp)) }) continue
            placed += rect
            regions += Offset(rect.left, rect.top) to t
            break
        }
    }

    for ((at, t) in regions) {
        drawRoundRect(
            p.chart.copy(alpha = 0.72f),
            topLeft = Offset(at.x - 3 * dp, at.y - 1 * dp),
            size = androidx.compose.ui.geometry.Size(t.size.width + 6 * dp, t.size.height + 2 * dp),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * dp),
        )
        drawText(t, topLeft = at)
    }
    for ((pt, t, x) in cities) {
        drawRoundRect(
            p.chart.copy(alpha = 0.7f),
            topLeft = Offset(x - 2 * dp, pt.y - t.size.height / 2f),
            size = androidx.compose.ui.geometry.Size(t.size.width + 4 * dp, t.size.height.toFloat()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * dp),
        )
        drawCircle(p.ink2, radius = 2.2f * dp, center = pt)
        drawCircle(p.chart, radius = 2.2f * dp, center = pt, style = Stroke(width = 1f * dp))
        drawText(t, topLeft = Offset(x, pt.y - t.size.height / 2f))
    }
}

/** A chart's scale bar: a round number of kilometers, about a quarter of the width. */
private fun DrawScope.drawScaleBar(frame: RegionFrame, measurer: TextMeasurer, p: Palette, bottomPx: Float) {
    val kmPerPx = frame.kmAcross / size.width
    val target = size.width * 0.24 * kmPerPx
    val mag = 10.0.pow(floor(log10(target)))
    val nice = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.last { it <= target }
    val len = (nice / kmPerPx).toFloat()
    val dp = 1.dp.toPx()
    val x0 = 16 * dp
    val y = size.height - bottomPx + 14 * dp - (if (bottomPx > 0f) 0f else 24 * dp)
    val text = measurer.measure(if (nice >= 1) "${nice.toLong()} km" else "$nice km", Type.mapGrid.copy(color = p.ink2))
    drawText(text, topLeft = Offset(x0, y - text.size.height - 4 * dp))
    drawLine(p.ink2, Offset(x0, y), Offset(x0 + len, y), strokeWidth = 1.5f * dp)
    drawLine(p.ink2, Offset(x0, y - 4 * dp), Offset(x0, y + 1 * dp), strokeWidth = 1.5f * dp)
    drawLine(p.ink2, Offset(x0 + len / 2, y - 2.5f * dp), Offset(x0 + len / 2, y), strokeWidth = 1f * dp)
    drawLine(p.ink2, Offset(x0 + len, y - 4 * dp), Offset(x0 + len, y + 1 * dp), strokeWidth = 1.5f * dp)
}

/**
 * The spot: an ink dot with the chart's magenta light flare beside it (the symbol nautical charts use for a
 * light), a faint range ring, and two sonar pings when [pingKey] changes.
 */
@Composable
fun PinLayer(
    at: Offset,
    flareLength: Dp,
    dotRadius: Dp,
    pingKey: Any?,
    label: String? = null,
    measurer: TextMeasurer? = null,
) {
    val p = LocalPalette.current
    val ping = remember { Animatable(1f) }
    LaunchedEffect(pingKey) {
        if (pingKey != null) {
            repeat(2) {
                ping.snapTo(0f)
                ping.animateTo(1f, tween(1500, easing = LinearOutSlowInEasing))
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val dp = 1.dp.toPx()
        val v = ping.value
        if (v < 1f) {
            drawCircle(p.flare.copy(alpha = (1f - v) * 0.8f), radius = (6 + 38 * v) * dp, center = at, style = Stroke(width = 1.5f * dp))
        }
        drawCircle(p.flare.copy(alpha = 0.3f), radius = 15 * dp, center = at, style = Stroke(width = 1f * dp))
        drawPath(flarePath(at, flareLength.toPx() / 19f), p.flare.copy(alpha = 0.93f))
        val r = dotRadius.toPx()
        drawCircle(p.chart, radius = r + 2 * dp, center = at)
        drawCircle(p.ink, radius = r, center = at)
        if (label != null && measurer != null) {
            val t = measurer.measure(label, Type.mapPlace.copy(color = p.ink))
            val tl = Offset(at.x + 11 * dp, at.y + 5 * dp)
            drawRoundRect(
                p.chart.copy(alpha = 0.82f),
                topLeft = Offset(tl.x - 4 * dp, tl.y - 1 * dp),
                size = androidx.compose.ui.geometry.Size(t.size.width + 8 * dp, t.size.height + 2 * dp),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4 * dp),
            )
            drawText(t, topLeft = tl)
        }
    }
}

/** The light flare: a teardrop from the spot up and to the right (same shape as Glyph.Flare). */
fun flarePath(tip: Offset, k: Float): Path = Path().apply {
    moveTo(tip.x, tip.y)
    lineTo(tip.x + 5.44f * k, tip.y - 12.06f * k)
    arcTo(Rect(Offset(tip.x + 10f * k, tip.y - 10f * k), 5f * k), 204.3f, 221.4f, false)
    close()
}
