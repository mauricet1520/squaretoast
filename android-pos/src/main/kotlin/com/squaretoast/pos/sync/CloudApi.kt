package com.squaretoast.pos.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

@Serializable
private data class MutationBatch(val mutations: List<Mutation>)

@Serializable
private data class MutationResults(val results: List<MutationResult>)

@Serializable
private data class ConnectionToken(val secret: String)

@Serializable
private data class StartCardPayment(val idempotencyKey: String, val method: String, val tipCents: Long)

@Serializable
data class StartedCardPayment(val paymentId: String, val clientSecret: String)

@Serializable
private data class EnrollRequest(val code: String, val publicKeySpki: String, val label: String)

/** The device token is shown once; the caller must store it encrypted and never log it. */
@Serializable
data class Enrollment(val deviceId: String, val locationId: String, val deviceToken: String) {
    override fun toString() = "Enrollment(deviceId=$deviceId, locationId=$locationId, deviceToken=***)"
}

@Serializable
data class DeviceInfo(val deviceId: String, val locationId: String, val terminalLocationId: String? = null)

/**
 * Client for the cloud API. The POS never sends a charge amount: the server computes the check balance, so a
 * tampered client can only choose the tip. Every call but enrollment carries the device token, and none is
 * made without one.
 */
class CloudApi(
    private val baseUrl: String,
    engine: HttpClient,
    private val deviceCredential: () -> String?,
    /** Called with the token we sent when the cloud answers 401 to it: the device was revoked or re-enrolled. */
    private val onRejectedCredential: (String) -> Unit,
) : SyncApi, MenuApi {

    constructor(baseUrl: String, engine: HttpClient, deviceCredential: () -> String?) :
        this(baseUrl, engine, deviceCredential, { _ -> })

    init { require(baseUrl.startsWith("https://")) { "the cloud API is https only" } }

    private val http = engine.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout)
    }

    /** Public endpoint: the one-time code is the credential. */
    suspend fun enroll(code: String, publicKeySpki: ByteArray, label: String): Enrollment =
        http.post(baseUrl + "/api/v1/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(EnrollRequest(code, Base64.getEncoder().encodeToString(publicKeySpki), label))
        }.also { if (!it.status.isSuccess()) throw RejectedBatchException(it.status.value) }.body()

    override suspend fun me(): DeviceInfo =
        http.get(baseUrl + "/api/v1/devices/me") { authenticate() }.checked().body()

    override suspend fun push(batch: List<Mutation>): List<MutationResult> =
        post("/api/v1/sync/mutations", MutationBatch(batch)).body<MutationResults>().results

    override suspend fun menu(locationId: String): MenuDto {
        require(locationId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "not a location id" } // it goes into the path
        return http.get(baseUrl + "/api/v1/locations/$locationId/menu") { authenticate() }.checked().body()
    }

    /** The check's bill as the server computes it. 404 (RejectedBatchException) until the order has synced. */
    suspend fun bill(checkId: UUID): Bill =
        http.get(baseUrl + "/api/v1/checks/$checkId/bill") { authenticate() }.checked().body()

    /** Slice 11: the location's stock for the "Sold out" list. */
    suspend fun stock(locationId: String): List<StockItem> {
        require(locationId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "not a location id" }
        return http.get(baseUrl + "/api/v1/locations/$locationId/inventory") { authenticate() }.checked().body<StockItems>().items
    }

    /** Slice 11: 86 a stock item, or bring it back. The server broadcasts the change to every screen. */
    suspend fun setEightySixed(locationId: String, stockId: UUID, eightySixed: Boolean) {
        require(locationId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "not a location id" }
        http.put(baseUrl + "/api/v1/locations/$locationId/inventory/$stockId/eighty-six") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(EightySixRequest(eightySixed))
        }.checked()
    }

    /** Slice 12: the device's location's guest QR orders from the last 12 hours, newest first. */
    suspend fun guestOrders(): List<GuestOrder> =
        http.get(baseUrl + "/api/v1/guest-orders") { authenticate() }.checked().body<GuestOrderList>().orders

    /** Slice 12: staff accept a waiting guest order; the server commits it, takes stock and opens its check. */
    suspend fun acceptOrder(orderId: UUID) {
        post("/api/v1/orders/$orderId/commit", Unit)
    }


    /** Slice 13: a one-time ticket for the location's live stream (the stream itself takes no bearer token). */
    suspend fun streamTicket(): String = post("/api/v1/kds/stream-tickets", Unit).body<StreamTicket>().ticket

    /**
     * Slice 13: opens the live stream with [ticket], calls [onOpen] once the server accepts it, then hands each event
     * to [onEvent] until the stream ends. The server sends nothing while idle, so a quiet stream times out after
     * [STREAM_IDLE_MS] and the caller reconnects (cheap, and it notices a dead Wi-Fi link).
     */
    suspend fun liveStream(ticket: String, lastEventId: Long?, onOpen: suspend () -> Unit, onEvent: suspend (SseEvent) -> Unit) {
        http.prepareGet(baseUrl + "/api/v1/kds/stream") {
            parameter("ticket", ticket)
            lastEventId?.let { parameter("lastEventId", it) }
            accept(ContentType.Text.EventStream)
            timeout { socketTimeoutMillis = STREAM_IDLE_MS }
        }.execute { response ->
            // Not RejectedBatchException: a refused ticket (expired, server restarted) is retried, not a revoked device.
            if (!response.status.isSuccess()) throw java.io.IOException("live stream returned ${response.status.value}")
            onOpen()
            val channel = response.bodyAsChannel()
            val parser = SseParser()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                parser.line(line)?.let { onEvent(it) }
            }
        }
    }

    companion object {
        const val STREAM_IDLE_MS = 120_000L
    }
    suspend fun connectionToken(): String = post("/api/v1/terminal/connection_token", Unit).body<ConnectionToken>().secret

    suspend fun startCardPayment(checkId: UUID, idempotencyKey: UUID, method: String, tipCents: Long): StartedCardPayment {
        require(tipCents >= 0) { "tip must not be negative" }
        return post("/api/v1/checks/$checkId/payments", StartCardPayment(idempotencyKey.toString(), method, tipCents)).body()
    }

    /** Fail closed: never call the cloud unauthenticated. 401 is retryable, so the outbox waits for a credential. */
    private fun HttpRequestBuilder.authenticate() = bearerAuth(deviceCredential() ?: throw RejectedBatchException(401))

    /** Only for authenticated calls: a 401 here means the token this request sent is no longer accepted. */
    private fun io.ktor.client.statement.HttpResponse.checked() = also {
        if (status.value == 401) {
            request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.let(onRejectedCredential)
        }
        if (!status.isSuccess()) throw RejectedBatchException(status.value)
    }

    private suspend inline fun <reified T> post(path: String, body: T) = http.post(baseUrl + path) {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(body)
    }.checked()
}

