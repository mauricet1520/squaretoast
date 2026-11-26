package com.squaretoast.pos.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
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
