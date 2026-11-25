package com.squaretoast.pos.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squaretoast.pos.data.AppDatabase
import com.squaretoast.pos.data.OutboxEntity
import com.squaretoast.pos.data.OutboxState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A revoked device token: the POS forgets it and asks for enrollment again, without losing unsynced work. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncAuthTest {

    private var stored: String? = "tok-revoked"
    private val session = DeviceSession(token = { stored }, clear = { stored = null })

    private fun api(status: HttpStatusCode) = CloudApi(
        "https://api.test",
        HttpClient(MockEngine { respond("""{"code":"UNAUTHORIZED"}""", status, headersOf(HttpHeaders.ContentType, "application/json")) }),
        session::token,
        onRejectedCredential = session::rejected,
    )

    @Test
    fun aRejectedTokenIsClearedAndTheSessionAsksForEnrollment() = runBlocking {
        assertTrue(session.enrolled.value)

        val e = assertThrows(RejectedBatchException::class.java) { runBlocking { api(HttpStatusCode.Unauthorized).me() } }

        assertEquals(401, e.status)
        assertEquals(null, stored)
        assertFalse(session.enrolled.value)
    }

    @Test
    fun otherFailuresKeepTheToken() = runBlocking {
        assertThrows(RejectedBatchException::class.java) { runBlocking { api(HttpStatusCode.ServiceUnavailable).me() } }
        assertThrows(RejectedBatchException::class.java) { runBlocking { api(HttpStatusCode.Forbidden).me() } }

        assertEquals("tok-revoked", stored)
        assertTrue(session.enrolled.value)
    }

    @Test
    fun enrollingAgainMarksTheSessionEnrolled() {
        session.rejected()
        assertFalse(session.enrolled.value)

        stored = "tok-new"
        session.saved()

        assertTrue(session.enrolled.value)
    }

    @Test
    fun unsyncedOrdersSurviveARejectedTokenAndWaitForReEnrollment() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.outboxDao().insert(OutboxEntity("m-1", "o-1", "ORDER_PLACED", "{}", 1, 0))

            val outcome = OutboxDrainer(db, api(HttpStatusCode.Unauthorized)).drain()

            assertTrue(outcome is DrainOutcome.RetryLater)
            assertEquals(OutboxState.PENDING, db.outboxDao().all().single().state)
            assertFalse(session.enrolled.value)
        } finally {
            db.close()
        }
    }
}