@Serializable
data class BillLine(val name: String, val qty: Int, val modifiers: List<String> = emptyList(), val amountCents: Long)

/** The server's bill for a check; the POS only displays it. */
@Serializable
data class Bill(
    val checkId: String,
    val status: String,
    val currency: String,
    val subtotalCents: Long,
    val serviceChargeCents: Long,
    val taxCents: Long,
    val paidCents: Long,
    val dueCents: Long,
    val lines: List<BillLine>,
)

/** One stock item the POS can 86 (Slice 11). `onHand` is null when the item is not counted. */
@Serializable
data class StockItem(val id: String, val name: String, val tracked: Boolean, val onHand: Int? = null, val eightySixed: Boolean)

@Serializable
private data class StockItems(val items: List<StockItem>)

@Serializable
private data class EightySixRequest(val eightySixed: Boolean)

/** Slice 12: one check of an accepted guest QR order, with the server's amount due. */
@Serializable
data class GuestCheck(val checkId: String, val label: String, val status: String, val dueCents: Long, val currency: String)

/** Slice 12: a guest QR order. WAITING ones need staff to accept them; ACCEPTED ones have checks. */
@Serializable
data class GuestOrder(
    val orderId: String,
    val table: String,
    val openedAt: String,
    val status: String,
    val items: List<String> = emptyList(),
    val checks: List<GuestCheck> = emptyList(),
)

@Serializable
private data class GuestOrderList(val orders: List<GuestOrder>)

@Serializable
private data class StreamTicket(val ticket: String, val expiresInSeconds: Int = 60)
