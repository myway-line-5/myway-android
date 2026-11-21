package com.myway.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 즐겨찾기 = 역 묶음(물리적 역) 단위. TODO: Station 에 groupId 생기면 키를 groupId 로 교체 */
@Entity(tableName = "favorite_station")
data class FavoriteStationEntity(
    @PrimaryKey val name: String,
    val addedAt: Long = System.currentTimeMillis()
)