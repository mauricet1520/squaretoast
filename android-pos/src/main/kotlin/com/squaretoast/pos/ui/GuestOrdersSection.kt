package com.squaretoast.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.squaretoast.pos.order.GuestOrders
import com.squaretoast.pos.sync.GuestOrder

/** Slice 12: guest QR orders waiting for staff, and the open checks of accepted ones. */
@Composable
fun GuestOrdersSection(orders: List<GuestOrder>, selectedCheck: String?, accepting: Set<String>, vm: PosViewModel) {
    val waiting = GuestOrders.waiting(orders)
    val chips = GuestOrders.chips(orders)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From guests (QR)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = vm::loadGuestOrders) { Text("Refresh") }
            }
            if (waiting.isEmpty() && chips.isEmpty()) {
                Text("No guest orders right now.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (o in waiting) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${o.table} · waiting", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(GuestOrders.summary(o), style = MaterialTheme.typography.bodyMedium)
                        }
                        val busy = o.orderId in accepting
                        Button(onClick = { vm.accept(o) }, enabled = !busy) { Text(if (busy) "Accepting…" else "Accept") }
                    }
                }
            }
            if (chips.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it.checkId }) { c ->
                        FilterChip(selected = c.checkId == selectedCheck, onClick = { vm.selectGuestCheck(c.checkId) },
                            label = { Text(c.label) })
                    }
                }
            }
        }
    }
}
