package com.myway.app.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.RouteSegment
import com.myway.app.domain.model.RouteType

// ---- 요청 ----
data class RouteSearchRequestDto(
    @SerializedName("origin_station_id") val originStationId: String,
    @SerializedName("destination_station_id") val destinationStationId: String,
    // 탐색 기준 (서버 11/24 추가): shortest_time / minimum_transfer / minimum_fare. null 이면 서버 기본
    @SerializedName("optimization") val optimization: String? = null
)

// ---- 응답 ----
data class RouteSegmentDto(
    @SerializedName("from_station") val fromStation: StationDto?,
    @SerializedName("to_station") val toStation: StationDto?,
    @SerializedName("line_name") val lineName: String?,
    @SerializedName("seconds") val seconds: Int?,
    @SerializedName("meters") val meters: Int?,
    @SerializedName("is_transfer") val isTransfer: Boolean?
)

data class RouteResultDto(
    @SerializedName("type") val type: String?,
    @SerializedName("segments") val segments: List<RouteSegmentDto>?,
    @SerializedName("total_seconds") val totalSeconds: Int?,
    @SerializedName("total_meters") val totalMeters: Int?,
    @SerializedName("total_fare_krw") val totalFareKrw: Int?,
    @SerializedName("transfer_count") val transferCount: Int?,
    @SerializedName("fare_version") val fareVersion: String?
)

data class RouteSearchResponseDto(
    @SerializedName("status") val status: String?,        // ok / no_route / incomplete / error
    @SerializedName("error_code") val errorCode: String?,
    @SerializedName("route") val route: RouteResultDto?
)

// ---- 서버 DTO -> 앱 모델. 필수 값이 빠지면 null ----
fun RouteSegmentDto.toDomain(): RouteSegment? {
    val from = fromStation?.toDomain() ?: return null
    val to = toStation?.toDomain() ?: return null
    return RouteSegment(
        fromStation = from,
        toStation = to,
        lineName = lineName ?: from.line,
        seconds = seconds ?: return null,
        meters = meters ?: 0,
        isTransfer = isTransfer ?: false
    )
}

fun RouteResultDto.toDomain(): RouteResult? {
    val routeType = RouteType.values().firstOrNull { it.name == type } ?: return null
    val segs = segments.orEmpty().map { it.toDomain() ?: return null }
    if (segs.isEmpty()) return null
    return RouteResult(
        type = routeType,
        segments = segs,
        totalSeconds = totalSeconds ?: return null,
        totalMeters = totalMeters ?: segs.sumOf { it.meters },
        // TODO: 서버가 요금을 아직 안 줌(null). 요금 계산이 붙으면 그 값을 사용
        totalFareKrw = totalFareKrw ?: 0,
        transferCount = transferCount ?: segs.count { it.isTransfer },
        fareVersion = fareVersion.orEmpty()
    )
}