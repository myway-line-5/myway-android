package com.myway.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT name FROM favorite_station ORDER BY addedAt DESC")
    fun observeNames(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FavoriteStationEntity)

    @Query("DELETE FROM favorite_station WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_station WHERE name = :name)")
    suspend fun exists(name: String): Boolean
}