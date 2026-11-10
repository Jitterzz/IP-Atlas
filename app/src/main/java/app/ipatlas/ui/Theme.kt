package app.ipatlas.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.ipatlas.R
import app.ipatlas.data.ThemeMode

/**
 * The colors of a nautical chart: deep water, buff land, and chart magenta for the one thing that matters
 * (where the address is, and anything flagged). "Night chart" is the dark theme, "Day chart" the light one.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val chart: Color,
    val raised: Color,
    val rule: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val land: Color,
    val border: Color,
    val graticule: Color,
    val flare: Color,
    val depth: Color,
)

val NightChart = Palette(
    dark = true,
    bg = Color(0xFF0B1A2A),
    chart = Color(0xFF0F2438),
    raised = Color(0xFF15304A),
    rule = Color(0xFF213E5A),
    ink = Color(0xFFE6EDF2),
    ink2 = Color(0xFF9FB2C4),
    ink3 = Color(0xFF6B8197),
    land = Color(0xFFCDBB86),
    border = Color(0xFF9AAABB),
    graticule = Color(0xFF26476A),
    flare = Color(0xFFF0559E),
    depth = Color(0xFF6CB0E0),
)

val DayChart = Palette(
    dark = false,
    bg = Color(0xFFF5F8FA),
    chart = Color(0xFFDCEAF3),
    raised = Color(0xFFFFFFFF),
    rule = Color(0xFFD5E0E8),
    ink = Color(0xFF0F2740),
    ink2 = Color(0xFF4B6177),
    ink3 = Color(0xFF7D90A3),
    land = Color(0xFF9C8240),
    border = Color(0xFF7D7158),
    graticule = Color(0xFFB7CDDC),
    flare = Color(0xFFC0177F),
    depth = Color(0xFF1F6FA8),
)

val LocalPalette = staticCompositionLocalOf { NightChart }

/** Barlow Semi Condensed (drawn from California's road signs and plates) for words, IBM Plex Mono for addresses. */
object Type {
    val barlow = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
        Font(R.font.barlow_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val plex = FontFamily(
        Font(R.font.plexmono_regular, FontWeight.Normal),
        Font(R.font.plexmono_medium, FontWeight.Medium),
    )

    val wordmark = TextStyle(fontFamily = barlow, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp)
    val screenTitle = TextStyle(fontFamily = barlow, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp)
    val ipLarge = TextStyle(fontFamily = plex, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp, fontFeatureSettings = "zero")
    val ipHero = TextStyle(fontFamily = plex, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp, fontFeatureSettings = "zero")
    val place = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 25.sp)
    val section = TextStyle(fontFamily = barlow, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val bodyMedium = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val label = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val value = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum")
    val valueMono = TextStyle(fontFamily = plex, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "zero")
    val small = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp)
    val chip = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    val nav = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)
    val mapLabel = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 14.sp)
    val mapPlace = TextStyle(fontFamily = barlow, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 17.sp)
    val mapGrid = TextStyle(fontFamily = barlow, fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic, fontSize = 11.sp, lineHeight = 13.sp, fontFeatureSettings = "tnum")
}

@Composable
fun AtlasTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
    }
    val p = if (dark) NightChart else DayChart
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.flare, onPrimary = Color.White, secondary = p.depth, background = p.bg, onBackground = p.ink,
            surface = p.raised, onSurface = p.ink, surfaceVariant = p.chart, onSurfaceVariant = p.ink2, outline = p.rule,
            surfaceContainerHigh = p.raised, surfaceContainer = p.raised,
        )
    } else {
        lightColorScheme(
            primary = p.flare, onPrimary = Color.White, secondary = p.depth, background = p.bg, onBackground = p.ink,
            surface = p.raised, onSurface = p.ink, surfaceVariant = p.chart, onSurfaceVariant = p.ink2, outline = p.rule,
            surfaceContainerHigh = p.raised, surfaceContainer = p.raised,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
