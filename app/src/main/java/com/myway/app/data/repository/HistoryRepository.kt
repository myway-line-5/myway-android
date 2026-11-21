package com.myway.app.data.repository

import com.myway.app.data.local.RecentSearchDao
import com.myway.app.data.local.RecentSearchEntity
import com.myway.app.domain.model.RecentSearch
import com.myway.app.domain.model.Station
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface HistoryRepository {
    fun observeRecent(): Flow<List<RecentSearch>>
    /** 경로 검색에 성공했을 때만 호출. 역 후보 검색만으로는 부르지 않는다. */
    suspend fun record(origin: Station, destination: Station)
    suspend fun remove(searchId: Long)
    suspend fun clear()
}

class RoomHistoryRepository(
    private val dao: RecentSearchDao
) : HistoryRepository {

    override fun observeRecent(): Flow<List<RecentSearch>> =
        dao.observeRecent(SHOW_COUNT).map { list ->
            list.map {
                RecentSearch(
                    searchId = it.searchId,
                    originId = it.originId,
                    destinationId = it.destinationId,
                    originName = it.originName,
                    destinationName = it.destinationName,
                    searchedAt = it.searchedAt
                )
            }
        }

    override suspend fun record(origin: Station, destination: Station) {
        dao.insert(
            RecentSearchEntity(
                originId = origin.stationId,
                destinationId = destination.stationId,
                searchedAt = Instant.now().toString(),
                originName = origin.name,
                destinationName = destination.name
            )
        )
        dao.trim(KEEP_COUNT)
    }

    override suspend fun remove(searchId: Long) = dao.deleteById(searchId)

    override suspend fun clear() = dao.clearAll()

    private companion object {
        const val SHOW_COUNT = 10
        const val KEEP_COUNT = 20
    }
}