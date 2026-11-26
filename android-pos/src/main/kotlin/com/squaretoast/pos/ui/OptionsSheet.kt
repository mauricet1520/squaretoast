package com.squaretoast.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.squaretoast.pos.order.OptionChoice
import com.squaretoast.pos.order.OptionGroup
import com.squaretoast.pos.order.OptionRules

/** Pick options for one item. Add stays off until every required choice is made. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(sheet: PosViewModel.OptionSheet, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val chosen = remember(sheet) { mutableStateListOf<String>() }
    val problems = OptionRules.problems(sheet.groups, chosen)
    val deltas = sheet.groups.flatMap { it.options }.filter { it.id in chosen }.sumOf { it.priceDeltaCents }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text(sheet.item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Seat ${sheet.seat} · Course ${sheet.course}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            for (g in sheet.groups) {
                item(key = "h-${g.id}") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(rule(g), style = MaterialTheme.typography.labelLarge,
                            color = if (g.minSelect > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(g.options, key = { "o-${g.id}-${it.id}" }) { o ->
                    OptionRow(g, o, selected = o.id in chosen) {
                        if (o.id in chosen) {
                            chosen.remove(o.id)
                        } else {
                            if (g.maxSelect == 1) chosen.removeAll { id -> g.options.any { it.id == id } } // radio: swap
                            chosen.add(o.id)
                        }
                    }
                }
            }
            item {
                Column(Modifier.navigationBarsPadding().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    problems.firstOrNull()?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { onAdd(chosen.toList()) }, enabled = problems.isEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Add · ${money(sheet.item.priceCents + deltas)}", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

private fun rule(g: OptionGroup): String = when {
    g.minSelect > 0 && g.minSelect == g.maxSelect -> if (g.minSelect == 1) "Required" else "Pick ${g.minSelect}"
    g.minSelect > 0 -> "Pick ${g.minSelect}–${g.maxSelect}"
    else -> "Optional · up to ${g.maxSelect}"
}

@Composable
private fun OptionRow(g: OptionGroup, o: OptionChoice, selected: Boolean, onToggle: () -> Unit) {
    val radio = g.maxSelect == 1
    val mod = if (radio) {
        Modifier.selectable(selected, enabled = o.available, role = Role.RadioButton, onClick = onToggle)
    } else {
        Modifier.toggleable(selected, enabled = o.available, role = Role.Checkbox, onValueChange = { onToggle() })
    }
    Row(mod.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (radio) RadioButton(selected, onClick = null, enabled = o.available) else Checkbox(selected, onCheckedChange = null, enabled = o.available)
        Text(o.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp),
            color = if (o.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            !o.available -> Text("Sold out", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            o.priceDeltaCents > 0 -> Text("+" + money(o.priceDeltaCents), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}
