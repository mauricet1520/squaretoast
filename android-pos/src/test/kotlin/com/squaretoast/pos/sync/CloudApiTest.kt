package com.squaretoast.pos.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

class CloudApiTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun api(credential: String?, reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        CloudApi("https://api.test", HttpClient(MockEngine { req -> requests += req; reply(req) })) { credential }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun bodyOf(req: HttpRequestData) = Json.parseToJsonElement((req.body as TextContent).text).jsonObject

    @Test
    fun enrollSendsTheCodeAndPublicKeyWithoutACredential() = runBlocking {
        val spki = byteArrayOf(1, 2, 3, 4)
        val api = api(null) {
            json("""{"deviceId":"d-1","locationId":"l-1","deviceToken":"tok-xyz"}""")
        }

        val e = api.enroll("ENR-123", spki, "Bar tablet")

        assertEquals(Enrollment("d-1", "l-1", "tok-xyz"), e)
        val req = requests.single()
        assertEquals("https://api.test/api/v1/devices/enroll", req.url.toString())
        assertNull(req.headers[HttpHeaders.Authorization])
        val b = bodyOf(req)
        assertEquals("ENR-123", b["code"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(spki), b["publicKeySpki"]!!.jsonPrimitive.content)
        assertEquals("Bar tablet", b["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun meCarriesTheBearerAndReadsTheTerminalLocation() = runBlocking {
        val api = api("tok-xyz") { json("""{"deviceId":"d-1","locationId":"l-1","terminalLocationId":"tml_1"}""") }

        assertEquals(DeviceInfo("d-1", "l-1", "tml_1"), api.me())
        assertEquals("Bearer tok-xyz", requests.single().headers[HttpHeaders.Authorization])
        assertEquals("https://api.test/api/v1/devices/me", requests.single().url.toString())
    }

    @Test
    fun aLocationWithoutTerminalSetupHasNoTerminalLocation() = runBlocking {
        val api = api("tok") { json("""{"deviceId":"d-1","locationId":"l-1","terminalLocationId":null}""") }
        assertNull(api.me().terminalLocationId)
    }

    @Test
    fun syncAndPaymentsCarryTheBearer() = runBlocking {
        val api = api("tok-xyz") { req ->
            when (req.url.encodedPath) {
                "/api/v1/sync/mutations" -> json("""{"results":[{"mutationId":"m1","status":"APPLIED"}]}""")
                else -> json("""{"paymentId":"p1","clientSecret":"pi_1_secret"}""")
            }
        }

        val results = api.push(listOf(Mutation("m1", "o1", "ORDER_PLACED", 1, "{}")))
        val started = api.startCardPayment(UUID.randomUUID(), UUID.randomUUID(), "CARD_PRESENT", 250)

        assertEquals(listOf(MutationResult("m1", MutationStatus.APPLIED)), results)
        assertEquals("pi_1_secret", started.clientSecret)
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer tok-xyz" })
        val pay = bodyOf(requests[1])
        assertEquals("250", pay["tipCents"]!!.jsonPrimitive.content)
        assertTrue("the POS never sends an amount", pay.keys.none { it.contains("amount", ignoreCase = true) })
    }

    @Test
    fun nothingIsSentWithoutACredential() {
        val api = api(null) { json("{}") }
        val e = assertThrows(RejectedBatchException::class.java) { runBlocking { api.me() } }
        assertEquals(401, e.status)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun httpErrorsCarryTheirStatus() {
        val api = api("tok") { json("{}", HttpStatusCode.Conflict) }
        val e = assertThrows(RejectedBatchException::class.java) { runBlocking { api.connectionToken() } }
        assertEquals(409, e.status)
    }

    @Test
    fun theCloudIsHttpsOnly() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudApi("http://api.test", HttpClient(MockEngine { respond("") })) { "tok" }
        }
    }

    @Test
    fun menuIsTheDevicesOwnLocationWithItsToken() = runBlocking {
        val api = api("tok") {
            json("""{"items":[{"id":"i-1","name":"Burger","priceCents":1299,"currency":"USD","available":false,
                "modifierGroups":[{"id":"g-1","name":"Cook","minSelect":1,"maxSelect":1,"options":[]}]}]}""")
        }

        val menu = api.menu("l-1")

        val req = requests.single()
        assertEquals("https://api.test/api/v1/locations/l-1/menu", req.url.toString())
        assertEquals("GET", req.method.value)
        assertEquals("Bearer tok", req.headers[HttpHeaders.Authorization])
        val item = menu.items.single()
        assertEquals(1299L, item.priceCents)
        assertEquals(false, item.available)
        assertEquals(1, item.modifierGroups.single().minSelect)
    }
}
