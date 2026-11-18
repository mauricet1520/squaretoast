package com.squaretoast.pos.hub

import com.squaretoast.pos.domain.CourseAction
import com.squaretoast.pos.domain.CourseState
import com.squaretoast.pos.domain.CourseStateMachine
import com.squaretoast.pos.domain.IllegalCourseTransitionException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
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

    /** Taken under the transition mutex, so a new subscriber never sees a hubSeq older than an emitted event. */
    suspend fun snapshot(): List<CourseEvent> = mutex.withLock { synchronized(this) { states.map { (id, s) -> CourseEvent(seq, id, s) } } }

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

/** Reads a file of the KDS web bundle (APK `assets/kds/`); null when it does not exist. */
fun interface KdsAssets {
    fun open(path: String): ByteArray?
}

private val ASSET_SEGMENT = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")

/** Joins decoded path segments, or null if any segment could leave the bundle or is not a plain file name. */
internal fun safeAssetPath(segments: List<String>): String? =
    if (segments.all { it.matches(ASSET_SEGMENT) && !it.contains("..") }) segments.joinToString("/") else null

private val KDS_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
    "connect-src 'self'; media-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

private fun contentTypeOf(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
    "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
    "js", "mjs" -> ContentType("text", "javascript").withCharset(Charsets.UTF_8)
    "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
    "json", "webmanifest" -> ContentType.Application.Json
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "ogg" -> ContentType("audio", "ogg")
    else -> ContentType.Application.OctetStream
}

/** Serves one bundle file; a path without an extension is a client-side route and gets index.html. */
private suspend fun ApplicationCall.serveKds(assets: KdsAssets, segments: List<String>) {
    response.headers.append("Content-Security-Policy", KDS_CSP)
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
    val path = safeAssetPath(segments.filter { it.isNotEmpty() })
    if (path == null) {
        respond(HttpStatusCode.BadRequest)
        return
    }
    val wanted = path.ifEmpty { "index.html" }
    val bytes = assets.open(wanted)
    when {
        bytes != null -> respondBytes(bytes, contentTypeOf(wanted))
        !wanted.substringAfterLast('/').contains('.') ->
            assets.open("index.html")?.let { respondBytes(it, contentTypeOf("index.html")) } ?: respond(HttpStatusCode.NotFound)
        else -> respond(HttpStatusCode.NotFound)
    }
}

/**
 * LAN hub endpoints. The SSE stream takes the pairing token as a query parameter because EventSource cannot send
 * headers; the transition endpoint uses a Bearer header. Tokens are compared in constant time. The KDS web bundle
 * is served at `/kds` (its pages) and `/assets` (its hashed files), so kitchen screens work with no internet.
 */
fun Application.hubModule(board: CourseBoard, tokens: PairingTokens, assets: KdsAssets = KdsAssets { null }) {
    install(SSE)
    routing {
        get("/kds") { call.serveKds(assets, emptyList()) }
        get("/kds/{path...}") { call.serveKds(assets, call.parameters.getAll("path").orEmpty()) }
        get("/assets/{path...}") { call.serveKds(assets, listOf("assets") + call.parameters.getAll("path").orEmpty()) }
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
