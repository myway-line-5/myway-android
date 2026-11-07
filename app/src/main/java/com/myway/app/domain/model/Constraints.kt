package com.myway.app.domain.model

data class Constraints(
    val maxDurationMinutes: Int? = null, // 최대 소요시간(분). null이면 제한 없음
    val maxTransfers: Int? = null,       // 최대 환승 횟수. 0은 "무환승", null은 "제한 없음"
    val maxFareKrw: Int? = null          // 최대 요금(원). null이면 제한 없음
)