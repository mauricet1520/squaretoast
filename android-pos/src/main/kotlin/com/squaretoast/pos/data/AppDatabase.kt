package com.squaretoast.pos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
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
    entities = [OrderEntity::class, CourseEntity::class, OrderLineEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun orderDao(): OrderDao
    abstract fun outboxDao(): OutboxDao
}
