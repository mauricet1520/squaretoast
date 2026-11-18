package com.squaretoast.pos.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.squaretoast.pos.data.CourseEntity
import com.squaretoast.pos.domain.CourseAction
import com.squaretoast.pos.domain.CourseState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: PosViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION) // Stripe Terminal requirement
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(permissions.toTypedArray())
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { PosScreen(vm) } } }
    }
}

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
    message?.let { AlertDialog(onDismissRequest = vm::dismissMessage,
        confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } }, text = { Text(it) }) }

    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Orders", style = MaterialTheme.typography.titleLarge) }
        items(orders, key = { it.id }) { o ->
            OutlinedButton(onClick = { vm.select(o.id) }) { Text(if (o.id == selected) "▶ ${o.tableLabel}" else o.tableLabel) }
        }
        if (selected != null) {
            item { Text("Courses", style = MaterialTheme.typography.titleMedium) }
            items(courses, key = { it.id }) { c -> CourseCard(c) { a -> vm.transition(c, a) } }
        }
        item { SplitPreview(vm) }
        item {
            Column {
                Text("Payment", style = MaterialTheme.typography.titleMedium)
                Button(onClick = vm::choosePaymentMethod) { Text("Choose payment method") }
                choice?.let { Text("${it.method}${if (it.offline) " (offline)" else ""}${it.deniedBecause?.let { r -> " — Tap to Pay unavailable: $r" } ?: ""}") }
            }
        }
        item { HubPanel(hubUrl, vm::startHub, vm::stopHub) }
    }
}

@Composable
private fun CourseCard(course: CourseEntity, onAction: (CourseAction) -> Unit) {
    Card { Column(Modifier.padding(12.dp)) {
        Text("Course ${course.number}: ${course.state}")
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
    } }
}

@Composable
private fun SplitPreview(vm: PosViewModel) {
    var ways by remember { mutableIntStateOf(2) }
    val demoTotal = 10_000L // preview only; real splits come from the check balance
    Column {
        Text("Split evenly", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (ways > 1) ways-- }) { Text("−") }
            Text("$ways ways")
            OutlinedButton(onClick = { if (ways < 20) ways++ }) { Text("+") }
        }
        Text(vm.split(demoTotal, ways).joinToString("  ") { "$" + (it / 100) + "." + (it % 100).toString().padStart(2, '0') })
    }
}

@Composable
private fun HubPanel(url: String?, start: () -> Unit, stop: () -> Unit) {
    Column {
        Text("Kitchen hub", style = MaterialTheme.typography.titleMedium)
        if (url == null) {
            Button(onClick = start) { Text("Start hub") }
        } else {
            Text("Open on the KDS: $url")
            val qr = remember(url) { qrBitmap(url) }
            Image(qr.asImageBitmap(), contentDescription = "Pairing QR code", modifier = Modifier.size(200.dp))
            OutlinedButton(onClick = stop) { Text("Stop hub") }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
