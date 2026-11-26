package com.squaretoast.pos.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squaretoast.pos.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MenuSyncTest {

    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.parse("2026-11-26T18:00:00Z"), ZoneOffset.UTC)

    private class FakeApi(var location: String, var menu: () -> MenuDto) : MenuApi {
        val asked = mutableListOf<String>()
        override suspend fun me() = DeviceInfo("d-1", location)
        override suspend fun menu(locationId: String): MenuDto { asked += locationId; return menu() }
    }

    private val lunch = MenuDto(listOf(
        MenuItemDto("burger", "Burger", 1299, "USD", true),
        MenuItemDto("steak", "Steak", 3150, "USD", true, listOf(ModifierGroupDto(minSelect = 1))),
        MenuItemDto("fries", "Fries", 450, "USD", false, listOf(ModifierGroupDto(minSelect = 0))),
    ))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun storesTheDevicesOwnMenuInServerOrder() = runBlocking {
        val api = FakeApi("loc-1") { lunch }

        assertTrue(MenuSync(db, api, clock).refresh())

        assertEquals(listOf("loc-1"), api.asked)
        val items = db.menuDao().items()
        assertEquals(listOf("burger", "steak", "fries"), items.map { it.id })
        assertEquals(listOf(1299L, 3150L, 450L), items.map { it.priceCents })
        assertEquals(listOf(true, true, false), items.map { it.available })
        assertEquals(listOf(false, true, false), items.map { it.needsOptions }) // only a required group counts
        val meta = db.menuDao().meta()!!
        assertEquals("loc-1", meta.locationId)
        assertEquals(clock.millis(), meta.fetchedAt)
    }

    @Test
    fun aNetworkErrorKeepsTheLastGoodMenu() = runBlocking {
        val api = FakeApi("loc-1") { lunch }
        MenuSync(db, api, clock).refresh()

        api.menu = { throw IOException("no route to host") }
        assertFalse(MenuSync(db, api, Clock.offset(clock, Duration.ofHours(1))).refresh())

        assertEquals(3, db.menuDao().items().size)
        assertEquals(clock.millis(), db.menuDao().meta()!!.fetchedAt)
    }

    @Test
    fun aRejectedTokenKeepsTheLastGoodMenu() = runBlocking {
        val api = FakeApi("loc-1") { lunch }
        MenuSync(db, api, clock).refresh()

        api.menu = { throw RejectedBatchException(401) }
        assertFalse(MenuSync(db, api, clock).refresh())

        assertEquals(3, db.menuDao().items().size)
    }

    @Test
    fun aDeviceMovedToAnotherLocationGetsOnlyThatMenu() = runBlocking {
        val api = FakeApi("loc-1") { lunch }
        MenuSync(db, api, clock).refresh()

        api.location = "loc-2"
        api.menu = { MenuDto(listOf(MenuItemDto("tacos", "Tacos", 900, "USD", true))) }
        assertTrue(MenuSync(db, api, clock).refresh())

        assertEquals(listOf("tacos"), db.menuDao().items().map { it.id })
        assertEquals("loc-2", db.menuDao().meta()!!.locationId)
    }
}
