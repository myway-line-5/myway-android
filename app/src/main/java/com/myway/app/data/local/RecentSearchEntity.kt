package com.myway.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 설계서 표 4-16 RecentSearch. 경로 검색에 성공한 확정 출발·도착역 쌍. */
@Entity(
    tableName = "RecentSearch",
    indices = [Index(value = ["origin_id", "destination_id"], unique = true)]
)
data class RecentSearchEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "search_id") val searchId: Long = 0,
    @ColumnInfo(name = "origin_id") val originId: String,
    @ColumnInfo(name = "destination_id") val destinationId: String,
    @ColumnInfo(name = "searched_at") val searchedAt: String,
    @ColumnInfo(name = "origin_name") val originName: String,
    @ColumnInfo(name = "destination_name") val destinationName: String
)