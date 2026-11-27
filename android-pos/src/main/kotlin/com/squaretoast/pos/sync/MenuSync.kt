package com.squaretoast.pos.sync

import androidx.room.withTransaction
import com.squaretoast.pos.data.AppDatabase
import com.squaretoast.pos.data.MenuItemEntity
import com.squaretoast.pos.data.MenuMetaEntity
import com.squaretoast.pos.data.ModifierGroupEntity
import com.squaretoast.pos.data.ModifierOptionEntity
import com.squaretoast.pos.data.TaxProfileEntity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
data class ModifierGroupDto(
    val minSelect: Int,
    val id: String = "",
    val name: String = "",
    val maxSelect: Int = maxOf(minSelect, 1),
    val options: List<ModifierOptionDto> = emptyList(),
)

@Serializable
data class ModifierOptionDto(val id: String, val name: String, val priceDeltaCents: Long, val available: Boolean = true)

/** What menu sync needs from the cloud. */
interface MenuApi {
    suspend fun me(): DeviceInfo
    suspend fun menu(locationId: String): MenuDto
    suspend fun taxProfile(locationId: String): TaxProfile
}

/** Keeps the on-device menu snapshot fresh. A failed fetch keeps the last good snapshot, so ordering works offline. */
class MenuSync(private val db: AppDatabase, private val api: MenuApi, private val clock: Clock) {

    /** True if a new snapshot was stored. */
    suspend fun refresh(): Boolean {
        val fetched = runCatching {
            val me = api.me() // the device's own location, never one chosen on screen
            Triple(me.locationId, me.deviceId, api.menu(me.locationId))
        }.getOrElse { if (it is CancellationException) throw it else return false }
        val (location, deviceId, menu) = fetched
        val items = menu.items.mapIndexed { i, m ->
            MenuItemEntity(m.id, m.name, m.priceCents, m.currency, m.available, m.modifierGroups.any { it.minSelect > 0 }, i)
        }
        val groups = menu.items.flatMap { m ->
            m.modifierGroups.mapIndexed { i, g -> ModifierGroupEntity(m.id, g.id, g.name, g.minSelect, g.maxSelect, i) }
        }
        val options = menu.items.flatMap { it.modifierGroups }.flatMap { g ->
            g.options.mapIndexed { i, o -> ModifierOptionEntity(o.id, g.id, o.name, o.priceDeltaCents, o.available, i) }
        }
        db.withTransaction {
            db.menuDao().clearItems()
            db.menuDao().clearGroups()
            db.menuDao().clearOptions()
            db.menuDao().insertItems(items)
            db.menuDao().insertGroups(groups)
            db.menuDao().insertOptions(options)
            db.menuDao().putMeta(MenuMetaEntity(locationId = location, fetchedAt = clock.millis()))
            // Another location's rules must never price this one's checks, even briefly.
            if (db.menuDao().taxProfile()?.locationId != location) db.menuDao().clearTaxProfile()
        }
        refreshTaxProfile(location, deviceId)
        return true
    }

    /** Slice 14: best effort. A failure keeps the last good profile; the pay code checks it still covers today. */
    private suspend fun refreshTaxProfile(location: String, deviceId: String) {
        val profile = runCatching { api.taxProfile(location) }
            .getOrElse { if (it is CancellationException) throw it else return }
        if (profile.locationId != location) return
        db.menuDao().putTaxProfile(TaxProfileEntity(locationId = location, deviceId = deviceId, json = Json.encodeToString(profile),
            fetchedAt = clock.millis()))
    }
}
