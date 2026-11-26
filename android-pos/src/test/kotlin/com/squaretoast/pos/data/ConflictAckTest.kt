package com.squaretoast.pos.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squaretoast.pos.domain.CourseAction
import com.squaretoast.pos.ui.ConflictLabels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** A refused change must not block the POS forever: staff see it in plain words and tap OK. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConflictAckTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OrderRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-26T19:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = OrderRepository(db, clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun placed(table: String = "T1"): PlaceOrder {
        val cmd = PlaceOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-0000000000b1"), table,
            listOf(NewLine(1, 1, "burger", "Burger", 1, 1450), NewLine(1, 2, "fries", "Fries", 1, 550)))
        repo.placeOrder(cmd)
        return cmd
    }

    @Test
    fun okOnARefusedOrderClearsTheAlertAndRemovesTheOrderFromThisPhone() = runBlocking {
        val refused = placed("T1")
        val kept = placed("T2")
        val course = db.orderDao().courses(refused.orderId.toString()).first()
        repo.transitionCourse(UUID.randomUUID(), course.id, CourseAction.FIRE) // queued behind the refused order
        db.outboxDao().markConflict(refused.mutationId.toString(), "INVALID_MODIFIERS")
        assertEquals(1, db.outboxDao().conflicts().first().size)

        repo.acknowledgeConflict(refused.mutationId.toString())

        assertTrue(db.outboxDao().conflicts().first().isEmpty())
        assertNull(db.orderDao().order(refused.orderId.toString()))
        assertTrue(db.orderDao().courses(refused.orderId.toString()).isEmpty())
        assertTrue(db.orderDao().lines(refused.orderId.toString()).isEmpty())
        val rows = db.outboxDao().all().filter { it.aggregateId == refused.orderId.toString() }
        assertEquals(listOf(OutboxState.ACKNOWLEDGED, OutboxState.ACKNOWLEDGED), rows.map { it.state }) // kept, never sent
        assertTrue(db.outboxDao().pending(50).none { it.aggregateId == refused.orderId.toString() })

        assertNotNull(db.orderDao().order(kept.orderId.toString())) // other orders untouched
        assertEquals(OutboxState.PENDING, db.outboxDao().all().single { it.mutationId == kept.mutationId.toString() }.state)
    }

    @Test
    fun okOnARefusedCourseChangeKeepsTheOrder() = runBlocking {
        val order = placed()
        val course = db.orderDao().courses(order.orderId.toString()).first()
        val change = UUID.randomUUID()
        repo.transitionCourse(change, course.id, CourseAction.FIRE)
        db.outboxDao().markConflict(change.toString(), "STALE_STATE")

        repo.acknowledgeConflict(change.toString())

        assertTrue(db.outboxDao().conflicts().first().isEmpty())
        assertNotNull(db.orderDao().order(order.orderId.toString()))
        assertEquals(OutboxState.PENDING, db.outboxDao().all().single { it.mutationId == order.mutationId.toString() }.state)
    }

    @Test
    fun okOnlyActsOnConflicts() = runBlocking {
        val order = placed()

        repo.acknowledgeConflict(order.mutationId.toString()) // still PENDING: nothing to acknowledge

        assertNotNull(db.orderDao().order(order.orderId.toString()))
        assertEquals(OutboxState.PENDING, db.outboxDao().all().single().state)
    }

    @Test
    fun refusalsAreDescribedInPlainWords() = runBlocking {
        val order = placed("T7")
        db.outboxDao().markConflict(order.mutationId.toString(), "INVALID_MODIFIERS")
        val row = db.outboxDao().conflicts().first().single()

        assertEquals("The order for T7 wasn't accepted: an item's options are missing or not allowed.",
            ConflictLabels.describe(row, "T7"))
        for (reason in listOf("OVERSOLD", "INVALID_LINE", "ITEM_NOT_AT_LOCATION", "ITEM_INACTIVE", "STALE_STATE", "SOMETHING_NEW", null)) {
            val text = ConflictLabels.describe(row.copy(lastError = reason), "T7")
            assertFalse("$reason -> $text", text.contains('_'))
        }
        assertEquals("A course change wasn't accepted: the course had already moved on another screen.",
            ConflictLabels.describe(row.copy(type = "COURSE_TRANSITIONED", lastError = "STALE_STATE"), null))
    }
}
