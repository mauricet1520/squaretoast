package com.squaretoast.pos.sync

import com.squaretoast.pos.data.AppDatabase
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class MenuDto(val items: List<MenuItemDto>)

@Serializable
data class MenuItemDto(
    val id: String,
    val name: String,
    val priceCents: Long,
    val currency: String,
    val available: Boolean,
    val modifierGroups: List<ModifierGroupDto> = emptyList(),
)

@Serializable
data class ModifierGroupDto(val minSelect: Int)

/** What menu sync needs from the cloud. */
interface MenuApi {
    suspend fun me(): DeviceInfo
    suspend fun menu(locationId: String): MenuDto
}

/** Keeps the on-device menu snapshot fresh. A failed fetch keeps the last good snapshot, so ordering works offline. */
class MenuSync(private val db: AppDatabase, private val api: MenuApi, private val clock: Clock) {

    /** True if a new snapshot was stored. */
    suspend fun refresh(): Boolean = TODO("Slice 8")
}
