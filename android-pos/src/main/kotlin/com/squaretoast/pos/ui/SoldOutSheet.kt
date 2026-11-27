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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.squaretoast.pos.order.StockList

/** Slice 11: mark stock sold out (86) or back in stock. Every screen at the location updates from the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoldOutSheet(vm: PosViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadStock() }
    val view by vm.stock.collectAsState()
    val saving by vm.stockSaving.collectAsState()
    val error by vm.stockError.collectAsState()
    var search by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sold out", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::loadStock) { Text("Refresh") }
            }
            Text("Turn an item on to 86 it everywhere: this POS, the KDS and guest QR menus.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            when (val v = view) {
                PosViewModel.StockView.Loading -> Text("Loading stock…", style = MaterialTheme.typography.bodyMedium)
                PosViewModel.StockView.Offline -> Text("86ing needs a connection. Try again when you're back online.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                is PosViewModel.StockView.Ready -> {
                    OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                        label = { Text("Search stock") }, modifier = Modifier.fillMaxWidth())
                    val shown = StockList.show(v.items, search)
                    if (v.items.isEmpty()) {
                        Text("No stock is set up for this location yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(shown, key = { it.id }) { item ->
                            val busy = item.id in saving
                            // Counted stock at 0 is sold out until restocked; the switch can't bring it back.
                            val emptyCount = StockList.soldOut(item) && !item.eightySixed
                            val canToggle = !busy && !emptyCount
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                                    value = StockList.soldOut(item), enabled = canToggle, role = Role.Switch,
                                    onValueChange = { vm.setSoldOut(item, it) },
                                ),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(when {
                                        busy -> "Saving…"
                                        emptyCount -> "None left · restock to bring it back"
                                        else -> StockList.label(item)
                                    }, style = MaterialTheme.typography.bodySmall,
                                        color = if (StockList.soldOut(item)) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = StockList.soldOut(item), onCheckedChange = null, enabled = canToggle)
                            }
                        }
                    }
                }
            }
        }
    }
}
