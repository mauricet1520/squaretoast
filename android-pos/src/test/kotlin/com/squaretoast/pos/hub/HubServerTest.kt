package com.squaretoast.pos.hub

import com.squaretoast.pos.domain.CourseState
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class HubServerTest {

    private val tokens = PairingTokens()
    private val board = CourseBoard().apply { register("course-1", CourseState.HOLD) }

    private fun hub(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application { hubModule(board, tokens) }
        block()
    }

    @Test
    fun streamRejectsMissingOrWrongPairingTokens() = hub {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/stream").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/stream?token=nope").status)
    }

    @Test
    fun streamSendsASnapshotThenEveryCourseTransition() = hub {
        val sse = createClient { install(SSE) }
        val connected = CompletableDeferred<Unit>()
        launch {
            connected.await()
            val r = client.post("/api/v1/course/course-1/transition") {
                header(HttpHeaders.Authorization, "Bearer ${tokens.current}")
                contentType(ContentType.Application.Json)
                setBody("""{"action":"FIRE"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }
        withTimeout(10_000) {
            sse.sse("/api/v1/stream?token=${tokens.current}") {
                val events = incoming.take(2).let { flow ->
                    val out = mutableListOf<io.ktor.sse.ServerSentEvent>()
                    flow.collect { e -> out += e; if (out.size == 1) connected.complete(Unit) }
                    out
                }
                assertEquals(listOf("course", "course"), events.map { it.event })
                val snapshot = Json.parseToJsonElement(events[0].data!!).jsonObject
                assertEquals("HOLD", snapshot["state"]!!.jsonPrimitive.content)
                val fired = Json.parseToJsonElement(events[1].data!!).jsonObject
                assertEquals("course-1", fired["courseId"]!!.jsonPrimitive.content)
                assertEquals("FIRED", fired["state"]!!.jsonPrimitive.content)
                assertEquals("1", events[1].id)
            }
        }
    }

    @Test
    fun transitionsRequireTheBearerToken() = hub {
        val r = client.post("/api/v1/course/course-1/transition") {
            contentType(ContentType.Application.Json)
            setBody("""{"action":"FIRE"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(CourseState.HOLD, board.state("course-1"))
    }

    @Test
    fun illegalTransitionsAndUnknownCoursesAreRejected() = hub {
        suspend fun post(course: String, body: String) = client.post("/api/v1/course/$course/transition") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.current}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, post("course-1", """{"action":"SERVE"}""").status)
        assertEquals(HttpStatusCode.NotFound, post("course-404", """{"action":"FIRE"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("course-1", """{"action":"EXPLODE"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("course-1", "not json").status)
        assertEquals(CourseState.HOLD, board.state("course-1"))

        val ok = post("course-1", """{"action":"FIRE"}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("FIRED", Json.parseToJsonElement(ok.bodyAsText()).jsonObject["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun rotatingThePairingTokenRevokesTheOldOne() = hub {
        val old = tokens.current
        val new = tokens.rotate()
        assertNotEquals(old, new)
        assertFalse(tokens.isValid(old))
        assertTrue(tokens.isValid(new))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/stream?token=$old").status)
    }

    @Test
    fun pairingTokensAreLongAndUrlSafe() {
        val t = PairingTokens().current
        assertTrue(t.length >= 32)
        assertEquals(t, URLEncoder.encode(t, "UTF-8"))
        assertFalse(tokens.isValid(null))
        assertFalse(tokens.isValid(""))
    }

    @Test
    fun pairingUrlPointsTheKdsAtTheHub() {
        assertEquals("http://192.168.1.20:8787/kds?pair=abc_-1",
            kdsPairingUrl(host = "192.168.1.20", port = 8787, token = "abc_-1"))
    }
}
