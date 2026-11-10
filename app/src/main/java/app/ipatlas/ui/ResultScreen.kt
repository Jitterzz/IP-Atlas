package app.ipatlas.ui

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ipatlas.AppModel
import app.ipatlas.Compared
import app.ipatlas.Phase
import app.ipatlas.ResultState
import app.ipatlas.data.Geo
import app.ipatlas.data.IpAddress
import app.ipatlas.data.IpInfo
import app.ipatlas.data.IpKind
import app.ipatlas.data.Load
import app.ipatlas.data.Source
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ResultScreen(model: AppModel, r: ResultState) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val copy = rememberCopier()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val info = r.info
    val ip = r.selected
    var zoom by rememberSaveable(r.id, info?.ip) { mutableIntStateOf(defaultZoom(info)) }
    val scroll = rememberScrollState()
    // The top bar turns solid (and shows what was looked up) once the page scrolls up under it.
    val density = LocalDensity.current
    val fadeStart = with(density) { (286.dp - 58.dp - 24.dp).toPx() }
    val fadeLength = with(density) { 24.dp.toPx() }
    val showTitle by remember(fadeStart, fadeLength) { derivedStateOf { scroll.value > fadeStart + fadeLength } }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(statusTop + 286.dp)) {
                Crossfade(targetState = info?.takeIf { it.hasPoint }, label = "chart") { located ->
                    if (located?.latitude != null && located.longitude != null) {
                        RegionChart(
                            lat = located.latitude,
                            lon = located.longitude,
                            kmAcross = ZoomSteps[zoom],
                            label = located.city ?: located.region ?: located.country,
                            modifier = Modifier.fillMaxSize(),
                            top = statusTop + 58.dp,
                            bottom = 46.dp,
                            pingKey = located.ip,
                        )
                    } else {
                        WorldChart(
                            modifier = Modifier.fillMaxSize(),
                            top = statusTop + 62.dp,
                            bottom = 40.dp,
                            scanning = r.phase == Phase.RESOLVING || r.phase == Phase.LOCATING,
                        )
                    }
                }
                if (info?.hasPoint == true) {
                    Row(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 6.dp)) {
                        RoundButton(Glyph.Minus, "Zoom out", onClick = { if (zoom < ZoomSteps.lastIndex) zoom++ }, size = 38.dp, iconSize = 20.dp, background = p.raised)
                        Spacer(Modifier.width(8.dp))
                        RoundButton(Glyph.Plus, "Zoom in", onClick = { if (zoom > 0) zoom-- }, size = 38.dp, iconSize = 20.dp, background = p.raised)
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                Headline(r, onCopy = { copy("Address", it) })

                if (r.phase == Phase.FAILED && r.error != null) {
                    Spacer(Modifier.height(14.dp))
                    Banner(r.error ?: "", Glyph.Warning, flagged = true, action = "Try again", onAction = { model.retry(r) })
                }
                if (info != null && r.notes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Banner(r.notes.joinToString(". ") + ". Showing ${info.source.label} instead.", Glyph.Info)
                }
                if (r.phase == Phase.LOCAL && ip != null) {
                    Spacer(Modifier.height(14.dp))
                    Banner(
                        ip.kind.detail + " Addresses like this only mean something inside one network, so there's nothing to look up online.",
                        Glyph.Info,
                    )
                }

                if (r.host != null) DomainSection(model, r)

                if (info != null) {
                    NetworkSection(r, info)
                    LocationSection(info, onMap = { lat, lon -> context.openMap(lat, lon, info.place.ifEmpty { info.ip }, toaster) })
                    info.timeZone?.let { TimeSection(it) }
                    PrivacySection(info, hasKey = model.store.settings.ipapiKey.isNotBlank())
                    AbuseSection(r, info, onEmail = { context.email(it, toaster) }, onCall = { context.dial(it, toaster) })
                } else if (r.phase == Phase.LOCATING) {
                    SectionTitle("Network")
                    LoadingRow("Provider", 0.5f)
                    LoadingRow("ASN", 0.3f)
                    LoadingRow("Hostname", 0.6f, divider = false)
                    SectionTitle("Location")
                    LoadingRow("City", 0.4f)
                    LoadingRow("Country", 0.5f)
                    LoadingRow("Coordinates", 0.6f, divider = false)
                }
                if (ip != null && ip.lookupTarget != null && r.phase != Phase.RESOLVING) RegistrationSection(r)
                if (ip != null) AddressSection(ip)
                if (info != null) CompareSection(model, r, info)
                if (info != null) Sources(r, info)
                Spacer(Modifier.height(36.dp + navBottom))
            }
        }

        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = ((scroll.value - fadeStart) / fadeLength).coerceIn(0f, 1f) }
                    .background(p.bg),
            ) {
                Spacer(Modifier.weight(1f))
                Hairline()
            }
            Row(
                Modifier.fillMaxWidth().padding(top = statusTop).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Glyph.Back, "Back", onClick = { model.back() })
                if (showTitle) {
                    Text(
                        r.key ?: r.input,
                        style = Type.bodyMedium,
                        color = p.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (r.key != null) {
                    val starred = model.isStarred(r)
                    RoundButton(
                        if (starred) Glyph.StarFilled else Glyph.Star,
                        if (starred) "Remove star" else "Star",
                        onClick = { model.toggleStar(r) },
                        tint = if (starred) p.flare else p.ink,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                RoundButton(Glyph.Share, "Share", onClick = { context.share(model.shareText(r), toaster) })
            }
        }
    }
}

