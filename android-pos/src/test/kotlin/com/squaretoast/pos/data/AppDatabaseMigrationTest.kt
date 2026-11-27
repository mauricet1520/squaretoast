package com.squaretoast.pos.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Builds a version-1 database from its exported schema, then opens it with the current code and migrations. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppDatabaseMigrationTest {

    @Test
    fun aVersionOneDatabaseKeepsItsOrdersAfterMigrating() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = context.getDatabasePath("migrate.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResource("com.squaretoast.pos.data.AppDatabase/1.json")).readText(),
        ).jsonObject["database"]!!.jsonObject

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
                old.execSQL(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach {
                    old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("insert into orders (id, locationId, tableLabel, createdAt) values ('o-1', 'l-1', 'T4', 1)")
            old.version = 1
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            val kept = db.orderDao().order("o-1")!!
            assertEquals("T4", kept.tableLabel)
            assertNull(kept.checkId) // placed before check IDs existed, and already synced without one
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersionTwoDatabaseKeepsItsOrdersAndGetsAnEmptyMenu() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = context.getDatabasePath("migrate2.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResource("com.squaretoast.pos.data.AppDatabase/2.json")).readText(),
        ).jsonObject["database"]!!.jsonObject

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
                old.execSQL(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach {
                    old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("insert into orders (id, locationId, tableLabel, createdAt, checkId) values ('o-2', 'l-1', 'T9', 1, 'c-2')")
            old.version = 2
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertEquals("c-2", db.orderDao().order("o-2")!!.checkId)
            assertEquals(emptyList<MenuItemEntity>(), db.menuDao().items())
            assertNull(db.menuDao().meta())
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersionThreeDatabaseKeepsItsMenuAndGetsEmptyOptionTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = context.getDatabasePath("migrate3.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResource("com.squaretoast.pos.data.AppDatabase/3.json")).readText(),
        ).jsonObject["database"]!!.jsonObject

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
                old.execSQL(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach {
                    old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("insert into orders (id, locationId, tableLabel, createdAt, checkId) values ('o-3', 'l-1', 'T2', 1, 'c-3')")
            old.execSQL("insert into menu_item (id, name, priceCents, currency, available, needsOptions, position) values ('steak', 'Steak', 3000, 'USD', 1, 1, 0)")
            old.version = 3
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertEquals("T2", db.orderDao().order("o-3")!!.tableLabel)
            assertEquals(listOf("steak"), db.menuDao().items().map { it.id })
            assertEquals(emptyList<ModifierGroupEntity>(), db.menuDao().groupsFor("steak"))
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersionFourDatabaseKeepsItsOptionsAndGetsNoTaxProfile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = context.getDatabasePath("migrate4.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResource("com.squaretoast.pos.data.AppDatabase/4.json")).readText(),
        ).jsonObject["database"]!!.jsonObject

        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
                old.execSQL(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach {
                    old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("insert into orders (id, locationId, tableLabel, createdAt, checkId) values ('o-4', 'l-1', 'T3', 1, 'c-4')")
            old.execSQL("insert into modifier_group (menuItemId, id, name, minSelect, maxSelect, position) values ('steak', 'g-1', 'Cook', 1, 1, 0)")
            old.version = 4
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertEquals("c-4", db.orderDao().order("o-4")!!.checkId)
            assertEquals(listOf("g-1"), db.menuDao().groupsFor("steak").map { it.id })
            assertEquals(null, db.menuDao().taxProfile()) // fetched on the next menu refresh; no pay code until then
            db.menuDao().putTaxProfile(TaxProfileEntity(locationId = "l-1", deviceId = "d-1", json = "{}", fetchedAt = 1))
            assertEquals("d-1", db.menuDao().taxProfile()!!.deviceId)
        } finally {
            db.close()
        }
    }
}
