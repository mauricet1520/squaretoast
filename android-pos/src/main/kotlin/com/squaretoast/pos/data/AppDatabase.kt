package com.squaretoast.pos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.squaretoast.pos.domain.CourseState
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOrder(order: OrderEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCourses(courses: List<CourseEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLines(lines: List<OrderLineEntity>)

    @Query("select * from orders where id = :id") suspend fun order(id: String): OrderEntity?
    @Query("select * from order_line where orderId = :orderId order by seat, id") suspend fun lines(orderId: String): List<OrderLineEntity>
    @Query("select * from course where orderId = :orderId order by number") suspend fun courses(orderId: String): List<CourseEntity>
    @Query("select * from course where orderId = :orderId order by number") fun observeCourses(orderId: String): Flow<List<CourseEntity>>
    @Query("select * from course where id = :id") suspend fun course(id: String): CourseEntity?
    @Query("update course set state = :state where id = :id") suspend fun setCourseState(id: String, state: CourseState)
    @Query("select * from orders order by createdAt desc") fun observeOrders(): Flow<List<OrderEntity>>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: OutboxEntity)
    @Query("select * from outbox order by deviceSeq") suspend fun all(): List<OutboxEntity>
    @Query("select coalesce(max(deviceSeq), 0) from outbox") suspend fun maxSeq(): Long
    @Query("select * from outbox where state = 'PENDING' order by deviceSeq limit :limit") suspend fun pending(limit: Int): List<OutboxEntity>
    @Query("update outbox set state = :state where mutationId = :id") suspend fun setState(id: String, state: OutboxState)
    @Query("update outbox set state = :state where mutationId in (:ids)") suspend fun setStates(ids: List<String>, state: OutboxState)
    @Query("update outbox set state = 'CONFLICT', lastError = :reason where mutationId = :id") suspend fun markConflict(id: String, reason: String?)
    @Query("update outbox set state = 'PENDING', attempts = attempts + 1, lastError = :error where mutationId in (:ids)")
    suspend fun releaseForRetry(ids: List<String>, error: String?)
    @Query("update outbox set state = 'PENDING' where state = 'INFLIGHT'") suspend fun resetInflight()
    @Query("select * from outbox where state = 'CONFLICT' order by deviceSeq") fun conflicts(): Flow<List<OutboxEntity>>
}

@Database(
    entities = [
        OrderEntity::class, CourseEntity::class, OrderLineEntity::class, OutboxEntity::class,
        MenuItemEntity::class, MenuMetaEntity::class, ModifierGroupEntity::class, ModifierOptionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun orderDao(): OrderDao
    abstract fun outboxDao(): OutboxDao
    abstract fun menuDao(): MenuDao

    companion object {
        /** v2: orders carry the check ID the POS sends with ORDER_PLACED (plan S7-1). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("alter table orders add column checkId TEXT")
            }
        }

        /** v3: the menu snapshot for offline order entry (plan 13). Adds tables only. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `menu_item` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`priceCents` INTEGER NOT NULL, `currency` TEXT NOT NULL, `available` INTEGER NOT NULL, " +
                        "`needsOptions` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `menu_meta` (`id` INTEGER NOT NULL, `locationId` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** v4: modifier groups and options, so items with options can be rung up (plan 14). Adds tables only. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `modifier_group` (`menuItemId` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `minSelect` INTEGER NOT NULL, `maxSelect` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`menuItemId`, `id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `modifier_option` (`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `priceDeltaCents` INTEGER NOT NULL, `available` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_modifier_option_groupId` ON `modifier_option` (`groupId`)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
