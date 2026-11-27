package com.squaretoast.pos.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 13: the POS listens to its location's stream and refreshes guest orders when they change. */
class LiveEventsTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val sleeps = mutableListOf<Long>()
    private var tickets = 0

    /** Each stream request gets the next body; once they run out the stream answers 503. */
    private fun api(ticketStatus: HttpStatusCode = HttpStatusCode.OK, vararg streams: String): CloudApi {
        var served = 0
        val engine = MockEngine { req ->
            synchronized(requests) { requests += req }
            if (req.url.encodedPath.endsWith("/kds/stream-tickets")) {
                tickets++
                respond("""{"ticket":"tk-$tickets","expiresInSeconds":60}""", ticketStatus,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            } else if (served < streams.size) {
                respond(streams[served++], HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.ServiceUnavailable)
            }
        }
        return CloudApi("https://api.test", HttpClient(engine)) { "tok" }
    }

    private fun streamRequests() = requests.filter { it.url.encodedPath.endsWith("/kds/stream") }

    @Test
    fun onlyOrderCourseAndCheckEventsAndEachConnectReachTheCallback() = runBlocking {
        val api = api(streams = arrayOf(listOf(
            "retry: 3000\n\n",
            "id: 1\nevent: order\ndata: {\"orderId\":\"o-1\"}\n\n",
            "id: 2\nevent: availability\ndata: {}\n\n",
            ": heartbeat\n\n",
            "id: 3\nevent: check\ndata: {\"checkId\":\"c-1\"}\n\n",
            "id: 4\nevent: course\ndata: {}\n\n",
        ).joinToString("")))
        val seen = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        val job = launch {
            LiveEvents(api, sleep = { sleeps += it }).run { reason ->
                seen += reason
                if (seen.size == 4) done.complete(Unit)
            }
        }
        withTimeout(5_000) { done.await() }
        job.cancel()
        assertEquals(listOf(LiveEvents.CONNECTED, "order", "check", "course"), seen)
        val first = streamRequests().first()
        assertEquals("tk-1", first.url.parameters["ticket"])
        assertNull(first.url.parameters["lastEventId"])
        assertNull(first.headers[HttpHeaders.Authorization]) // the one-time ticket is the credential, not the token
    }

    @Test
    fun aDroppedStreamReconnectsWithAFreshTicketAndTheLastEventId() = runBlocking {
        val api = api(streams = arrayOf(
            "id: 7\nevent: order\ndata: {}\n\n",
            "id: 8\nevent: check\ndata: {}\n\n",
        ))
        val seen = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        val job = launch {
            LiveEvents(api, sleep = { sleeps += it }).run { reason ->
                seen += reason
                if (reason == "check") done.complete(Unit)
            }
        }
        withTimeout(5_000) { done.await() }
        job.cancel()
        val second = streamRequests()[1]
        assertEquals("tk-2", second.url.parameters["ticket"])
        assertEquals("7", second.url.parameters["lastEventId"])
        assertEquals(listOf(LiveEvents.CONNECTED, "order", LiveEvents.CONNECTED, "check"), seen)
        assertEquals(1_000L, sleeps.first()) // a stream that delivered events starts the back-off over
    }

    @Test
    fun aRevokedDeviceStopsListening() = runBlocking {
        val api = api(ticketStatus = HttpStatusCode.Unauthorized)
        withTimeout(5_000) { LiveEvents(api, sleep = { sleeps += it }).run { } } // returns instead of retrying
        assertEquals(1, tickets)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun backOffDoublesAndCapsAtThirtySeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(LiveEvents::backoffMillis))
    }

    @Test
    fun failuresBackOffLongerEachTime() = runBlocking {
        val api = api() // every stream answers 503
        val job = launch { LiveEvents(api, sleep = { sleeps += it; if (sleeps.size == 3) throw kotlinx.coroutines.CancellationException() }).run { } }
        withTimeout(5_000) { job.join() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeps)
    }

    @Test
    fun aStreamThatOpensAndDropsAtOnceStillBacksOffLonger() = runBlocking {
        val api = api(streams = arrayOf("", "", "")) // accepted, then closed with no events
        val job = launch {
            LiveEvents(api, sleep = { sleeps += it; if (sleeps.size == 3) throw kotlinx.coroutines.CancellationException() },
                clock = { 0L }).run { }
        }
        withTimeout(5_000) { job.join() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeps)
    }

    @Test
    fun aStreamThatStayedOpenAWhileStartsTheBackOffOver() = runBlocking {
        var now = 0L
        val api = api(streams = arrayOf("", "", ""))
        val job = launch {
            LiveEvents(api, sleep = { sleeps += it; if (sleeps.size == 3) throw kotlinx.coroutines.CancellationException() },
                clock = { now.also { now += LiveEvents.HEALTHY_MILLIS } }).run { } // each open lasts 10 s
        }
        withTimeout(5_000) { job.join() }
        assertEquals(listOf(1_000L, 1_000L, 1_000L), sleeps)
    }

    @Test
    fun aRefusedStreamTicketIsRetriedNotTreatedAsARevokedDevice() = runBlocking {
        val engine = MockEngine { req ->
            synchronized(requests) { requests += req }
            if (req.url.encodedPath.endsWith("/kds/stream-tickets")) {
                tickets++
                respond("""{"ticket":"tk-$tickets","expiresInSeconds":60}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("", HttpStatusCode.Unauthorized)
            }
        }
        var rejected = false
        val api = CloudApi("https://api.test", HttpClient(engine), { "tok" }, { rejected = true })
        val job = launch { LiveEvents(api, sleep = { sleeps += it; if (sleeps.size == 2) throw kotlinx.coroutines.CancellationException() }).run { } }
        withTimeout(5_000) { job.join() }
        assertEquals(2, tickets)
        assertEquals(false, rejected) // the device token was never refused
    }
}
