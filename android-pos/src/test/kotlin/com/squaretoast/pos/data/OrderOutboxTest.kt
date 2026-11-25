package com.squaretoast.pos.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squaretoast.pos.domain.CourseAction
import com.squaretoast.pos.domain.CourseState
import com.squaretoast.pos.domain.IllegalCourseTransitionException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OrderOutboxTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OrderRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-18T19:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = OrderRepository(db, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun order(mutationId: UUID = UUID.randomUUID(), orderId: UUID = UUID.randomUUID()) = PlaceOrder(
        mutationId = mutationId,
        orderId = orderId,
        locationId = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        tableLabel = "T12",
        lines = listOf(
            NewLine(seat = 1, course = 1, menuItemId = "burrata", name = "Burrata", quantity = 1, unitPriceCents = 1600),
            NewLine(seat = 2, course = 2, menuItemId = "steak", name = "Hanger steak", quantity = 2, unitPriceCents = 3150),
        ),
    )

    @Test
    fun placingAnOrderWritesTheOrderItsCoursesLinesAndOneOutboxRowTogether() = runBlocking {
        val cmd = order()
        repo.placeOrder(cmd)

        val saved = db.orderDao().order(cmd.orderId.toString())!!
        assertEquals("T12", saved.tableLabel)
        assertEquals(2, db.orderDao().lines(cmd.orderId.toString()).size)
        val courses = db.orderDao().courses(cmd.orderId.toString())
        assertEquals(listOf(1, 2), courses.map { it.number })
        assertTrue(courses.all { it.state == CourseState.HOLD })

        val row = db.outboxDao().all().single()
        assertEquals(cmd.mutationId.toString(), row.mutationId)
        assertEquals(cmd.orderId.toString(), row.aggregateId)
        assertEquals("ORDER_PLACED", row.type)
        assertEquals(OutboxState.PENDING, row.state)
        assertEquals(clock.millis(), row.createdAt)
        val payload = Json.parseToJsonElement(row.payloadJson).jsonObject
        assertEquals(cmd.orderId.toString(), payload["orderId"]!!.jsonPrimitive.content)
        assertEquals(2, payload["lines"]!!.jsonArray.size)
    }

    @Test
    fun eachOrderGetsItsOwnCheckIdAndSendsItWithTheOrder() = runBlocking {
        val first = order()
        val second = order()
        repo.placeOrder(first)
        repo.placeOrder(second)

        val checkId = db.orderDao().order(first.orderId.toString())!!.checkId!!
        assertEquals(checkId, UUID.fromString(checkId).toString())
        assertEquals(checkId, repo.checkIdOf(first.orderId.toString()))
        val payload = Json.parseToJsonElement(db.outboxDao().all().first().payloadJson).jsonObject
        assertEquals(checkId, payload["checkId"]!!.jsonPrimitive.content)
        assertTrue(checkId != db.orderDao().order(second.orderId.toString())!!.checkId)
    }

    @Test
    fun aFailedOutboxWriteRollsBackTheWholeOrder() = runBlocking {
        val mutationId = UUID.randomUUID()
        repo.placeOrder(order(mutationId = mutationId))
        val second = order(mutationId = mutationId) // reused mutation id -> the outbox insert violates its primary key

        assertThrows(SQLiteConstraintException::class.java) { runBlocking { repo.placeOrder(second) } }

        assertNull(db.orderDao().order(second.orderId.toString()))
        assertTrue(db.orderDao().lines(second.orderId.toString()).isEmpty())
        assertTrue(db.orderDao().courses(second.orderId.toString()).isEmpty())
        assertEquals(1, db.outboxDao().all().size)
    }

    @Test
    fun deviceSequenceIsStrictlyIncreasingAcrossCommands() = runBlocking {
        repeat(3) { repo.placeOrder(order()) }
        assertEquals(listOf(1L, 2L, 3L), db.outboxDao().all().map { it.deviceSeq })
    }

    @Test
    fun invalidOrdersWriteNothing() = runBlocking {
        val base = order()
        for (bad in listOf(
            base.copy(lines = emptyList()),
            base.copy(lines = listOf(base.lines[0].copy(quantity = 0))),
            base.copy(lines = listOf(base.lines[0].copy(unitPriceCents = -1))),
            base.copy(lines = listOf(base.lines[0].copy(seat = 0))),
            base.copy(lines = listOf(base.lines[0].copy(course = 0))),
        )) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.placeOrder(bad) } }
        }
        assertTrue(db.outboxDao().all().isEmpty())
        assertNull(db.orderDao().order(base.orderId.toString()))
    }

    @Test
    fun courseTransitionsUpdateStateAndEnqueueAMutationAtomically() = runBlocking {
        val cmd = order()
        repo.placeOrder(cmd)
        val course = db.orderDao().courses(cmd.orderId.toString()).first { it.number == 1 }

        val fireId = UUID.randomUUID()
        assertEquals(CourseState.FIRED, repo.transitionCourse(fireId, course.id, CourseAction.FIRE))
        assertEquals(CourseState.FIRED, db.orderDao().course(course.id)!!.state)
        val row = db.outboxDao().all().last()
        assertEquals(fireId.toString(), row.mutationId)
        assertEquals("COURSE_TRANSITIONED", row.type)
        assertEquals(cmd.orderId.toString(), row.aggregateId)
        val payload = Json.parseToJsonElement(row.payloadJson).jsonObject
        assertEquals(course.id, payload["courseId"]!!.jsonPrimitive.content)
        assertEquals("FIRE", payload["action"]!!.jsonPrimitive.content)
        assertEquals("FIRED", payload["to"]!!.jsonPrimitive.content)

        assertThrows(IllegalCourseTransitionException::class.java) {
            runBlocking { repo.transitionCourse(UUID.randomUUID(), course.id, CourseAction.SERVE) }
        }
        assertEquals(CourseState.FIRED, db.orderDao().course(course.id)!!.state)
        assertEquals(2, db.outboxDao().all().size)
    }
}
