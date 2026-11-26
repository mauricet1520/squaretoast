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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(meta: MenuMetaEntity)
}
