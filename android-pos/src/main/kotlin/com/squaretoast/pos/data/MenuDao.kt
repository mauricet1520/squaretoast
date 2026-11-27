package com.squaretoast.pos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuDao {
    @Query("select * from menu_item order by position") suspend fun items(): List<MenuItemEntity>
    @Query("select * from menu_item order by position") fun observeItems(): Flow<List<MenuItemEntity>>
    @Query("select * from menu_meta where id = 0") suspend fun meta(): MenuMetaEntity?
    @Query("select * from menu_meta where id = 0") fun observeMeta(): Flow<MenuMetaEntity?>
    @Query("delete from menu_item") suspend fun clearItems()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertItems(items: List<MenuItemEntity>)
    @Query("select * from modifier_group where menuItemId = :menuItemId order by position") suspend fun groupsFor(menuItemId: String): List<ModifierGroupEntity>
    @Query("select * from modifier_option where groupId in (:groupIds) order by position") suspend fun optionsFor(groupIds: List<String>): List<ModifierOptionEntity>
    @Query("select distinct menuItemId from modifier_group") fun observeItemsWithOptions(): Flow<List<String>>
    @Query("delete from modifier_group") suspend fun clearGroups()
    @Query("delete from modifier_option") suspend fun clearOptions()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGroups(groups: List<ModifierGroupEntity>)
    /** A shared group is listed under each of its items; its options are stored once. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertOptions(options: List<ModifierOptionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(meta: MenuMetaEntity)
    @Query("select * from tax_profile where id = 0") suspend fun taxProfile(): TaxProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTaxProfile(profile: TaxProfileEntity)
    @Query("delete from tax_profile") suspend fun clearTaxProfile()
}
