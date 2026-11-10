package app.ipatlas.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.ipatlas.AppModel
import app.ipatlas.BuildConfig
import app.ipatlas.data.Cooldowns
import app.ipatlas.data.Source
import app.ipatlas.data.ThemeMode

@Composable
fun SettingsScreen(model: AppModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val settings = model.store.settings
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var confirmClear by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = statusTop + 18.dp, bottom = 28.dp),
        ) {
            Text("Settings", style = Type.screenTitle, color = p.ink)

            SectionTitle("Look")
            for ((i, mode) in ThemeMode.entries.withIndex()) {
                Choice(
                    title = mode.label,
                    detail = when (mode) {
                        ThemeMode.SYSTEM -> "Day or night, following the phone's dark theme."
                        ThemeMode.DAY -> "Light: white water and buff land, like a paper chart."
                        ThemeMode.NIGHT -> "Dark: deep water and dim land, easy on the eyes at night."
                    },
                    selected = settings.theme == mode,
                    divider = i < ThemeMode.entries.lastIndex,
                ) { model.store.updateSettings { it.copy(theme = mode) } }
            }

            SectionTitle("Where lookups come from")
            Choice(
                title = "Automatic",
                detail = "Asks ${settings.copy(source = null).order.joinToString(", then ") { it.label }}, moving on when one is " +
                    "busy or out of free lookups, and fills in anything one leaves out.",
                selected = settings.source == null,
            ) { model.store.updateSettings { it.copy(source = null) } }
            for ((i, s) in Source.entries.withIndex()) {
                Choice(
                    title = s.label + " first",
                    detail = s.about,
                    selected = settings.source == s,
                    divider = i < Source.entries.lastIndex,
                ) { model.store.updateSettings { it.copy(source = s) } }
            }

            val key = settings.ipapiKey.trim()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { editKey = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("ipapi.is key", style = Type.bodyMedium, color = p.ink)
                    Text(
                        if (key.isEmpty()) {
                            "Optional. A free key adds VPN, proxy, Tor and hosting checks and abuse contacts."
                        } else {
                            "Saved (" + key.take(4) + "…" + key.takeLast(2) + "). ipapi.is is asked first."
                        },
                        style = Type.small,
                        color = p.ink2,
                    )
                }
                LineIcon(Glyph.Chevron, p.ink3, size = 20.dp)
            }

            SectionTitle("History")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { model.store.updateSettings { it.copy(saveHistory = !it.saveHistory) } }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep a history of lookups", style = Type.bodyMedium, color = p.ink)
                    Text("Starred lookups are always kept.", style = Type.small, color = p.ink2)
                }
                Switch(
                    checked = settings.saveHistory,
                    onCheckedChange = { on -> model.store.updateSettings { it.copy(saveHistory = on) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = p.bg,
                        checkedTrackColor = p.ink,
                        checkedBorderColor = p.ink,
                        uncheckedThumbColor = p.ink3,
                        uncheckedTrackColor = p.raised,
                        uncheckedBorderColor = p.rule,
                    ),
                )
            }
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, enabled = model.store.history.isNotEmpty()) { confirmClear = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LineIcon(Glyph.Trash, if (model.store.history.isNotEmpty()) p.flare else p.ink3, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (model.store.history.isEmpty()) "History is empty" else "Clear history (${model.store.history.count { !it.starred }} lookups)",
                    style = Type.bodyMedium,
                    color = if (model.store.history.isNotEmpty()) p.flare else p.ink3,
                )
            }

            SectionTitle("About")
            InfoRow("Version", BuildConfig.VERSION_NAME)
            InfoRow("Made by", "David & Dev", trailing = Glyph.External, onClick = { context.browse("https://davidanddev.com", toaster) })
            Text(
                "Lookups go straight from this phone to the services below. IP Atlas has no server of its own and " +
                    "sends nothing anywhere else. History stays on this phone.",
                style = Type.label,
                color = p.ink2,
                modifier = Modifier.padding(top = 12.dp),
            )
            SectionTitle("Data sources")
            for (s in Source.entries) {
                InfoRow(s.label, s.about, trailing = Glyph.External, onClick = { context.browse(s.site, toaster) })
            }
            InfoRow("Your address", "ipify", trailing = Glyph.External, onClick = { context.browse("https://www.ipify.org", toaster) })
            InfoRow("DNS", "Cloudflare DNS over HTTPS, with Google Public DNS as backup")
            InfoRow("Registration", "RDAP through rdap.org, from ARIN, RIPE NCC, APNIC, LACNIC and AFRINIC")
            InfoRow("Map", "Natural Earth countries and states, GLOBE land mask, GeoNames towns (CC BY 4.0)", divider = false)
            Text(
                "Type: Barlow Semi Condensed and IBM Plex Mono, both under the SIL Open Font License.",
                style = Type.small,
                color = p.ink3,
                modifier = Modifier.padding(top = 14.dp),
            )
        }

        StatusScrim()
    }

    if (editKey) {
        KeyDialog(
            current = settings.ipapiKey,
            onSave = { k ->
                model.store.updateSettings { it.copy(ipapiKey = k.trim()) }
                Cooldowns.clear(Source.IPAPI_IS)
                editKey = false
            },
            onGetKey = { context.browse("https://ipapi.is/app/signup", toaster) },
            onDismiss = { editKey = false },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?", style = Type.section) },
            text = { Text("Every lookup except your starred ones will be removed from this phone.", style = Type.body) },
            confirmButton = {
                TextButton(onClick = {
                    model.store.clear(keepStarred = true)
                    confirmClear = false
                }) { Text("Clear history", style = Type.bodyMedium, color = p.flare) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep it", style = Type.bodyMedium, color = p.ink) }
            },
            containerColor = p.raised,
            titleContentColor = p.ink,
            textContentColor = p.ink2,
        )
    }
}

/** A row you pick one of: title, a line about it, and a round mark when it's the one in use. */
@Composable
private fun Choice(title: String, detail: String, selected: Boolean, divider: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.RadioButton, onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = Type.bodyMedium, color = p.ink)
                Text(detail, style = Type.small, color = p.ink2)
            }
            Box(
                Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, if (selected) p.ink else p.ink3, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(p.ink))
            }
        }
        if (divider) Hairline()
    }
}

/** Paste (or remove) a free ipapi.is key. */
@Composable
private fun KeyDialog(current: String, onSave: (String) -> Unit, onGetKey: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(current) }
    val shape = RoundedCornerShape(10.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ipapi.is key", style = Type.section) },
        text = {
            Column {
                Text(
                    "Sign up free at ipapi.is (no card), copy your API key and paste it here. Leave it empty to go without.",
                    style = Type.body,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(shape)
                        .background(p.bg)
                        .border(1.dp, p.rule, shape)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text("Paste your key", style = Type.valueMono, color = p.ink3)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.trim() },
                        singleLine = true,
                        textStyle = Type.valueMono.copy(color = p.ink),
                        cursorBrush = SolidColor(p.flare),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Get a free key",
                    style = Type.chip,
                    color = p.depth,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onGetKey).padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", style = Type.bodyMedium, color = p.ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Type.bodyMedium, color = p.ink2) } },
        containerColor = p.raised,
        titleContentColor = p.ink,
        textContentColor = p.ink2,
    )
}

