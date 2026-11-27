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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

    // ---- Sold out (plan 16). An 86 is server truth, so it needs a connection and is never queued. ----

    sealed interface StockView {
        data object Loading : StockView
        data object Offline : StockView
        data class Ready(val items: List<com.squaretoast.pos.sync.StockItem>) : StockView
    }

    private val _stock = MutableStateFlow<StockView>(StockView.Loading)
    val stock: StateFlow<StockView> = _stock.asStateFlow()
    /** Stock IDs with a switch change on its way to the server. */
    private val _stockSaving = MutableStateFlow<Set<String>>(emptySet())
    val stockSaving: StateFlow<Set<String>> = _stockSaving.asStateFlow()
    private val _stockError = MutableStateFlow<String?>(null)
    val stockError: StateFlow<String?> = _stockError.asStateFlow()
    private var stockJob: Job? = null

    /** Ask the server: a cached location could be stale after re-enrolment, and 86ing needs a connection anyway. */
    private suspend fun stockLocation(): String = cloud.me().locationId

    fun loadStock() {
        stockJob?.cancel()
        _stockError.value = null
        if (_stock.value !is StockView.Ready) _stock.value = StockView.Loading
        stockJob = viewModelScope.launch {
            try {
                _stock.value = StockView.Ready(cloud.stock(stockLocation()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep a list we already have on screen; only say "offline" when there is nothing to show.
                if (_stock.value is StockView.Ready) {
                    _stockError.value = "Couldn't refresh stock. Check the connection."
                } else {
                    _stock.value = StockView.Offline
                }
            }
        }
    }

    fun setSoldOut(item: com.squaretoast.pos.sync.StockItem, soldOut: Boolean) {
        if (item.id in _stockSaving.value) return // one change per item at a time
        val id = runCatching { UUID.fromString(item.id) }.getOrNull() ?: return
        _stockSaving.value = _stockSaving.value + item.id
        _stockError.value = null
        viewModelScope.launch {
            try {
                cloud.setEightySixed(stockLocation(), id, soldOut)
                refreshMenu() // the menu badges follow the server's derived state
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _stockError.value = "Couldn't update ${item.name}. Check the connection and try again."
            } finally {
                _stockSaving.value = _stockSaving.value - item.id
            }
            val failed = _stockError.value
            loadStock()
            if (failed != null) _stockError.value = failed // keep the toggle's error visible
        }
    }

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

    fun select(orderId: String) {
        if (_charging.value) return // never switch checks under a running charge
        _guestCheck.value = null
        selected.value = orderId; _tip.value = 0; _splitWays.value = 1; loadBill()
    }

    // ---- Guest QR orders (plan 17). A local order and a guest check are never selected at once. ----

    private val _guestOrders = MutableStateFlow<List<com.squaretoast.pos.sync.GuestOrder>>(emptyList())
    val guestOrders: StateFlow<List<com.squaretoast.pos.sync.GuestOrder>> = _guestOrders.asStateFlow()
    private val _guestCheck = MutableStateFlow<String?>(null)
    val selectedGuestCheck: StateFlow<String?> = _guestCheck.asStateFlow()
    private val _accepting = MutableStateFlow<Set<String>>(emptySet())
    val accepting: StateFlow<Set<String>> = _accepting.asStateFlow()
    private var guestJob: Job? = null
    private var liveJob: Job? = null

    /** Slice 13: while the POS is on screen, refresh guest orders (and an open guest bill) when the cloud says so. */
    fun startLive() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            // Child of the live job: stopLive() also drops a refresh that is still waiting.
            val refresh = com.squaretoast.pos.sync.Debouncer(this, 300) {
                loadGuestOrders()
                if (com.squaretoast.pos.sync.LiveRefreshRules.reloadBill(_guestCheck.value, _charging.value)) loadBill()
            }
            com.squaretoast.pos.sync.LiveEvents(cloud).run { refresh.poke() }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    fun loadGuestOrders() {
        guestJob?.cancel()
        guestJob = viewModelScope.launch {
            try {
                val fresh = cloud.guestOrders()
                _guestOrders.value = fresh
                // A guest check that is paid or gone leaves the list; stop showing its bill (never mid-charge).
                val open = _guestCheck.value
                if (open != null && !_charging.value && com.squaretoast.pos.order.GuestOrders.chips(fresh).none { it.checkId == open }) {
                    _guestCheck.value = null
                    _bill.value = BillView.None
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: keep what we last saw; QR orders need the cloud anyway.
            }
        }
    }

    fun selectGuestCheck(checkId: String) {
        if (_charging.value) return
        selected.value = null
        _guestCheck.value = checkId; _tip.value = 0; _splitWays.value = 1; loadBill()
    }

    fun accept(order: com.squaretoast.pos.sync.GuestOrder) {
        if (order.orderId in _accepting.value) return // one accept per order at a time
        val id = runCatching { UUID.fromString(order.orderId) }.getOrNull() ?: return
        _accepting.value = _accepting.value + order.orderId
        viewModelScope.launch {
            try {
                cloud.acceptOrder(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RejectedBatchException) {
                _message.value = if (e.status == 409) "${order.table}: couldn't accept. It may already be accepted, " +
                    "or something on it is sold out. Check the list and talk to the guest if needed."
                else "Couldn't accept the order for ${order.table}. Try again."
            } catch (_: Exception) {
                _message.value = "Couldn't accept the order for ${order.table}. Check the connection."
            } finally {
                _accepting.value = _accepting.value - order.orderId
            }
            loadGuestOrders()
        }
    }

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

    private val _splitWays = MutableStateFlow(1)
    val splitWays: StateFlow<Int> = _splitWays.asStateFlow()
    fun setSplitWays(ways: Int) { _splitWays.value = ways.coerceIn(1, 20) }

    private val _charging = MutableStateFlow(false)
    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    private var billJob: Job? = null

    /** Only the latest request may set the bill, so a slow answer for another order never shows (or gets charged). */
    fun loadBill() {
        billJob?.cancel()
        billJob = viewModelScope.launch {
            val checkId = (_guestCheck.value ?: selected.value?.let { orders.checkIdOf(it) })
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
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
    }

    /** Charges the server's amount due plus the chosen tip, by the best available method. One charge at a time. */
    fun charge() {
        val ready = _bill.value as? BillView.Ready ?: return
        if (_charging.value) return
        _charging.value = true
        viewModelScope.launch {
            try {
                choosePaymentMethod().join()
                val c = _choice.value ?: return@launch
                when (c.method) {
                    PaymentMethod.TAP_TO_PAY -> if (payWithTapToPay(ready.checkId, _tip.value).await()) _tip.value = 0
                    PaymentMethod.BLUETOOTH_READER -> _message.value = "Connect the card reader to charge this check."
                    PaymentMethod.QR_PAY_AT_TABLE -> _message.value = "Ask the guest to scan the table QR to pay." +
                        (PaymentLabels.reason(c)?.let { "\n$it" } ?: "")
                }
            } finally {
                _charging.value = false
                loadBill()
            }
        }
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
    /** True if the payment was submitted. A cancelled coroutine cancels cleanly rather than reporting a failure. */
    fun payWithTapToPay(checkId: UUID, tipCents: Long) = viewModelScope.async {
        val ok = try {
            val started = cloud.startCardPayment(checkId, UUID.randomUUID(), "CARD_PRESENT", tipCents)
            terminal.collect(started.clientSecret) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        _message.value = if (ok) "Payment submitted" else "Payment did not complete"
        ok
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

    // ---- Slice 14: offline QR pay code (plan S14-6). The guest pays on their own phone; the POS never sees a card.

    data class PayCode(val url: String, val totalCents: Long, val currency: String, val expiresAtEpochSecond: Long)

    private val _payCode = MutableStateFlow<PayCode?>(null)
    val payCode: StateFlow<PayCode?> = _payCode.asStateFlow()

    fun showPayCode() = viewModelScope.launch {
        val orderId = selected.value ?: return@launch // a guest QR check is paid from the table QR, online
        val stored = db.menuDao().taxProfile()
        val profile = stored?.let { runCatching { it.profile() }.getOrNull() }
        val today = profile?.let { p -> runCatching { java.time.LocalDate.now(java.time.ZoneId.of(p.timezone)) }.getOrNull() }
        if (stored == null || profile == null || today == null ||
            !com.squaretoast.pos.payment.PayCodeGate.allowed(_bill.value !is BillView.Offline, enrolled.value, profile, today)) {
            _message.value = "No pay code: the tax rules on this phone are missing or too old. Connect once to refresh them."
            return@launch
        }
        val order = db.orderDao().order(orderId) ?: return@launch
        val draft = com.squaretoast.pos.payment.ClaimDrafts.draft(order, db.orderDao().lines(orderId), profile, stored.deviceId, today)
        if (draft == null) {
            _message.value = "No pay code: an item on this check has no tax rule on this phone."
            return@launch
        }
        val signed = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.squaretoast.pos.payment.OfflineClaimSigner(KeystoreClaimKey.loadOrCreate(), java.time.Clock.systemUTC()) {
                    UUID.randomUUID().toString()
                }.issue(draft)
            }
        }.getOrElse { if (it is CancellationException) throw it; _message.value = "Couldn't sign the pay code."; return@launch }
        _payCode.value = PayCode(signed.qrUrl(com.squaretoast.pos.BuildConfig.PAY_BASE_URL), draft.totalCents, draft.currency,
            signed.claim.expiresAtEpochSecond)
    }

    fun closePayCode() { _payCode.value = null }

    fun dismissMessage() { _message.value = null }

    private fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}
