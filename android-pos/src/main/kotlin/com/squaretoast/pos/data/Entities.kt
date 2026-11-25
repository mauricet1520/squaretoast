package com.squaretoast.pos.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squaretoast.pos.domain.CourseState

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val locationId: String,
    val tableLabel: String,
    val createdAt: Long,
    /** The check's ID, made here so an offline claim and the synced check share it. Null for pre-v2 orders. */
    val checkId: String? = null,
)

@Entity(
    tableName = "course",
    foreignKeys = [ForeignKey(entity = OrderEntity::class, parentColumns = ["id"], childColumns = ["orderId"])],
    indices = [Index(value = ["orderId", "number"], unique = true)],
)
data class CourseEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val number: Int,
    val state: CourseState,
)

@Entity(
    tableName = "order_line",
    foreignKeys = [
        ForeignKey(entity = OrderEntity::class, parentColumns = ["id"], childColumns = ["orderId"]),
        ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"]),
    ],
    indices = [Index("orderId"), Index("courseId")],
)
data class OrderLineEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val courseId: String,
    val seat: Int,
    val menuItemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Long,
)

enum class OutboxState { PENDING, INFLIGHT, DONE, CONFLICT }

/** One local command awaiting the cloud. mutationId is the server's dedupe key; deviceSeq is the send order. */
@Entity(tableName = "outbox", indices = [Index(value = ["deviceSeq"], unique = true), Index("state")])
data class OutboxEntity(
    @PrimaryKey val mutationId: String,
    val aggregateId: String,
    val type: String,
    val payloadJson: String,
    val deviceSeq: Long,
    val createdAt: Long,
    val attempts: Int = 0,
    val state: OutboxState = OutboxState.PENDING,
    val lastError: String? = null,
)
