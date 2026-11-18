package com.squaretoast.pos.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squaretoast.pos.data.AppDatabase
import com.squaretoast.pos.data.NewLine
import com.squaretoast.pos.data.OrderRepository
import com.squaretoast.pos.data.OutboxState
import com.squaretoast.pos.data.PlaceOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Clock
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxDrainerTest {

    /** Stands in for the network and the server's dedupe-by-mutationId contract. */
    private class FakeSyncServer : SyncApi {
        val applied = mutableListOf<String>()
        val batches = mutableListOf<List<Mutation>>()
        var loseNextResponse = false
        var offline = false
        val conflictIds = mutableSetOf<String>()

        override suspend fun push(batch: List<Mutation>): List<MutationResult> {
            if (offline) throw IOException("no route to host")
            batches += batch
            val results = batch.map { m ->
                when {
                    m.mutationId in applied -> MutationResult(m.mutationId, MutationStatus.DUPLICATE)
                    m.mutationId in conflictIds -> MutationResult(m.mutationId, MutationStatus.CONFLICT, "OVERSOLD")
                    else -> { applied += m.mutationId; MutationResult(m.mutationId, MutationStatus.APPLIED) }
                }
            }.reversed() // results are matched by mutationId, not position
            if (loseNextResponse) {
                loseNextResponse = false
                throw IOException("connection reset after the server committed")
            }
            return results
        }
    }

    private lateinit var db: AppDatabase
    private lateinit var repo: OrderRepository
    private val server = FakeSyncServer()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = OrderRepository(db, Clock.systemUTC())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun placeOrders(n: Int): List<String> = List(n) {
        val id = UUID.randomUUID()
        repo.placeOrder(PlaceOrder(id, UUID.randomUUID(), UUID.randomUUID(), "T$it",
            listOf(NewLine(1, 1, "fries", "Fries", 1, 650))))
        id.toString()
    }

    @Test
    fun sendsEveryPendingMutationInDeviceOrderInBatchesOfFifty() = runBlocking {
        val ids = placeOrders(120)

        val outcome = OutboxDrainer(db, server).drain()

        assertEquals(DrainOutcome.Drained(sent = 120, conflicts = 0), outcome)
        assertEquals(listOf(50, 50, 20), server.batches.map { it.size })
        assertEquals(ids, server.applied)
        assertEquals(server.batches.flatten().map { it.deviceSeq }, (1L..120L).toList())
        assertTrue(db.outboxDao().all().all { it.state == OutboxState.DONE })
    }

    @Test
    fun aLostResponseIsRetriedAndTheServerAppliesEachMutationOnce() = runBlocking {
        val ids = placeOrders(3)
        server.loseNextResponse = true

        val first = OutboxDrainer(db, server).drain()
        assertTrue(first is DrainOutcome.RetryLater)
        assertTrue(db.outboxDao().all().all { it.state == OutboxState.PENDING && it.attempts == 1 })

        val second = OutboxDrainer(db, server).drain()
        assertEquals(DrainOutcome.Drained(sent = 3, conflicts = 0), second)
        assertEquals(ids, server.applied)
        assertEquals(2, server.batches.size)
        assertTrue(db.outboxDao().all().all { it.state == OutboxState.DONE })
    }

    @Test
    fun offlineLeavesRowsPendingForTheNextAttempt() = runBlocking {
        placeOrders(2)
        server.offline = true

        val outcome = OutboxDrainer(db, server).drain()

        assertTrue(outcome is DrainOutcome.RetryLater)
        assertTrue(db.outboxDao().all().all { it.state == OutboxState.PENDING && it.attempts == 1 })
    }

    @Test
    fun conflictsAreKeptSurfacedAndNotResent() = runBlocking {
        val ids = placeOrders(3)
        server.conflictIds += ids[1]

        val outcome = OutboxDrainer(db, server).drain()

        assertEquals(DrainOutcome.Drained(sent = 3, conflicts = 1), outcome)
        val conflicts = db.outboxDao().conflicts().first()
        assertEquals(listOf(ids[1]), conflicts.map { it.mutationId })
        assertEquals("OVERSOLD", conflicts.single().lastError)

        OutboxDrainer(db, server).drain()
        assertEquals(1, server.batches.size)
    }

    @Test
    fun rowsLeftInFlightByAKilledProcessAreResent() = runBlocking {
        val ids = placeOrders(2)
        db.outboxDao().setState(ids[0], OutboxState.INFLIGHT)

        val outcome = OutboxDrainer(db, server).drain()

        assertEquals(DrainOutcome.Drained(sent = 2, conflicts = 0), outcome)
        assertEquals(ids, server.applied)
    }

    @Test
    fun nothingPendingSendsNothing() = runBlocking {
        assertEquals(DrainOutcome.Drained(sent = 0, conflicts = 0), OutboxDrainer(db, server).drain())
        assertTrue(server.batches.isEmpty())
    }
}
