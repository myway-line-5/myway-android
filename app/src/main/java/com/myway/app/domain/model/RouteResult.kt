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
    val type: RouteType,              // 최단시간/최소비용/최소환승/선호 기반 중 어떤 경로인지
    val segments: List<RouteSegment>, // 이동 순서대로 구간 목록
    val totalSeconds: Int,
    val totalMeters: Int,
    val totalFareKrw: Int,
    val transferCount: Int,
    val dataVersion: String,          // 그래프 데이터 버전 (즐겨찾기 스냅샷 저장용, 표 4-14)
    val fareVersion: String           // 운임 규칙 버전 (즐겨찾기 스냅샷 저장용, 표 4-14)
)