/** Start close in when the city is known, further out when only the region or country is. */
private fun defaultZoom(info: IpInfo?): Int = when {
    info == null -> 1
    info.city != null -> 1
    info.region != null -> 2
    else -> 3
}

@Composable
private fun Headline(r: ResultState, onCopy: (String) -> Unit) {
    val p = LocalPalette.current
    val ip = r.selected
    val info = r.info
    Column(Modifier.padding(top = 6.dp)) {
        if (r.host != null) {
            Text(r.host, style = Type.place, color = p.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = ip?.toString() ?: when {
                r.phase == Phase.RESOLVING -> "Finding addresses…"
                r.host != null -> "No address found"
                else -> r.input
            }
            Text(
                text,
                style = if (ip == null || ip.version == 6 || text.length > 15) Type.ipLarge.copy(fontSize = Type.place.fontSize, lineHeight = Type.place.lineHeight) else Type.ipLarge,
                color = if (ip == null) p.ink2 else p.ink,
                modifier = Modifier.weight(1f).then(if (ip != null) Modifier.clickable(onClickLabel = "Copy") { onCopy(ip.toString()) } else Modifier),
            )
            if (ip != null) RoundButton(Glyph.Copy, "Copy address", onClick = { onCopy(ip.toString()) }, background = Color.Transparent, tint = p.ink2)
        }
        when {
            info != null -> {
                val place = info.place.ifEmpty { "Location unknown" }
                Text((Geo.flag(info.countryCode) + "  " + place).trim(), style = Type.place, color = p.ink, modifier = Modifier.padding(top = 2.dp))
            }
            r.phase == Phase.LOCATING -> Shimmer(Modifier.padding(top = 10.dp).width(220.dp), height = 16.dp)
            r.phase == Phase.LOCAL && ip != null -> Text("${ip.kind.label} address", style = Type.place, color = p.ink, modifier = Modifier.padding(top = 2.dp))
            else -> Unit
        }
        if (ip != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tag("IPv${ip.version}")
                Tag(ip.kind.label)
                info?.networkType?.let { Tag(it, Glyph.Server) }
                if (info?.isMobile == true) Tag("Mobile network", Glyph.Cellular)
                info?.flagged?.forEach { Tag(it, Glyph.Diamond, flagged = true) }
            }
        }
    }
}

