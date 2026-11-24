package com.myway.app.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.myway.app.domain.model.Constraints
import com.myway.app.domain.model.Criterion
import com.myway.app.domain.model.Preference
import com.myway.app.domain.model.PreferenceMode
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.StationSlot

// ---- 문장 해석: POST /v1/conditions/interpret ----
data class InterpretRequestDto(
    @SerializedName("user_message") val userMessage: String
)

// 서버가 앱용으로 바꿔서 주는 조건은 camelCase 그대로라 이름을 따로 안 붙인다
data class StationSlotDto(
    val rawName: String?,
    val suggestedName: String?
)

data class ConstraintsDto(
    val maxDurationMinutes: Int?,
    val maxTransfers: Int?,
    val maxFareKrw: Int?
)

// preference 는 서버가 AI 원본(소문자 문자열)을 그대로 내려준다
data class PreferenceDto(
    val mode: String?,
    val priority: List<String>?,
    val weights: Map<String, Double>?
)

data class ConditionsDto(
    val origin: StationSlotDto?,
    val destination: StationSlotDto?,
    val constraints: ConstraintsDto?,
    val preference: PreferenceDto?
)

// 최상위 필드만 snake_case
data class InterpretResponseDto(
    @SerializedName("result") val result: String?,
    @SerializedName("error_code") val errorCode: String?,
    @SerializedName("conditions") val conditions: ConditionsDto?,
    @SerializedName("ai_provider") val aiProvider: String?
)

// ---- 역 후보: POST /v1/stations/resolve ----
data class StationResolveRequestDto(
    @SerializedName("raw_name") val rawName: String,
    @SerializedName("suggested_name") val suggestedName: String?
)

data class StationResolveResponseDto(
    @SerializedName("candidates") val candidates: List<StationDto>?,
    @SerializedName("suggested_candidates") val suggestedCandidates: List<StationDto>?
)

// ---- 서버 DTO -> 앱 모델 ----
fun StationSlotDto.toDomain(): StationSlot? {
    val raw = rawName ?: return null
    return StationSlot(rawName = raw, suggestedName = suggestedName, station = null) // 확정 전
}

private fun criterionOf(s: String): Criterion? = when (s.lowercase()) {
    "duration" -> Criterion.DURATION
    "transfers" -> Criterion.TRANSFERS
    "fare" -> Criterion.FARE
    else -> null
}

fun PreferenceDto.toDomain(): Preference? {
    val m = when (mode?.lowercase()) {
        "weighted" -> PreferenceMode.WEIGHTED
        "lexicographic" -> PreferenceMode.LEXICOGRAPHIC
        else -> return null
    }
    val order = priority.orEmpty().mapNotNull { criterionOf(it) }.distinct()
    if (order.isEmpty()) return null
    val w = weights?.entries?.mapNotNull { (k, v) -> criterionOf(k)?.let { it to v } }?.toMap()
    return Preference(m, order, w)
}

fun ConditionsDto.toDomain(): RouteCondition = RouteCondition(
    origin = origin?.toDomain(),
    destination = destination?.toDomain(),
    constraints = Constraints(
        maxDurationMinutes = constraints?.maxDurationMinutes,
        maxTransfers = constraints?.maxTransfers,
        maxFareKrw = constraints?.maxFareKrw
    ),
    preference = preference?.toDomain()
)