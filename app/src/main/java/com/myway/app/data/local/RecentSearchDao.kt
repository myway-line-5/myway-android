package com.myway.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSearchDao {

    @Query("SELECT * FROM RecentSearch ORDER BY search_id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>>

    // 같은 (출발, 도착) 쌍이면 기존 행을 지우고 새로 넣어 맨 위로 올림
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecentSearchEntity)

    @Query(
        "DELETE FROM RecentSearch WHERE search_id NOT IN " +
                "(SELECT search_id FROM RecentSearch ORDER BY search_id DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM RecentSearch WHERE search_id = :searchId")
    suspend fun deleteById(searchId: Long)

    @Query("DELETE FROM RecentSearch")
    suspend fun clearAll()
}