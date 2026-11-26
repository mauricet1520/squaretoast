package com.squaretoast.pos.data

import androidx.room.withTransaction
import com.squaretoast.pos.domain.CourseAction
import com.squaretoast.pos.domain.CourseState
import com.squaretoast.pos.domain.CourseStateMachine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID

data class NewLine(
    val seat: Int,
    val course: Int,
    val menuItemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Long,
    /** Modifier option IDs only; the server names and prices them. */
    val optionIds: List<String> = emptyList(),
)

/** mutationId is created once per user action, so a retried command can never be applied twice. */
data class PlaceOrder(
    val mutationId: UUID,
    val orderId: UUID,
    val locationId: UUID,
    val tableLabel: String,
    val lines: List<NewLine>,
)

@Serializable
private data class OrderPlacedPayload(
    val orderId: String,
    val locationId: String,
    val tableLabel: String,
    val lines: List<LinePayload>,
    val checkId: String,
)

@Serializable
private data class LinePayload(
    val lineId: String,
    val courseId: String,
    val course: Int,
    val seat: Int,
    val menuItemId: String,
    val quantity: Int,
    val unitPriceCents: Long,
)

@Serializable
private data class CourseTransitionPayload(val courseId: String, val action: String, val from: String, val to: String)

/** Every command writes its domain rows and its outbox row in one Room transaction. */
class OrderRepository(private val db: AppDatabase, private val clock: Clock) {

    suspend fun placeOrder(cmd: PlaceOrder) {
        require(cmd.lines.isNotEmpty()) { "an order needs at least one line" }
        for (l in cmd.lines) {
            require(l.quantity > 0) { "quantity must be positive" }
            require(l.unitPriceCents >= 0) { "price must not be negative" }
            require(l.seat > 0) { "seats start at 1" }
            require(l.course > 0) { "courses start at 1" }
        }
        val orderId = cmd.orderId.toString()
        val courseIds = cmd.lines.map { it.course }.distinct().sorted().associateWith { UUID.randomUUID().toString() }
        val lines = cmd.lines.map { l ->
            OrderLineEntity(UUID.randomUUID().toString(), orderId, courseIds.getValue(l.course), l.seat, l.menuItemId,
                l.name, l.quantity, l.unitPriceCents)
        }
        val checkId = UUID.randomUUID().toString()
        val payload = OrderPlacedPayload(orderId, cmd.locationId.toString(), cmd.tableLabel, cmd.lines.zip(lines) { n, l ->
            LinePayload(l.id, l.courseId, n.course, l.seat, l.menuItemId, l.quantity, l.unitPriceCents)
        }, checkId)

        db.withTransaction {
            db.orderDao().insertOrder(OrderEntity(orderId, cmd.locationId.toString(), cmd.tableLabel, clock.millis(), checkId))
            db.orderDao().insertCourses(courseIds.map { (n, id) -> CourseEntity(id, orderId, n, CourseState.HOLD) })
            db.orderDao().insertLines(lines)
            enqueue(cmd.mutationId, orderId, "ORDER_PLACED", Json.encodeToString(payload))
        }
    }

    /**
     * Places the order unless one with this orderId is already on the device. True if this call placed it. Safe
     * to call again after a double tap or a crash mid-send.
     */
    suspend fun placeOrderOnce(cmd: PlaceOrder): Boolean = db.withTransaction {
        // Room transactions are exclusive, so the check and the insert are one step even across repository instances.
        if (db.orderDao().order(cmd.orderId.toString()) != null) return@withTransaction false
        placeOrder(cmd)
        true
    }

    /** The check ID to sign an offline claim with, so the claim and the synced check are the same check. */
    suspend fun checkIdOf(orderId: String): String? = db.orderDao().order(orderId)?.checkId

    suspend fun transitionCourse(mutationId: UUID, courseId: String, action: CourseAction): CourseState =
        db.withTransaction {
            val course = checkNotNull(db.orderDao().course(courseId)) { "unknown course" }
            val to = CourseStateMachine.next(course.state, action)
            db.orderDao().setCourseState(courseId, to)
            enqueue(mutationId, course.orderId, "COURSE_TRANSITIONED",
                Json.encodeToString(CourseTransitionPayload(courseId, action.name, course.state.name, to.name)))
            to
        }

    private suspend fun enqueue(mutationId: UUID, aggregateId: String, type: String, payload: String) {
        val seq = db.outboxDao().maxSeq() + 1
        db.outboxDao().insert(OutboxEntity(mutationId.toString(), aggregateId, type, payload, seq, clock.millis()))
    }
}
