package app.ipatlas.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ipatlas.AppModel
import app.ipatlas.Tab
import app.ipatlas.data.Geo
import app.ipatlas.data.HistoryEntry
import app.ipatlas.data.Load
import app.ipatlas.data.Transport

@Composable
fun HomeScreen(model: AppModel) {
    val p = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val copy = rememberCopier()
    val self = model.self
    val info = (self.info as? Load.Ready)?.value
    val device = model.device
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val chartHeight = statusTop + 292.dp

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // The world, with you on it. The search box sits across the chart's bottom edge.
        Box(Modifier.fillMaxWidth().height(chartHeight + 28.dp)) {
            WorldChart(
                modifier = Modifier.fillMaxWidth().height(chartHeight),
                pinLat = info?.latitude,
                pinLon = info?.longitude,
                top = statusTop + 50.dp,
                bottom = 92.dp,
                scanning = self.loading,
                pingKey = info?.ip,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = statusTop).padding(start = 20.dp, end = 12.dp).height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wordmark()
                Spacer(Modifier.weight(1f))
                RoundButton(Glyph.Refresh, "Check my connection again", onClick = { model.refreshSelf() }, size = 40.dp, iconSize = 20.dp)
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = chartHeight - 104.dp)) {
                Text("Your public address", style = Type.small, color = p.ink2)
                val mine = self.primary
                when {
                    mine != null -> Text(
                        mine,
                        style = if (mine.contains(':')) Type.place.copy(fontFamily = Type.plex) else Type.ipHero,
                        color = p.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClickLabel = "Copy") { copy("Address", mine) },
                    )
                    self.loading -> Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) { Shimmer(Modifier.width(190.dp), height = 20.dp) }
                    else -> Text("Not found", style = Type.ipHero, color = p.ink3)
                }
                Text(
                    info?.let { listOfNotNull(it.provider, it.shortPlace.ifEmpty { null }).joinToString(", ") } ?: " ",
                    style = Type.label,
                    color = p.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SearchField(
                value = model.search,
                onValueChange = {
                    model.search = it
                    model.searchError = null
                },
                onSubmit = {
                    focus.clearFocus()
                    model.submit()
                },
                onPaste = {
                    clipboard.getText()?.text?.let { pasted ->
                        model.search = app.ipatlas.data.Queries.findIn(pasted) ?: pasted.trim().take(200)
                        model.searchError = null
                    }
                },
                hasError = model.searchError != null,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp),
            )
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            model.searchError?.let {
                Text(it, style = Type.small, color = p.flare, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
            }

            // What kind of connection this is, at a glance.
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tag(if (device.vpn) "VPN on" else "VPN off", Glyph.Lock, flagged = device.vpn)
                Tag(
                    if (device.vpn && device.underlying != null) "${device.underlying.label} under VPN" else device.transport.label,
                    transportGlyph(if (device.vpn) device.underlying ?: Transport.VPN else device.transport),
                )
                when (self.v6) {
                    is Load.Ready -> Tag("IPv6 ready", Glyph.Globe)
                    is Load.Failed -> Tag("IPv4 only", Glyph.Globe)
                    else -> Unit
                }
            }

            SectionTitle("Your connection", action = if (self.primary != null) "Full details" else null, onAction = { model.openMine() })
            when (val load = self.info) {
                is Load.Failed -> {
                    Spacer(Modifier.height(6.dp))
                    Banner(load.message, Glyph.Warning, action = "Try again", onAction = { model.refreshSelf() })
                }
                else -> {
                    val v4 = (self.v4 as? Load.Ready)?.value
                    val v6 = (self.v6 as? Load.Ready)?.value
                    if (self.v4 == Load.Loading) LoadingRow("Public IPv4") else InfoRow("Public IPv4", v4 ?: "None", mono = v4 != null)
                    if (self.v6 == Load.Loading) LoadingRow("Public IPv6", 0.7f) else InfoRow("Public IPv6", v6 ?: "None", mono = v6 != null)
                    if (load == Load.Loading) {
                        LoadingRow("Provider", 0.5f)
                        LoadingRow("Location", 0.6f)
                        LoadingRow("Network", 0.3f, divider = false)
                    } else if (info != null) {
                        InfoRow("Provider", info.provider)
                        InfoRow("Location", (Geo.flag(info.countryCode) + " " + info.shortPlace).trim().ifEmpty { null })
                        InfoRow("Network", info.asLabel?.let { a -> listOfNotNull(a, info.org ?: info.asnName).joinToString("  ") })
                        InfoRow("Time zone", info.timeZone, divider = false)
                    }
                }
            }

            SectionTitle("This phone")
            if (!device.connected) {
                Text("Not connected to a network.", style = Type.label, color = p.ink2, modifier = Modifier.padding(vertical = 10.dp))
            } else {
                InfoRow(
                    "Connection",
                    if (device.vpn) "VPN" + (device.underlying?.let { " through ${it.label}" } ?: "") else device.transport.label,
                )
                InfoRow("Local address", device.localV4.firstOrNull(), mono = true)
                InfoRow("Local IPv6", device.localV6.firstOrNull(), mono = true)
                InfoRow("Gateway", device.gateway, mono = true)
                InfoRow("DNS servers", device.dns.joinToString("\n").ifEmpty { null }, mono = true)
                InfoRow("Private DNS", device.privateDns)
                InfoRow("Interface", device.iface?.let { i -> i + (device.mtu?.let { ", MTU $it" } ?: "") })
                InfoRow("Metered", device.metered?.let { if (it) "Yes" else "No" }, divider = false)
            }

            SectionTitle("Recent", action = if (model.store.history.isNotEmpty()) "History" else null, onAction = { model.tab = Tab.HISTORY })
            val recent = model.store.history.take(4)
            if (recent.isEmpty()) {
                Text(
                    "Addresses and domains you look up show up here. You can also select an address in any app and pick Look up IP.",
                    style = Type.label,
                    color = p.ink2,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else {
                recent.forEachIndexed { i, e -> HistoryRow(e, onOpen = { model.openEntry(e) }, divider = i < recent.lastIndex) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun Wordmark() {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        LineIcon(Glyph.Flare, p.flare, size = 20.dp)
        Spacer(Modifier.width(7.dp))
        Text("IP Atlas", style = Type.wordmark, color = p.ink)
    }
}

fun transportGlyph(t: Transport): Glyph = when (t) {
    Transport.WIFI -> Glyph.Wifi
    Transport.CELLULAR -> Glyph.Cellular
    Transport.ETHERNET -> Glyph.Ethernet
    Transport.VPN -> Glyph.Lock
    else -> Glyph.Globe
}

/** One past lookup: flag, what was looked up, where it is, when. */
@Composable
fun HistoryRow(
    entry: HistoryEntry,
    onOpen: () -> Unit,
    divider: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .historyClick(onOpen, onLongPress)
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val flag = Geo.flag(entry.countryCode)
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (flag.isNotEmpty()) Text(flag, style = Type.body) else LineIcon(Glyph.Target, p.ink3, size = 20.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(entry.query, style = Type.bodyMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(entry.place.ifEmpty { null }, entry.ip.takeIf { it.isNotEmpty() && it != entry.query }).joinToString(", ")
                if (detail.isNotEmpty()) Text(detail, style = Type.small, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(timeAgo(entry.at), style = Type.small, color = p.ink3)
            if (trailing != null) {
                Spacer(Modifier.width(2.dp))
                trailing()
            }
        }
        if (divider) Hairline()
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.historyClick(onOpen: () -> Unit, onLongPress: (() -> Unit)?): Modifier =
    if (onLongPress == null) {
        clickable(role = Role.Button, onClick = onOpen)
    } else {
        combinedClickable(onClick = onOpen, onLongClick = onLongPress, onLongClickLabel = "More")
    }
