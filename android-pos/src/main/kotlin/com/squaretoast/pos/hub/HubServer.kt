package com.squaretoast.pos.hub

import com.squaretoast.pos.domain.CourseAction
import com.squaretoast.pos.domain.CourseState
import com.squaretoast.pos.domain.CourseStateMachine
import com.squaretoast.pos.domain.IllegalCourseTransitionException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Per-shift secret that KDS browsers and POS peers present to the hub. Rotating it revokes every pairing. */
class PairingTokens(private val random: SecureRandom = SecureRandom()) {
    @Volatile
    var current: String = next()
        private set

    fun rotate(): String = next().also { current = it }

    fun isValid(token: String?): Boolean =
        !token.isNullOrEmpty() && MessageDigest.isEqual(token.toByteArray(), current.toByteArray())

    private fun next(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
}

@Serializable
data class CourseEvent(val hubSeq: Long, val courseId: String, val state: CourseState)

/** The hub's canonical course state for the floor while offline. hubSeq orders events for later reconciliation. */
class CourseBoard {
    private val mutex = Mutex()
    private val states = LinkedHashMap<String, CourseState>()
    private var seq = 0L
    private val flow = MutableSharedFlow<CourseEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<CourseEvent> = flow.asSharedFlow()

    @Synchronized
    fun register(courseId: String, state: CourseState) { states[courseId] = state }

    @Synchronized
    fun state(courseId: String): CourseState? = states[courseId]

    @Synchronized
    fun snapshot(): List<CourseEvent> = states.map { (id, s) -> CourseEvent(seq, id, s) }

    /** Throws NoSuchElementException for an unknown course, IllegalCourseTransitionException for a bad action. */
    suspend fun transition(courseId: String, action: CourseAction): CourseEvent = mutex.withLock {
        val event = synchronized(this) {
            val from = states[courseId] ?: throw NoSuchElementException(courseId)
            val to = CourseStateMachine.next(from, action)
            states[courseId] = to
            CourseEvent(++seq, courseId, to)
        }
        flow.emit(event) // under the mutex, so subscribers see events in hubSeq order
        event
    }
}

@Serializable
private data class TransitionRequest(val action: String)

fun kdsPairingUrl(host: String, port: Int, token: String): String = "http://$host:$port/kds?pair=$token"

private fun CourseEvent.toSse() = ServerSentEvent(data = Json.encodeToString(this), event = "course", id = hubSeq.toString())

/**
 * LAN hub endpoints. The SSE stream takes the pairing token as a query parameter because EventSource cannot send
 * headers; the transition endpoint uses a Bearer header. Tokens are compared in constant time.
 */
fun Application.hubModule(board: CourseBoard, tokens: PairingTokens) {
    install(SSE)
    routing {
        route("/api/v1/stream") {
            install(createRouteScopedPlugin("StreamPairing") {
                onCall { call ->
                    if (!tokens.isValid(call.request.queryParameters["token"])) call.respond(HttpStatusCode.Unauthorized)
                }
            })
            sse {
                board.events
                    .onSubscription { board.snapshot().forEach { send(it.toSse()) } }
                    .collect { send(it.toSse()) }
            }
        }
        post("/api/v1/course/{id}/transition") {
            val header = call.request.headers["Authorization"]
            val bearer = if (header != null && header.startsWith("Bearer ")) header.substring(7) else null
            if (!tokens.isValid(bearer)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            val action = try {
                CourseAction.valueOf(Json.decodeFromString<TransitionRequest>(call.receiveText()).action)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (action == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            try {
                val event = board.transition(call.parameters["id"]!!, action)
                call.respondText(Json.encodeToString(event), ContentType.Application.Json)
            } catch (_: NoSuchElementException) {
                call.respond(HttpStatusCode.NotFound)
            } catch (_: IllegalCourseTransitionException) {
                call.respond(HttpStatusCode.Conflict)
            }
        }
    }
}
