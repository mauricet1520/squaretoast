package com.squaretoast.pos.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import java.io.IOException
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

/**
 * Client for the cloud API. The POS never sends a charge amount: the server computes the check balance, so a
 * tampered client can only choose the tip. Endpoints and device credentials land with the backend in Slice 5b.
 */
class CloudApi(
    private val baseUrl: String,
    engine: HttpClient,
    private val deviceCredential: () -> String?,
) : SyncApi {

    init { require(baseUrl.startsWith("https://")) { "the cloud API is https only" } }

    private val http = engine.config { install(ContentNegotiation) { json() } }

    override suspend fun push(batch: List<Mutation>): List<MutationResult> =
        post("/api/v1/sync/mutations", MutationBatch(batch)).body<MutationResults>().results

    suspend fun connectionToken(): String = post("/api/v1/terminal/connection_token", Unit).body<ConnectionToken>().secret

    suspend fun startCardPayment(checkId: UUID, idempotencyKey: UUID, method: String, tipCents: Long): StartedCardPayment {
        require(tipCents >= 0) { "tip must not be negative" }
        return post("/api/v1/checks/$checkId/payments", StartCardPayment(idempotencyKey.toString(), method, tipCents)).body()
    }

    private suspend inline fun <reified T> post(path: String, body: T) = http.post(baseUrl + path) {
        deviceCredential()?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }.also { if (!it.status.isSuccess()) throw IOException("cloud returned ${it.status.value}") }
}
