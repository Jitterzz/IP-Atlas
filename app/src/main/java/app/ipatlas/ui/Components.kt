package app.ipatlas.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ipatlas.Tab
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A band of page color behind the status bar, so text scrolling up doesn't run into the clock and icons.
 * [alpha] is read while drawing, so it can follow a scroll position without recomposing.
 */
@Composable
fun StatusScrim(alpha: () -> Float = { 0.96f }) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .graphicsLayer { this.alpha = alpha() }
            .background(p.bg),
    )
}

/** A one-pixel rule. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalPalette.current.rule) {
    val px = with(LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxWidth().height(px).background(color))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = Type.section, color = p.ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = Type.chip,
                color = p.depth,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** Label on the left, value on the right. Tapping copies the value (or does [onClick]). Hidden when [value] is null. */
@Composable
fun InfoRow(
    label: String,
    value: String?,
    mono: Boolean = false,
    valueColor: Color? = null,
    trailing: Glyph? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    if (value.isNullOrBlank()) return
    val p = LocalPalette.current
    val copy = rememberCopier()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (onClick == null) "Copy" else null) { if (onClick != null) onClick() else copy(label, value) }
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(label, style = Type.label, color = p.ink2, modifier = Modifier.width(128.dp).padding(end = 12.dp))
            Text(
                value,
                style = if (mono) Type.valueMono else Type.value,
                color = valueColor ?: p.ink,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                LineIcon(trailing, p.ink3, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
            }
        }
        if (divider) Hairline()
    }
}

/** A row whose value is still on its way. */
@Composable
fun LoadingRow(label: String, fraction: Float = 0.42f, divider: Boolean = true) {
    val p = LocalPalette.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.label, color = p.ink2, modifier = Modifier.width(128.dp).padding(end = 12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Shimmer(Modifier.fillMaxWidth(fraction)) }
        }
        if (divider) Hairline()
    }
}

@Composable
fun Shimmer(modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.45f, 0.95f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "a")
    Box(modifier.height(height).clip(RoundedCornerShape(4.dp)).background(p.rule.copy(alpha = a)))
}

/** A small rounded tag. Flagged tags (VPN, proxy, Tor...) are drawn in chart magenta. */
@Composable
fun Tag(text: String, glyph: Glyph? = null, flagged: Boolean = false, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    val color = if (flagged) p.flare else p.ink
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, if (flagged) p.flare.copy(alpha = 0.7f) else p.rule, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            LineIcon(glyph, color, size = 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.chip, color = color)
    }
}

/** A round icon button, for sitting on top of the charts. */
@Composable
fun RoundButton(
    glyph: Glyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalPalette.current.ink,
    background: Color = LocalPalette.current.chart.copy(alpha = 0.85f),
    size: Dp = 42.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(glyph, tint, size = iconSize)
    }
}

/** A note across the page: what went wrong or what was swapped in, and what to do about it. */
@Composable
fun Banner(text: String, glyph: Glyph = Glyph.Info, flagged: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.raised)
            .border(1.dp, if (flagged) p.flare.copy(alpha = 0.55f) else p.rule, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LineIcon(glyph, if (flagged) p.flare else p.ink2, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.label, color = p.ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                action,
                style = Type.chip,
                color = p.depth,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Yes (magenta diamond), no (open ring) or unknown (dash) for one privacy check. */
@Composable
fun CheckMark(flag: Boolean?) {
    val p = LocalPalette.current
    when (flag) {
        true -> LineIcon(Glyph.Diamond, p.flare, size = 16.dp)
        false -> LineIcon(Glyph.Ring, p.ink3, size = 16.dp)
        null -> LineIcon(Glyph.Minus, p.ink3, size = 16.dp)
    }
}

/**
 * The search box: an address, domain or link goes in; "Look up" (or the keyboard's search key) runs it.
 * [hasError] outlines it in magenta; the caller shows the message.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPaste: () -> Unit,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(shape)
                .background(p.raised)
                .border(1.dp, if (hasError) p.flare else p.rule, shape)
                .padding(start = 14.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(Glyph.Search, p.ink2)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text("IP address or domain", style = Type.body, color = p.ink3, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = Type.body.copy(color = p.ink),
                    cursorBrush = SolidColor(p.flare),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                RoundButton(Glyph.Close, "Clear", onClick = { onValueChange("") }, background = Color.Transparent, tint = p.ink2, size = 40.dp, iconSize = 20.dp)
            } else {
                RoundButton(Glyph.Paste, "Paste", onClick = onPaste, background = Color.Transparent, tint = p.ink2, size = 40.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.ink)
                    .clickable(role = Role.Button, onClick = onSubmit)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Look up", style = Type.bodyMedium, color = p.bg)
            }
        }
    }
}

@Composable
fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.bg)) {
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(62.dp)) {
            NavItem(tab == Tab.LOOKUP, "Look up", Glyph.Globe) { onTab(Tab.LOOKUP) }
            NavItem(tab == Tab.HISTORY, "History", Glyph.Clock) { onTab(Tab.HISTORY) }
            NavItem(tab == Tab.SETTINGS, "Settings", Glyph.Sliders) { onTab(Tab.SETTINGS) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(selected: Boolean, label: String, glyph: Glyph, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            LineIcon(glyph, if (selected) p.ink else p.ink3, size = 24.dp)
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-2).dp).size(6.dp).clip(CircleShape).background(p.flare))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, style = Type.nav, color = if (selected) p.ink else p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Short messages near the bottom of the screen, like "Address copied". */
class Toaster {
    var message by mutableStateOf<String?>(null)
        private set
    var stamp by mutableStateOf(0L)
        private set

    fun show(text: String) {
        message = text
        stamp = System.nanoTime()
    }

    fun hide() {
        message = null
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    LaunchedEffect(toaster.stamp) {
        if (toaster.message != null) {
            delay(1800)
            toaster.hide()
        }
    }
    AnimatedVisibility(visible = toaster.message != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            toaster.message ?: "",
            style = Type.chip,
            color = p.bg,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(p.ink).padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** Copies text to the clipboard. Android 13+ shows its own confirmation, so ours only shows on older phones. */
@Composable
fun rememberCopier(): (String, String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val toaster = LocalToaster.current
    return remember(clipboard, haptics, toaster) {
        { label: String, value: String ->
            clipboard.setText(AnnotatedString(value))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (Build.VERSION.SDK_INT < 33) toaster.show("$label copied")
        }
    }
}

/** Opens another app (maps, email, the share sheet...). Shows a note instead of crashing if there isn't one. */
fun Context.tryStart(intent: Intent, toaster: Toaster, missing: String) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toaster.show(missing)
    }
}

fun Context.share(text: String, toaster: Toaster) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    tryStart(Intent.createChooser(send, null), toaster, "No app to share with")
}

fun Context.openMap(lat: Double, lon: Double, label: String, toaster: Toaster) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
    tryStart(Intent(Intent.ACTION_VIEW, uri), toaster, "No maps app found")
}

fun Context.email(address: String, toaster: Toaster) =
    tryStart(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")), toaster, "No email app found")

fun Context.dial(number: String, toaster: Toaster) =
    tryStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })), toaster, "No phone app found")

fun Context.browse(url: String, toaster: Toaster) =
    tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)), toaster, "No browser found")

/** "Just now", "12 min ago", "3 h ago", "Yesterday", "Mar 4". */
fun timeAgo(at: Long, now: Long = System.currentTimeMillis()): String {
    val mins = (now - at) / 60_000
    return when {
        mins < 1 -> "Just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        mins < 48 * 60 -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(at))
    }
}
