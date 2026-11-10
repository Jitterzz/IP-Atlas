package app.ipatlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's own line icons, drawn on a 24 × 24 grid with a 1.75 stroke and round ends. (tools/preview.py draws
 * the same paths for the screenshot previews.)
 */
enum class Glyph(val d: String, val filled: Boolean = false) {
    Search("M16.5 10.5a6 6 0 1 1-12 0a6 6 0 1 1 12 0M15 15l5 5"),
    Paste("M9 4.5H7.5A2.5 2.5 0 0 0 5 7v11.5A2.5 2.5 0 0 0 7.5 21h9a2.5 2.5 0 0 0 2.5-2.5V7a2.5 2.5 0 0 0-2.5-2.5H15M9.5 3h5a1 1 0 0 1 1 1v1a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"),
    Close("M6.5 6.5l11 11M17.5 6.5l-11 11"),
    Back("M19 12H5M11 6l-6 6 6 6"),
    Star("M12 3.5l2.6 5.4 5.9 0.7-4.4 4.1 1.2 5.9L12 16.7l-5.3 2.9 1.2-5.9-4.4-4.1 5.9-0.7z"),
    StarFilled("M12 3.5l2.6 5.4 5.9 0.7-4.4 4.1 1.2 5.9L12 16.7l-5.3 2.9 1.2-5.9-4.4-4.1 5.9-0.7z", filled = true),
    Share("M12 14.5V3.5M7.5 8L12 3.5 16.5 8M8 10.5H6.5A1.5 1.5 0 0 0 5 12v6.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V12a1.5 1.5 0 0 0-1.5-1.5H16"),
    Copy("M9 9.5A1.5 1.5 0 0 1 10.5 8h8A1.5 1.5 0 0 1 20 9.5v9a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 9 18.5zM15 5.5V5a1.5 1.5 0 0 0-1.5-1.5h-8A1.5 1.5 0 0 0 4 5v9a1.5 1.5 0 0 0 1.5 1.5H6"),
    Globe("M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0M12 3.5c-4.4 4.7-4.4 12.3 0 17M12 3.5c4.4 4.7 4.4 12.3 0 17M3.5 12h17"),
    Clock("M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0M12 7.5V12l3 2"),
    Sliders("M4 7h9M17 7h3M4 12h3M11 12h9M4 17h11M19 17h1M17 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0M11 12a2 2 0 1 1-4 0a2 2 0 1 1 4 0M19 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0"),
    Wifi("M2.8 9.2a13 13 0 0 1 18.4 0M5.8 12.3a8.8 8.8 0 0 1 12.4 0M8.9 15.4a4.4 4.4 0 0 1 6.2 0M12.9 18.8a0.9 0.9 0 1 1-1.8 0a0.9 0.9 0 1 1 1.8 0"),
    Cellular("M5 19v-3M9.5 19v-6M14 19v-9M18.5 19V6"),
    Ethernet("M7 4v4M17 4v4M5 8h14v3a7 7 0 0 1-14 0zM12 18v3"),
    Lock("M7.5 10.5V8a4.5 4.5 0 0 1 9 0v2.5M6.5 10.5h11A1.5 1.5 0 0 1 19 12v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19v-7a1.5 1.5 0 0 1 1.5-1.5zM12 14.5v2.5"),
    Refresh("M20 12a8 8 0 1 1-2.34-5.66L20 8.5M20 4v4.5h-4.5"),
    Plus("M12 5.5v13M5.5 12h13"),
    Minus("M5.5 12h13"),
    External("M14 4h6v6M20 4l-8.5 8.5M18 13.5v5A1.5 1.5 0 0 1 16.5 20h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6h5"),
    Mail("M4.5 6h15A1.5 1.5 0 0 1 21 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 16.5v-9A1.5 1.5 0 0 1 4.5 6zM3.5 7l8.5 6.5L20.5 7"),
    Phone("M8 3h8a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 16 21H8a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 8 3zM11 18h2"),
    Chevron("M9.5 6l6 6-6 6"),
    More("M13 5.5a1 1 0 1 1-2 0a1 1 0 1 1 2 0M13 12a1 1 0 1 1-2 0a1 1 0 1 1 2 0M13 18.5a1 1 0 1 1-2 0a1 1 0 1 1 2 0"),
    Trash("M4.5 7h15M10 7V5h4v2M6.5 7l1 12.5A1.5 1.5 0 0 0 9 21h6a1.5 1.5 0 0 0 1.5-1.5l1-12.5M10 11v6M14 11v6"),
    Check("M5 12.5l4.5 4.5L19 7.5"),
    Warning("M12 4l9 15.5H3zM12 10v4M12 17h.01"),
    Flare("M5 19L10.44 6.94A5 5 0 1 1 17.06 13.56Z", filled = true),
    Shield("M12 3.5l7 2.6v5.4c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6.1z"),
    Server("M5.5 4h13A1.5 1.5 0 0 1 20 5.5v3A1.5 1.5 0 0 1 18.5 10h-13A1.5 1.5 0 0 1 4 8.5v-3A1.5 1.5 0 0 1 5.5 4zM5.5 14h13a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5v-3A1.5 1.5 0 0 1 5.5 14zM7.5 7h.01M7.5 17h.01"),
    Pin("M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11zM14.5 10a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0"),
    Info("M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0M12 11v5.5M12 7.8h.01"),
    Diamond("M12 6.5l5.5 5.5-5.5 5.5L6.5 12z", filled = true),
    Ring("M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0"),
    Link("M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1"),
    Compare("M7 7h12M15.5 3.5L19 7l-3.5 3.5M17 17H5M8.5 13.5L5 17l3.5 3.5"),
    Target("M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M18 12a6 6 0 1 1-12 0a6 6 0 1 1 12 0M14 12a2 2 0 1 1-4 0a2 2 0 1 1 4 0"),
}

@Composable
fun LineIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, stroke: Float = 1.75f) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.d).toPath() }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            if (glyph.filled) {
                drawPath(path, tint)
            } else {
                drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
