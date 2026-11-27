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

/** ACKNOWLEDGED: a CONFLICT a person has seen; kept for the record, never resent. */
enum class OutboxState { PENDING, INFLIGHT, DONE, CONFLICT, ACKNOWLEDGED }

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

/** The location's menu as last fetched, so orders can be taken offline. Prices here are for display only. */
@Entity(tableName = "menu_item")
data class MenuItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val priceCents: Long,
    val currency: String,
    val available: Boolean,
    /** Has a modifier group the guest must choose from; such items can't be rung up until modifiers exist. */
    val needsOptions: Boolean,
    val position: Int,
)

/** One row: which location the snapshot belongs to and when it was fetched. */
@Entity(tableName = "menu_meta")
data class MenuMetaEntity(
    @PrimaryKey val id: Int = 0,
    val locationId: String,
    val fetchedAt: Long,
)

/** A modifier group as linked to one menu item (a group can be shared, so the key includes the item). */
@Entity(tableName = "modifier_group", primaryKeys = ["menuItemId", "id"])
data class ModifierGroupEntity(
    val menuItemId: String,
    val id: String,
    val name: String,
    val minSelect: Int,
    val maxSelect: Int,
    val position: Int,
)

/** Prices are display estimates; the server prices every option from its own menu. */
@Entity(tableName = "modifier_option", indices = [Index("groupId")])
data class ModifierOptionEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
    val priceDeltaCents: Long,
    val available: Boolean,
    val position: Int,
)

/** Slice 14: one row, the location's tax setup as fetched, so an offline check can be totalled (plan S14-3). */
@Entity(tableName = "tax_profile")
data class TaxProfileEntity(
    @PrimaryKey val id: Int = 0,
    val locationId: String,
    /** The enrolled device that fetched it: the claim's signer, known without the network. */
    val deviceId: String,
    val json: String,
    val fetchedAt: Long,
) {
    fun profile(): com.squaretoast.pos.sync.TaxProfile = kotlinx.serialization.json.Json.decodeFromString(json)
}
