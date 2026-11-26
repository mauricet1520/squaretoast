package com.squaretoast.pos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squaretoast.pos.data.AppDatabase
import com.squaretoast.pos.data.CourseEntity
import com.squaretoast.pos.data.MenuItemEntity
import com.squaretoast.pos.data.MenuMetaEntity
import com.squaretoast.pos.order.Cart
import com.squaretoast.pos.order.CartLine
import com.squaretoast.pos.order.MenuChoice
import com.squaretoast.pos.order.OptionChoice
import com.squaretoast.pos.order.OptionGroup
import com.squaretoast.pos.order.OptionRules
import com.squaretoast.pos.payment.PaymentMethod
import com.squaretoast.pos.sync.Bill
import com.squaretoast.pos.sync.RejectedBatchException
import kotlinx.coroutines.flow.map
import com.squaretoast.pos.sync.MenuSync
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
import com.squaretoast.pos.sync.DeviceCredentialStore
import com.squaretoast.pos.sync.DeviceSession
import com.squaretoast.pos.payment.KeystoreClaimKey
import android.os.Build
import com.squaretoast.pos.sync.OutboxDrainWorker
import com.squaretoast.pos.terminal.TerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val credentials: DeviceCredentialStore,
    private val session: DeviceSession,
    private val menuSync: MenuSync,
) : AndroidViewModel(app) {

    // ---- Order entry (plan 13). The cart lives here so it survives folding, unfolding and rotation. ----

    val menu: StateFlow<List<MenuItemEntity>> =
        db.menuDao().observeItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val menuMeta: StateFlow<MenuMetaEntity?> =
        db.menuDao().observeMeta().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _ordering = MutableStateFlow(false)
    val ordering: StateFlow<Boolean> = _ordering.asStateFlow()
    private val _cart = MutableStateFlow(Cart())
    val cart: StateFlow<Cart> = _cart.asStateFlow()
    private val _table = MutableStateFlow("")
    val table: StateFlow<String> = _table.asStateFlow()
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    fun refreshMenu() = viewModelScope.launch { menuSync.refresh() }

    fun startOrder(table: String = "") { _table.value = table; _ordering.value = true; refreshMenu() }
    fun closeOrder() { _ordering.value = false }
    fun setTable(label: String) { _table.value = label.take(24) }
    /** Items that have option groups open the options sheet; the rest go straight into the cart. */
    val itemsWithOptions: StateFlow<Set<String>> = db.menuDao().observeItemsWithOptions().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    data class OptionSheet(val item: MenuItemEntity, val groups: List<OptionGroup>, val seat: Int, val course: Int)
    private val _sheet = MutableStateFlow<OptionSheet?>(null)
    val sheet: StateFlow<OptionSheet?> = _sheet.asStateFlow()

    fun tap(item: MenuItemEntity, seat: Int, course: Int) = viewModelScope.launch {
        if (!item.available) return@launch
        val groups = db.menuDao().groupsFor(item.id)
        if (groups.isEmpty()) { add(item, seat, course); return@launch }
        val options = db.menuDao().optionsFor(groups.map { it.id }).groupBy { it.groupId }
        _sheet.value = OptionSheet(item, groups.map { g ->
            OptionGroup(g.id, g.name, g.minSelect, g.maxSelect,
                options[g.id].orEmpty().map { OptionChoice(it.id, it.name, it.priceDeltaCents, it.available) })
        }, seat, course)
    }

    fun addWithOptions(chosenIds: List<String>) {
        val s = _sheet.value ?: return
        val choice = MenuChoice(s.item.id, s.item.name, s.item.priceCents, s.item.available, s.item.needsOptions)
        if (OptionRules.problems(s.groups, chosenIds).isEmpty()) {
            _cart.value = _cart.value.addWithOptions(choice, s.seat, s.course, s.groups, chosenIds)
            _sheet.value = null
        }
    }

    fun closeOptions() { _sheet.value = null }

    /** Staff saw a refused change and tapped OK. */
    fun acknowledge(mutationId: String) = viewModelScope.launch {
        orders.acknowledgeConflict(mutationId)
        if (db.orderDao().order(selected.value ?: "") == null) selected.value = null
    }

    fun add(item: MenuItemEntity, seat: Int, course: Int) {
        val choice = MenuChoice(item.id, item.name, item.priceCents, item.available, item.needsOptions)
        if (_cart.value.canAdd(choice)) _cart.value = _cart.value.add(choice, seat, course)
    }
    fun setQuantity(line: CartLine, qty: Int) { _cart.value = _cart.value.setQuantity(line, qty) }

    /** Saves the order on this phone and queues it for the cloud; works offline. The cart's IDs make a retry harmless. */
    fun send() = viewModelScope.launch {
        if (_sending.value) return@launch
        val location = menuMeta.value?.locationId ?: db.menuDao().meta()?.locationId
        if (location == null) { _message.value = "Menu not loaded yet. Connect to the internet once to load it."; return@launch }
        _sending.value = true
        val cart = _cart.value
        val outcome = try {
            Result.success(orders.placeOrderOnce(cart.toPlaceOrder(_table.value, UUID.fromString(location))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _sending.value = false
        }
        if (outcome.isSuccess) {
            OutboxDrainWorker.enqueue(app)
            select(cart.orderId.toString())
            _cart.value = Cart()
            _table.value = ""
            _ordering.value = false
        } else {
            _message.value = "Couldn't save the order on this phone. Try again."
        }
    }

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

    /** Turns false if the cloud rejects the token, which brings the enrollment screen back. */
    val enrolled: StateFlow<Boolean> = session.enrolled

    /** Registers this phone with a one-time code and its Keystore claim key; the token is stored encrypted. */
    fun enroll(code: String) = viewModelScope.launch {
        val outcome = runCatching {
            val key = KeystoreClaimKey.loadOrCreate().publicKey.encoded
            credentials.save(cloud.enroll(code.trim(), key, Build.MODEL).deviceToken)
        }
        if (outcome.isSuccess) {
            session.saved()
            OutboxDrainWorker.enqueue(app)
            refreshMenu()
        } else {
            _message.value = "That code didn't work. Ask a manager for a new one."
        }
    }

    fun select(orderId: String) { selected.value = orderId; _tip.value = 0; loadBill() }

    // ---- The bill (plan 15). The server computes every amount; the POS shows it and picks the tip. ----

    sealed interface BillView {
        data object None : BillView
        data object Loading : BillView
        data object NotSynced : BillView
        data object Offline : BillView
        data class Ready(val checkId: UUID, val bill: Bill) : BillView
    }

    private val _bill = MutableStateFlow<BillView>(BillView.None)
    val bill: StateFlow<BillView> = _bill.asStateFlow()
    private val _tip = MutableStateFlow(0L)
    val tip: StateFlow<Long> = _tip.asStateFlow()

    fun setTip(cents: Long) { if (cents >= 0) _tip.value = cents }

    fun loadBill() = viewModelScope.launch {
        val checkId = selected.value?.let { orders.checkIdOf(it) }?.let(UUID::fromString)
        if (checkId == null) { _bill.value = BillView.None; return@launch }
        _bill.value = BillView.Loading
        _bill.value = try {
            BillView.Ready(checkId, cloud.bill(checkId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RejectedBatchException) {
            if (e.status == 404) BillView.NotSynced else BillView.Offline
        } catch (e: Exception) {
            BillView.Offline
        }
    }

    /** Charges the server's amount due plus the chosen tip, by the best available method. */
    fun charge() = viewModelScope.launch {
        val ready = _bill.value as? BillView.Ready ?: return@launch
        choosePaymentMethod().join()
        val c = _choice.value ?: return@launch
        when (c.method) {
            PaymentMethod.TAP_TO_PAY -> payWithTapToPay(ready.checkId, _tip.value).join()
            PaymentMethod.BLUETOOTH_READER -> _message.value = "Connect the card reader to charge this check."
            PaymentMethod.QR_PAY_AT_TABLE -> _message.value = "Ask the guest to scan the table QR to pay." +
                (PaymentLabels.reason(c)?.let { "\n$it" } ?: "")
        }
        loadBill()
    }

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
                // The platform-account Terminal Location for this restaurant, provisioned server-side.
                val location = runCatching { cloud.me().terminalLocationId }.getOrNull()
                location != null && runCatching { terminal.connectTapToPay(location) }.getOrDefault(false)
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
}