@Composable
private fun DomainSection(model: AppModel, r: ResultState) {
    val p = LocalPalette.current
    val res = r.resolved
    SectionTitle("Domain")
    if (res == null) {
        if (r.phase == Phase.RESOLVING) LoadingRow("Addresses", 0.5f, divider = false)
        return
    }
    InfoRow("Name", res.host)
    if (res.aliases.isNotEmpty()) InfoRow("Alias of", res.aliases.joinToString("\n"))
    if (res.addresses.isNotEmpty()) {
        Text(
            if (res.addresses.size == 1) "Points to one address" else "Points to ${res.addresses.size} addresses. Tap one to look it up.",
            style = Type.label,
            color = p.ink2,
            modifier = Modifier.padding(top = 11.dp, bottom = 4.dp),
        )
        for (a in res.addresses) {
            val showing = a == r.selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, enabled = !showing) { model.select(r, a) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LineIcon(if (showing) Glyph.Diamond else Glyph.Ring, if (showing) p.flare else p.ink3, size = 14.dp)
                Spacer(Modifier.width(10.dp))
                Text(a.toString(), style = Type.valueMono, color = if (showing) p.ink else p.ink2, modifier = Modifier.weight(1f))
                Text(if (showing) "Showing" else "IPv${a.version}", style = Type.small, color = if (showing) p.flare else p.ink3)
            }
        }
        Hairline()
    }
    when (val rec = r.records) {
        is Load.Loading -> LoadingRow("Mail servers", 0.5f)
        is Load.Ready -> {
            val labels = mapOf("MX" to "Mail servers", "NS" to "Name servers", "TXT" to "Text records", "CAA" to "Certificate rules")
            for ((type, list) in rec.value) {
                InfoRow(labels[type] ?: type, list.joinToString("\n") { it.data }, mono = type != "TXT")
            }
        }
        is Load.Failed -> Text(rec.message, style = Type.small, color = p.ink3, modifier = Modifier.padding(vertical = 8.dp))
        else -> Unit
    }
    InfoRow("Answered by", res.via, divider = false)
}

@Composable
private fun NetworkSection(r: ResultState, info: IpInfo) {
    val p = LocalPalette.current
    SectionTitle("Network")
    InfoRow("Provider", info.isp ?: info.org)
    if (info.org != null && info.org != info.isp) InfoRow("Organization", info.org)
    InfoRow("ASN", info.asLabel)
    InfoRow("AS name", info.asnName?.takeIf { it != info.org && it != info.isp })
    InfoRow("Route", info.route, mono = true)
    InfoRow("Range", info.network?.takeIf { it != info.route }, mono = true)
    InfoRow("Net name", info.netName)
    when (val ptr = r.ptr) {
        is Load.Loading -> LoadingRow("Hostname", 0.6f)
        is Load.Ready -> InfoRow("Hostname", ptr.value ?: "None set", mono = ptr.value != null, valueColor = if (ptr.value == null) p.ink3 else null)
        is Load.Failed -> InfoRow("Hostname", "Couldn't check", valueColor = p.ink3)
        else -> Unit
    }
    InfoRow("Domain", info.domain ?: info.asnDomain)
    InfoRow("Type", info.networkType)
    InfoRow("Data center", info.datacenter)
    InfoRow("Registry", info.rir, divider = false)
}

@Composable
private fun LocationSection(info: IpInfo, onMap: (Double, Double) -> Unit) {
    SectionTitle("Location")
    InfoRow("City", info.city)
    InfoRow("Region", info.region)
    InfoRow("Postal code", info.postal)
    InfoRow("Country", info.country?.let { c -> (Geo.flag(info.countryCode) + " " + c + (info.countryCode?.let { " ($it)" } ?: "")).trim() })
    InfoRow("Continent", info.continent)
    if (info.latitude != null && info.longitude != null) {
        InfoRow("Coordinates", Geo.decimal(info.latitude, info.longitude), trailing = Glyph.External, onClick = { onMap(info.latitude, info.longitude) })
        InfoRow("In degrees", Geo.dms(info.latitude, info.longitude))
    }
    InfoRow("Accuracy", info.accuracy)
    InfoRow("Capital", info.capital)
    InfoRow("Calling code", info.callingCode)
    InfoRow("Currency", info.currency)
    InfoRow("Languages", info.languages)
    InfoRow("In the EU", if (info.isEu == true) "Yes" else null, divider = false)
    if (info.placeFromMap) {
        Text(
            "The services gave only the spot, so the town and state are named from IP Atlas's own map.",
            style = Type.small,
            color = LocalPalette.current.ink3,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TimeSection(zoneId: String) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val zone = remember(zoneId) { runCatching { ZoneId.of(zoneId) }.getOrNull() }
    SectionTitle("Time")
    InfoRow("Time zone", zoneId)
    if (zone == null) return
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000L - System.currentTimeMillis() % 1000L)
            value = System.currentTimeMillis()
        }
    }
    val instant = Instant.ofEpochMilli(now)
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm:ss, EEE MMM d" else "h:mm:ss a, EEE MMM d"
    val local = instant.atZone(zone).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    val offset = zone.rules.getOffset(instant)
    val mine = ZoneId.systemDefault().rules.getOffset(instant)
    val diffMin = (offset.totalSeconds - mine.totalSeconds) / 60
    InfoRow("Local time", local)
    InfoRow("UTC offset", "UTC" + offset.id.replace("Z", "+00:00").replace("-", "−"))
    InfoRow("Daylight saving", if (zone.rules.isDaylightSavings(instant)) "In effect" else "Not in effect")
    InfoRow(
        "Compared to you",
        when {
            diffMin == 0 -> "Same time as you"
            else -> {
                val h = abs(diffMin) / 60
                val m = abs(diffMin) % 60
                val amount = if (m == 0) "$h h" else "$h h $m min"
                "$amount " + if (diffMin > 0) "ahead" else "behind"
            }
        },
        valueColor = p.ink,
        divider = false,
    )
}

