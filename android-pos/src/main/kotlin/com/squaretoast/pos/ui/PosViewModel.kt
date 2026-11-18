package com.squaretoast.pos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squaretoast.pos.data.AppDatabase
import com.squaretoast.pos.data.CourseEntity
import com.squaretoast.pos.data.OrderEntity
import com.squaretoast.pos.data.OrderRepository
import com.squaretoast.pos.domain.CourseAction
import com.squaretoast.pos.domain.SplitAllocator
import com.squaretoast.pos.hub.HubForegroundService
import com.squaretoast.pos.hub.HubRuntime
import com.squaretoast.pos.hub.kdsPairingUrl
import com.squaretoast.pos.payment.AndroidDeviceSignals
import com.squaretoast.pos.payment.PaymentChoice
import com.squaretoast.pos.payment.PaymentMethodSelector
import com.squaretoast.pos.payment.TapToPayGate
import com.squaretoast.pos.sync.CloudApi
import com.squaretoast.pos.sync.OutboxDrainWorker
import com.squaretoast.pos.terminal.TerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PosViewModel @Inject constructor(
    private val app: Application,
    private val db: AppDatabase,
    private val orders: OrderRepository,
    private val hub: HubRuntime,
    private val cloud: CloudApi,
    private val terminal: TerminalSession,
) : AndroidViewModel(app) {

    val openOrders: StateFlow<List<OrderEntity>> =
        db.orderDao().observeOrders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selected = MutableStateFlow<String?>(null)
    val selectedOrder: StateFlow<String?> = selected.asStateFlow()

    val courses: StateFlow<List<CourseEntity>> = selected
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.orderDao().observeCourses(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conflicts = db.outboxDao().conflicts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _choice = MutableStateFlow<PaymentChoice?>(null)
    val choice: StateFlow<PaymentChoice?> = _choice.asStateFlow()

    private val _hubUrl = MutableStateFlow<String?>(null)
    val hubUrl: StateFlow<String?> = _hubUrl.asStateFlow()

    fun select(orderId: String) { selected.value = orderId }

    fun transition(course: CourseEntity, action: CourseAction) = viewModelScope.launch {
        runCatching { orders.transitionCourse(UUID.randomUUID(), course.id, action) }
            .onSuccess { OutboxDrainWorker.enqueue(app) }
            .onFailure { _message.value = "Can't ${action.name.lowercase()} a ${course.state.name.lowercase()} course" }
    }

    fun split(totalCents: Long, ways: Int): List<Long> = SplitAllocator.evenly(totalCents, ways)

    fun choosePaymentMethod() = viewModelScope.launch {
        val signals = AndroidDeviceSignals(
            app,
            tokenRoundTrip = { runCatching { cloud.connectionToken() }.isSuccess },
            supported = { runCatching { terminal.ensureInitialized(); terminal.tapToPaySupported() }.getOrDefault(false) },
            attestation = {
                TERMINAL_LOCATION.isNotEmpty() &&
                    runCatching { terminal.connectTapToPay(TERMINAL_LOCATION) }.getOrDefault(false)
            },
        )
        _choice.value = PaymentMethodSelector(TapToPayGate(signals)) { null }.select()
    }

    /** The server computes the amount from the check; the POS only sends the guest's tip. */
    fun payWithTapToPay(checkId: UUID, tipCents: Long) = viewModelScope.launch {
        val outcome = runCatching {
            val started = cloud.startCardPayment(checkId, UUID.randomUUID(), "CARD_PRESENT", tipCents)
            terminal.collect(started.clientSecret)
        }
        _message.value = if (outcome.getOrNull() != null) "Payment submitted" else "Payment did not complete"
    }

    fun startHub() {
        HubForegroundService.start(app)
        _hubUrl.value = lanAddress()?.let { kdsPairingUrl(it, HubForegroundService.PORT, hub.tokens.current) }
    }

    fun stopHub() {
        HubForegroundService.stop(app)
        hub.tokens.rotate() // a new shift needs a new pairing
        _hubUrl.value = null
    }

    fun dismissMessage() { _message.value = null }

    private fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    private companion object {
        // Provisioned per location by the backend in Slice 5b.
        const val TERMINAL_LOCATION = ""
    }
}
