package com.myway.app.domain.model

data class RouteCondition(
    val origin: StationSlot?,        // 출발역. 아직 안 정했으면 null
    val destination: StationSlot?,   // 도착역. 아직 안 정했으면 null
    val constraints: Constraints = Constraints(), // 제한 조건 (기본: 전부 제한 없음)
    val preference: Preference? = null            // 선호 조건. 없으면 null
)