@Composable
private fun PrivacySection(info: IpInfo, hasKey: Boolean) {
    val p = LocalPalette.current
    val checks = listOf(
        "VPN" to info.isVpn,
        "Proxy" to info.isProxy,
        "Tor exit" to info.isTor,
        "Hosting" to info.isHosting,
        "Known abuse" to info.isAbuser,
        "Mobile network" to info.isMobile,
        "Crawler or bot" to info.isCrawler,
        "Satellite" to info.isSatellite,
    ).filter { it.second != null }
    SectionTitle("Privacy checks")
    if (checks.isEmpty()) {
        Text(
            if (hasKey) {
                "ipapi.is didn't answer this time, so there are no VPN, proxy or Tor checks. Try again later."
            } else {
                "VPN, proxy and Tor checks come from ipapi.is with a free key. Add one in Settings to see them here."
            },
            style = Type.label,
            color = p.ink2,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        return
    }
    checks.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
            for ((label, flag) in pair) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    CheckMark(flag)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = Type.label, color = if (flag == true) p.flare else p.ink, modifier = Modifier.weight(1f))
                    Text(if (flag == true) "Yes" else "No", style = Type.value, color = if (flag == true) p.flare else p.ink3, modifier = Modifier.padding(end = 14.dp))
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Hairline()
    }
    InfoRow("VPN service", info.vpnService)
    InfoRow("Crawler", info.crawlerName)
    InfoRow("Abuse score", info.abuseScore)
    InfoRow("ASN abuse score", info.asnAbuseScore, divider = false)
}

@Composable
private fun AbuseSection(r: ResultState, info: IpInfo, onEmail: (String) -> Unit, onCall: (String) -> Unit) {
    val email = info.abuseEmail ?: (r.rdap as? Load.Ready)?.value?.abuseEmail
    if (email == null && info.abuseName == null && info.abusePhone == null) return
    SectionTitle("Abuse contact")
    InfoRow("Name", info.abuseName)
    if (email != null) InfoRow("Email", email, trailing = Glyph.Mail, onClick = { onEmail(email) })
    info.abusePhone?.let { phone -> InfoRow("Phone", phone, trailing = Glyph.Phone, onClick = { onCall(phone) }) }
    InfoRow("Address", info.abuseAddress, divider = false)
}

@Composable
private fun RegistrationSection(r: ResultState) {
    val p = LocalPalette.current
    SectionTitle("Registration")
    when (val rd = r.rdap) {
        is Load.Loading -> {
            LoadingRow("Network name", 0.4f)
            LoadingRow("Range", 0.7f)
            LoadingRow("Registered", 0.35f, divider = false)
        }
        is Load.Ready -> {
            val v = rd.value
            InfoRow("Network name", v.name)
            InfoRow("Organization", v.org)
            InfoRow("Range", v.range, mono = true)
            InfoRow("Block", v.cidr, mono = true)
            InfoRow("Type", v.type)
            InfoRow("Country", v.country)
            InfoRow("Registered", v.registered)
            InfoRow("Last changed", v.updated)
            InfoRow("Registry", v.registry)
            InfoRow("Status", v.status)
            InfoRow("Handle", v.handle, mono = true, divider = false)
        }
        is Load.Failed -> Text(rd.message, style = Type.label, color = p.ink2, modifier = Modifier.padding(vertical = 10.dp))
        else -> Unit
    }
}

