package com.myway.app.data.repository

import com.myway.app.data.local.FavoriteDao
import com.myway.app.data.local.FavoriteStationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface FavoriteRepository {
    fun observeFavoriteNames(): Flow<Set<String>>
    suspend fun toggle(name: String)
}

class RoomFavoriteRepository(
    private val dao: FavoriteDao
) : FavoriteRepository {

    override fun observeFavoriteNames(): Flow<Set<String>> =
        dao.observeNames().map { it.toSet() }

    override suspend fun toggle(name: String) {
        if (dao.exists(name)) dao.deleteByName(name)
        else dao.insert(FavoriteStationEntity(name))
    }
}