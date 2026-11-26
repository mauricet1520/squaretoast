package com.squaretoast.pos.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.squaretoast.pos.data.CourseEntity
import com.squaretoast.pos.domain.CourseAction
import com.squaretoast.pos.domain.CourseState
import com.squaretoast.pos.ui.theme.PosTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: PosViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION) // Stripe Terminal requirement
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(permissions.toTypedArray())
        setContent { PosTheme { Surface(Modifier.fillMaxSize()) { Root(vm) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PosScreen(vm: PosViewModel) {
    val orders by vm.openOrders.collectAsState()
    val selected by vm.selectedOrder.collectAsState()
    val courses by vm.courses.collectAsState()
    val conflicts by vm.conflicts.collectAsState()
    val message by vm.message.collectAsState()
    val choice by vm.choice.collectAsState()
    val hubUrl by vm.hubUrl.collectAsState()

    if (conflicts.isNotEmpty()) {
        // A server CONFLICT (e.g. OVERSOLD) blocks until a person has seen it; it is never dropped silently.
        AlertDialog(onDismissRequest = {}, confirmButton = {},
            title = { Text("Sync conflict") },
            text = { Text(conflicts.joinToString("\n") { "${it.type}: ${it.lastError ?: "rejected"}" }) })
    }
    message?.let { MessageDialog(it, vm::dismissMessage) }

    LaunchedEffect(Unit) { vm.refreshMenu() } // keep the saved menu (and its 86s) current while online

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.startOrder() }) {
                Text("+  New order", style = MaterialTheme.typography.titleMedium)
            }
        },
        topBar = {
            LargeTopAppBar(
                title = { Text("SquareToast", fontWeight = FontWeight.SemiBold) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Section("Orders") {
                    if (orders.isEmpty()) {
                        Text("No open orders", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(orders, key = { it.id }) { o ->
                                FilterChip(selected = o.id == selected, onClick = { vm.select(o.id) }, label = { Text(o.tableLabel) })
                            }
                        }
                    }
                }
            }
            if (selected != null) {
                items(courses, key = { it.id }) { c -> CourseCard(c) { a -> vm.transition(c, a) } }
            }
            item { SplitPreview(vm) }
            item {
                Section("Payment") {
                    Button(onClick = vm::choosePaymentMethod, modifier = Modifier.fillMaxWidth()) { Text("Choose payment method") }
                    choice?.let { c ->
                        Text(PaymentLabels.method(c), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        PaymentLabels.reason(c)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { HubPanel(hubUrl, vm::startHub, vm::stopHub) }
        }
    }
}

/** A titled card: the building block of the POS screen. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StateBadge(state: CourseState) {
    val (bg, fg) = when (state) {
        CourseState.HOLD -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        CourseState.FIRED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        CourseState.READY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        CourseState.SERVED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(state.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun CourseCard(course: CourseEntity, onAction: (CourseAction) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Course ${course.number}", style = MaterialTheme.typography.titleMedium)
                StateBadge(course.state)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (course.state) {
                    CourseState.HOLD -> Button(onClick = { onAction(CourseAction.FIRE) }) { Text("Fire") }
                    CourseState.FIRED -> {
                        Button(onClick = { onAction(CourseAction.BUMP) }) { Text("Ready") }
                        OutlinedButton(onClick = { onAction(CourseAction.UNFIRE) }) { Text("Hold") }
                    }
                    CourseState.READY -> {
                        Button(onClick = { onAction(CourseAction.SERVE) }) { Text("Served") }
                        OutlinedButton(onClick = { onAction(CourseAction.RECALL) }) { Text("Recall") }
                    }
                    CourseState.SERVED -> OutlinedButton(onClick = { onAction(CourseAction.RECALL) }) { Text("Undo") }
                }
            }
        }
    }
}

@Composable
private fun SplitPreview(vm: PosViewModel) {
    var ways by remember { mutableIntStateOf(2) }
    val demoTotal = 10_000L // preview only; real splits come from the check balance
    Section("Split evenly") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalButton(onClick = { if (ways > 1) ways-- }, enabled = ways > 1) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Text("$ways ways", style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(onClick = { if (ways < 20) ways++ }, enabled = ways < 20) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.split(demoTotal, ways)) { cents ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun HubPanel(url: String?, start: () -> Unit, stop: () -> Unit) {
    Section("Kitchen hub") {
        if (url == null) {
            Text("Share orders with kitchen screens on this Wi-Fi.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("Start hub") }
        } else {
            Text("Open on the KDS: $url", style = MaterialTheme.typography.bodyMedium)
            val qr = remember(url) { qrBitmap(url) }
            Surface(color = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Image(qr.asImageBitmap(), contentDescription = "Pairing QR code", modifier = Modifier.padding(12.dp).size(200.dp))
            }
            OutlinedButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text("Stop hub") }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun MessageDialog(text: String, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, confirmButton = { TextButton(onClick = dismiss) { Text("OK") } }, text = { Text(text) })
}

@Composable
private fun Root(vm: PosViewModel) {
    val enrolled by vm.enrolled.collectAsState()
    val ordering by vm.ordering.collectAsState()
    when {
        !enrolled -> EnrollScreen(vm)
        ordering -> OrderEntryScreen(vm)
        else -> PosScreen(vm)
    }
}

/** Shown until this phone is enrolled. The code comes from a manager and works once, for 15 minutes. */
@Composable
private fun EnrollScreen(vm: PosViewModel) {
    var code by remember { mutableStateOf("") }
    val message by vm.message.collectAsState()
    message?.let { MessageDialog(it, vm::dismissMessage) }
    Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("SquareToast", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Set up this device", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Enter the one-time code from your manager.", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = code, onValueChange = { code = it.trim() }, label = { Text("Enrollment code") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.enroll(code) }, enabled = code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Enroll") }
        }
    }
}