@Composable
private fun AddressSection(ip: IpAddress) {
    val p = LocalPalette.current
    SectionTitle("Address details")
    InfoRow("Version", "IPv${ip.version}")
    InfoRow("Kind", ip.kind.label)
    if (ip.version == 4) {
        InfoRow("Decimal", ip.decimal, mono = true)
        InfoRow("Hex", ip.hex, mono = true)
        InfoRow("Binary", ip.binary.replace(".", ".​"), mono = true)
        InfoRow("Old class", ip.legacyClass)
        InfoRow("As IPv6", "::ffff:$ip", mono = true)
    } else {
        InfoRow("Full form", ip.expanded, mono = true)
        InfoRow("/64 network", ip.prefix64, mono = true)
        InfoRow("Interface ID", ip.interfaceId, mono = true)
        InfoRow("MAC address", ip.eui64Mac?.let { "$it (built into the address)" }, mono = true)
        InfoRow("IPv4 inside", ip.embeddedV4?.toString(), mono = true)
        InfoRow("Teredo client", ip.teredoClient?.takeIf { ip.kind == IpKind.TEREDO }?.toString(), mono = true)
    }
    InfoRow("Reverse name", ip.reverseName, mono = true, divider = false)
    Text(ip.kind.detail, style = Type.small, color = p.ink3, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CompareSection(model: AppModel, r: ResultState, info: IpInfo) {
    val p = LocalPalette.current
    val others = Source.entries.filter { it != info.source }
    SectionTitle("Compare sources")
    when (val c = r.compare) {
        is Load.Idle -> {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { model.compare(r) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LineIcon(Glyph.Compare, p.depth, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Ask ${others.joinToString(" and ") { it.label }} too. Location services often disagree, especially for mobile and VPN addresses.",
                    style = Type.label,
                    color = p.depth,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is Load.Loading -> others.forEachIndexed { i, s -> LoadingRow(s.label, 0.6f, divider = i < others.lastIndex) }
        is Load.Ready -> c.value.forEachIndexed { i, row -> ComparedRow(row, info, divider = i < c.value.lastIndex) }
        is Load.Failed -> Text(c.message, style = Type.label, color = p.ink2)
    }
}

@Composable
private fun ComparedRow(row: Compared, main: IpInfo, divider: Boolean) {
    val o = row.info
    val value = if (o == null) {
        row.error ?: "No answer"
    } else {
        listOfNotNull(
            o.place.ifEmpty { "Location unknown" },
            o.provider,
            row.km?.let { km ->
                when {
                    km < 1 -> "Same spot as ${main.source.label}"
                    km < 10 -> "${(km * 10).roundToInt() / 10.0} km from ${main.source.label}'s spot"
                    else -> "${km.roundToInt()} km from ${main.source.label}'s spot"
                }
            },
        ).joinToString("\n")
    }
    InfoRow(row.source.label, value, valueColor = if (o == null) LocalPalette.current.ink3 else null, divider = divider)
}

@Composable
private fun Sources(r: ResultState, info: IpInfo) {
    val p = LocalPalette.current
    val registry = (r.rdap as? Load.Ready)?.value?.registry?.substringBefore(" (")
    val parts = buildList {
        add("Location and network from ${info.source.label}" + (info.filledFrom?.let { " (gaps filled from ${it.label})" } ?: ""))
        add("hostname" + (if (r.host != null) " and DNS records" else "") + " from Cloudflare DNS")
        if (registry != null) add("registration from $registry over RDAP")
    }
    Text(
        parts.joinToString("; ").replaceFirstChar { it.uppercase() } + ". Map from Natural Earth and GeoNames.",
        style = Type.small,
        color = p.ink3,
        modifier = Modifier.padding(top = 22.dp),
    )
}
