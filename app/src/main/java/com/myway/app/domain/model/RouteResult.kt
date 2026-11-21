package com.myway.app.domain.model

enum class RouteType { SHORTEST_TIME, MIN_FARE, MIN_TRANSFERS, PREFERENCE }

data class RouteSegment(
    val fromStation: Station,
    val toStation: Station,
    val lineName: String,
    val seconds: Int,       // 구간 소요시간(초)
    val meters: Int,        // 구간 거리(m)
    val isTransfer: Boolean // 환승 구간이면 true
)

data class RouteResult(
    val type: RouteType,
    val segments: List<RouteSegment>,
    val totalSeconds: Int,
    val totalMeters: Int,
    val totalFareKrw: Int,
    val transferCount: Int,
    val fareVersion: String           // 서버 RouteResultDTO.fare_version (즐겨찾기 경로 저장용)
)