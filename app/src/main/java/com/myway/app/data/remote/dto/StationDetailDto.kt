package com.myway.app.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.myway.app.data.repository.DayType
import com.myway.app.data.repository.Facility
import com.myway.app.data.repository.FirstLast
import com.myway.app.data.repository.StationDetail
import com.myway.app.domain.model.Station

// ---- 역 상세: GET /v1/stations/{station_id}/detail ----
data class FirstLastDto(
    @SerializedName("direction") val direction: String?,   // UP / DOWN / IN / OUT
    @SerializedName("first") val first: String?,
    @SerializedName("last") val last: String?,
    @SerializedName("day_type") val dayType: String?       // WEEKDAY / SATURDAY / HOLIDAY
)

data class FacilityDto(
    @SerializedName("code") val code: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("where") val where: String?
)

data class StationDetailResponseDto(
    @SerializedName("station") val station: StationDto?,
    @SerializedName("first_last") val firstLast: List<FirstLastDto>?,
    @SerializedName("timetable") val timetable: Map<String, Map<String, List<String>>>?,
    @SerializedName("congestion") val congestion: List<Int>?,   // 데이터 없으면 빈 목록
    @SerializedName("facilities") val facilities: List<FacilityDto>?
)

/** 서버 방향 코드 → 화면 표시(한국어). 영어 화면은 StationNames.direction 이 다시 번역 */
private fun directionLabel(code: String) = when (code.uppercase()) {
    "UP" -> "상행"
    "DOWN" -> "하행"
    "IN" -> "내선순환"
    "OUT" -> "외선순환"
    else -> code
}

private fun dayTypeOf(s: String?) = runCatching { DayType.valueOf(s.orEmpty().uppercase()) }.getOrNull()

/** today: 첫차·막차는 오늘 요일 것만 (없으면 평일 → 아무 요일) */
fun StationDetailResponseDto.toDomain(fallback: Station, today: DayType): StationDetail {
    val all = firstLast.orEmpty().filter { it.direction != null && it.first != null && it.last != null }
    val pick = all.filter { dayTypeOf(it.dayType) == today }.ifEmpty { all.filter { dayTypeOf(it.dayType) == DayType.WEEKDAY } }.ifEmpty { all }
    return StationDetail(
        station = station?.toDomain() ?: fallback,
        firstLast = pick.distinctBy { it.direction }
            .map { FirstLast(directionLabel(it.direction!!), it.first!!, it.last!!) },
        timetable = timetable.orEmpty().mapNotNull { (day, dirs) ->
            dayTypeOf(day)?.let { d -> d to dirs.mapKeys { (dir, _) -> directionLabel(dir) } }
        }.toMap(),
        congestion = congestion.orEmpty(),
        facilities = facilities.orEmpty().mapNotNull { f ->
            val code = f.code ?: return@mapNotNull null
            Facility(code, f.name ?: code, f.where.orEmpty())
        }
    )
}
