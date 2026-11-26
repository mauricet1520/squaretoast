package com.squaretoast.pos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squaretoast.pos.data.MenuItemEntity
import com.squaretoast.pos.order.CartLine

/** Whole cents as dollars, for display only. */
fun money(cents: Long): String = "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')

/**
 * Ring up a table: pick the table, the seat and course, tap items, send. Works from the saved menu, so it keeps
 * working when the internet drops; the order syncs when it comes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderEntryScreen(vm: PosViewModel) {
    val menu by vm.menu.collectAsState()
    val meta by vm.menuMeta.collectAsState()
    val cart by vm.cart.collectAsState()
    val table by vm.table.collectAsState()
    val sending by vm.sending.collectAsState()
    val orders by vm.openOrders.collectAsState()
    var seat by remember { mutableIntStateOf(1) }
    var course by remember { mutableIntStateOf(1) }
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 4 else 2 // unfolded Fold or tablet: wider grid
    val recentTables = remember(orders) { orders.map { it.tableLabel }.distinct().take(8) }

    BackHandler(onBack = vm::closeOrder)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("New order", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = vm::closeOrder) { Text("Cancel") } },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Button(
                    onClick = vm::send,
                    enabled = !sending && cart.lines.isNotEmpty() && table.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 56.dp),
                ) {
                    val count = cart.lines.sumOf { it.quantity }
                    Text(
                        when {
                            table.isBlank() -> "Pick a table"
                            count == 0 -> "Add items"
                            else -> "Send $count to kitchen · ${money(cart.subtotalCents)} est."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = table, onValueChange = vm::setTable, label = { Text("Table") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (recentTables.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentTables) { t -> FilterChip(selected = t == table, onClick = { vm.setTable(t) }, label = { Text(t) }) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Seat", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((1..12).toList()) { s -> FilterChip(selected = s == seat, onClick = { seat = s }, label = { Text("$s") }) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Course", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(1 to "1 Starter", 2 to "2 Main", 3 to "3 Dessert")) { (n, label) ->
                            FilterChip(selected = n == course, onClick = { course = n }, label = { Text(label) })
                        }
                    }
                }
            }
            if (meta == null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Menu not loaded. Connect to the internet once to load it.", Modifier.weight(1f))
                            TextButton(onClick = vm::refreshMenu) { Text("Retry") }
                        }
                    }
                }
            }
            items(menu.chunked(columns)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> MenuTile(item, Modifier.weight(1f)) { vm.add(item, seat, course) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (cart.lines.isNotEmpty()) {
                item { Text("This order", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(cart.lines.sortedWith(compareBy({ it.course }, { it.seat })), key = { "${it.menuItemId}/${it.seat}/${it.course}" }) { line ->
                    CartRow(line, onQty = { vm.setQuantity(line, it) })
                }
            }
        }
    }
}

@Composable
private fun MenuTile(item: MenuItemEntity, modifier: Modifier, onAdd: () -> Unit) {
    val enabled = item.available && !item.needsOptions
    Card(
        onClick = onAdd, enabled = enabled, modifier = modifier.heightIn(min = 88.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(money(item.priceCents), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            when {
                !item.available -> Badge("86'd", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                item.needsOptions -> Badge("Needs options", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Badge(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun CartRow(line: CartLine, onQty: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.titleSmall)
                Text("Seat ${line.seat} · Course ${line.course} · ${money(line.totalCents)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { onQty(line.quantity - 1) }) { Text("−") }
            Text("${line.quantity}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
            FilledTonalButton(onClick = { onQty(line.quantity + 1) }) { Text("+") }
        }
    }
}
