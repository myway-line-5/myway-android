package com.myway.app.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.myway.app.domain.model.Station

data class StationDto(
    @SerializedName("station_id") val stationId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("line") val line: String?,
    @SerializedName("is_active") val isActive: Boolean?
)

data class StationListResponseDto(
    @SerializedName("items") val items: List<StationDto>?
)

// 서버 DTO -> 앱 모델. 필수 값이 하나라도 없으면 null (그 역은 버림)
fun StationDto.toDomain(): Station? {
    val id = stationId ?: return null
    val n = name ?: return null
    val l = line ?: return null
    return Station(
        stationId = id,
        name = n,
        line = l,
        isActive = isActive ?: false
    )
}

fun StationListResponseDto.toDomain(): List<Station> =
    items.orEmpty().mapNotNull { it.toDomain() }

