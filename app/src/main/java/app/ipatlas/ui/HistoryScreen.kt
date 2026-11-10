package app.ipatlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.ipatlas.AppModel
import app.ipatlas.data.HistoryEntry

@Composable
fun HistoryScreen(model: AppModel) {
    val p = LocalPalette.current
    val copy = rememberCopier()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var filter by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val all = model.store.history
    val f = filter.trim()
    val shown = if (f.isEmpty()) all else all.filter { e ->
        e.query.contains(f, true) || e.ip.contains(f, true) || e.place.contains(f, true) || (e.provider?.contains(f, true) == true)
    }
    val starred = shown.filter { it.starred }
    val rest = shown.filterNot { it.starred }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = statusTop + 18.dp, bottom = 28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("History", style = Type.screenTitle, color = p.ink, modifier = Modifier.weight(1f))
                if (all.isNotEmpty()) {
                    Text(
                        "Clear",
                        style = Type.chip,
                        color = p.depth,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { confirmClear = true }.padding(8.dp),
                    )
                }
            }
        }
        if (all.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Every address and domain you look up is kept here, newest first. Star the ones you want to keep at the top.",
                    style = Type.body,
                    color = p.ink2,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
            return@LazyColumn
        }
        item { FilterField(filter, { filter = it }, Modifier.padding(top = 14.dp)) }
        if (shown.isEmpty()) {
            item {
                Text("Nothing matches \"$f\".", style = Type.body, color = p.ink2, modifier = Modifier.padding(top = 18.dp))
            }
        }
        if (starred.isNotEmpty()) {
            item { SectionTitle("Starred") }
            items(starred, key = { "s:" + it.query }) { e ->
                EntryRow(model, e, divider = e != starred.last(), onCopy = { copy("Address", it) })
            }
        }
        if (rest.isNotEmpty()) {
            item { SectionTitle(if (starred.isEmpty()) "Recent" else "Everything else") }
            items(rest, key = { "r:" + it.query }) { e ->
                EntryRow(model, e, divider = e != rest.last(), onCopy = { copy("Address", it) })
            }
        }
    }

    if (confirmClear) {
        val keep = all.any { it.starred }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?", style = Type.section) },
            text = {
                Text(
                    if (keep) "Every lookup except your starred ones will be removed from this phone." else "Every lookup will be removed from this phone.",
                    style = Type.body,
                )
            },
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

@Composable
private fun EntryRow(model: AppModel, e: HistoryEntry, divider: Boolean, onCopy: (String) -> Unit) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box {
        HistoryRow(
            e,
            onOpen = { model.openEntry(e) },
            divider = divider,
            onLongPress = { menu = true },
            trailing = {
                RoundButton(
                    if (e.starred) Glyph.StarFilled else Glyph.Star,
                    if (e.starred) "Remove star" else "Star",
                    onClick = { model.store.setStarred(e, !e.starred) },
                    tint = if (e.starred) p.flare else p.ink3,
                    background = Color.Transparent,
                    size = 38.dp,
                    iconSize = 20.dp,
                )
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(p.raised)) {
            DropdownMenuItem(
                text = { Text("Copy address", style = Type.body, color = p.ink) },
                onClick = {
                    onCopy(e.ip.ifEmpty { e.query })
                    menu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from history", style = Type.body, color = p.flare) },
                onClick = {
                    model.store.delete(e.query)
                    menu = false
                },
            )
        }
    }
}

@Composable
private fun FilterField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(p.raised)
            .border(1.dp, p.rule, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Glyph.Search, p.ink3, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Filter by address, place or provider", style = Type.label, color = p.ink3, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.label.copy(color = p.ink),
                cursorBrush = SolidColor(p.flare),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            RoundButton(Glyph.Close, "Clear filter", onClick = { onChange("") }, background = Color.Transparent, tint = p.ink3, size = 34.dp, iconSize = 18.dp)
        }
    }
}
