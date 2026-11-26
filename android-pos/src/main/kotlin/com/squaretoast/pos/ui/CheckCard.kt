package com.squaretoast.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.squaretoast.pos.domain.SplitAllocator
import com.squaretoast.pos.order.Tips
import com.squaretoast.pos.sync.Bill

/** The selected order's bill, as the server computed it, with tip, even split and Charge. */
@Composable
fun CheckCard(vm: PosViewModel) {
    val view by vm.bill.collectAsState()
    val tip by vm.tip.collectAsState()
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.loadBill() }) { Text("Refresh") }
            }
            when (val v = view) {
                PosViewModel.BillView.None, PosViewModel.BillView.Loading -> Muted("Loading the bill…")
                PosViewModel.BillView.NotSynced -> Muted("The bill appears once this order reaches the server.")
                PosViewModel.BillView.Offline -> Muted("The bill needs a connection. Use QR pay at the table if you're offline.")
                is PosViewModel.BillView.Ready -> Ready(v.bill, tip, vm)
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Amount(label: String, cents: Long, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        val style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(money(cents), style = style, fontWeight = if (strong) FontWeight.SemiBold else null)
    }
}

@Composable
private fun Ready(bill: Bill, tip: Long, vm: PosViewModel) {
    for (l in bill.lines) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("${l.qty}× ${l.name}", style = MaterialTheme.typography.bodyLarge)
                if (l.modifiers.isNotEmpty()) {
                    Text(l.modifiers.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(money(l.amountCents), style = MaterialTheme.typography.bodyLarge)
        }
    }
    HorizontalDivider()
    Amount("Subtotal", bill.subtotalCents)
    if (bill.serviceChargeCents > 0) Amount("Service charge", bill.serviceChargeCents)
    Amount("Tax", bill.taxCents)
    if (bill.paidCents > 0) Amount("Paid", bill.paidCents)
    Amount("Due", bill.dueCents, strong = true)

    if (bill.dueCents == 0L) {
        Muted("Paid in full.")
        return
    }

    Text("Tip (on the subtotal, before tax)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = tip == 0L, onClick = { vm.setTip(0) }, label = { Text("No tip") }) }
        items(Tips.PERCENTS) { p ->
            val cents = runCatching { Tips.percentOf(bill.subtotalCents, p) }.getOrDefault(0L) // never crash the screen
            FilterChip(selected = tip == cents && tip != 0L, onClick = { vm.setTip(cents) }, label = { Text("$p% · ${money(cents)}") })
        }
    }

    val ways by vm.splitWays.collectAsState()
    val charging by vm.charging.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Split evenly", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = { vm.setSplitWays(ways - 1) }, enabled = ways > 1) { Text("−") }
        Text("$ways", style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(onClick = { vm.setSplitWays(ways + 1) }, enabled = ways < 20) { Text("+") }
    }
    if (ways > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SplitAllocator.evenly(Tips.charge(bill.dueCents, tip), ways)) { cents ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(money(cents), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Muted("Each guest pays their share at the table with QR pay.")
    }

    Button(onClick = { vm.charge() }, enabled = !charging, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 4.dp)) {
        Text(if (charging) "Charging…" else "Charge ${money(Tips.charge(bill.dueCents, tip))}", style = MaterialTheme.typography.titleMedium)
    }
}
