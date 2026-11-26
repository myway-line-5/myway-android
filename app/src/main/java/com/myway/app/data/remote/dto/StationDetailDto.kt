package com.myway.app.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.myway.app.data.repository.DayType
import com.myway.app.data.repository.Facility
import com.myway.app.data.repository.FirstLast
import com.myway.app.data.repository.StationDetail
import com.myway.app.domain.model.Station

// ---- 응답 (GET /v1/stations/{id}/detail) ----
data class FirstLastDto(
    @SerializedName("direction") val direction: String?,
    @SerializedName("first") val first: String?,
    @SerializedName("last") val last: String?,
    @SerializedName("day_type") val dayType: String?
)

data class FacilityDto(
    @SerializedName("code") val code: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("where") val where: String?
)

data class StationDetailResponseDto(
    @SerializedName("station") val station: StationDto?,
    @SerializedName("first_last") val firstLast: List<FirstLastDto>?,
    // 요일 -> 방향 -> 출발 시각("HH:mm", 자정 넘김은 24:xx)
    @SerializedName("timetable") val timetable: Map<String, Map<String, List<String>>>?,
    @SerializedName("congestion") val congestion: List<Int>?,
    @SerializedName("facilities") val facilities: List<FacilityDto>?
)

private data class FirstLastRow(val dayType: String?, val value: FirstLast)

// 서버 방향 코드 -> 화면에 보일 글자
private fun directionLabel(code: String): String = when (code.uppercase()) {
    "IN" -> "내선순환"
    "OUT" -> "외선순환"
    "UP" -> "상행"
    "DOWN" -> "하행"
    else -> code
}

// 서버 편의시설 코드 -> 아이콘 칸에 들어갈 2글자
private fun facilityCode(code: String): String = when (code) {
    "elevator" -> "EV"
    "wheelchair_lift" -> "WL"
    "nursing_room" -> "NR"
    "bike_parking" -> "BK"
    "transfer_parking" -> "PK"
    "meeting_place" -> "MP"
    "cultural_space" -> "CS"
    "manned_luggage" -> "LG"
    "unmanned_civil_service" -> "CV"
    "exchange_kiosk" -> "FX"
    else -> code.take(2).uppercase()
}

// 서버 DTO -> 앱 모델. 값이 비어 있어도(데이터 미적재) 빈 목록으로 돌려준다
fun StationDetailResponseDto.toDomain(fallback: Station): StationDetail {
    val st = station?.toDomain() ?: fallback

    // 첫차·막차: 요일별로 오므로 평일 기준 한 세트만 쓴다 (평일이 없으면 있는 첫 요일)
    val rows = firstLast.orEmpty().mapNotNull { r ->
        val d = r.direction ?: return@mapNotNull null
        val f = r.first ?: return@mapNotNull null
        val l = r.last ?: return@mapNotNull null
        FirstLastRow(r.dayType, FirstLast(directionLabel(d), f, l))
    }
    val pickedDay = if (rows.any { it.dayType == "WEEKDAY" }) "WEEKDAY" else rows.firstOrNull()?.dayType
    val firstLastList = rows.filter { it.dayType == pickedDay }.map { it.value }

    val timetableMap = timetable.orEmpty().mapNotNull { (day, byDirection) ->
        val type = DayType.values().firstOrNull { it.name == day } ?: return@mapNotNull null
        type to byDirection.orEmpty().map { (dir, times) -> directionLabel(dir) to times.orEmpty() }.toMap()
    }.toMap()

    val facilityList = facilities.orEmpty().mapNotNull { f ->
        val c = f.code ?: return@mapNotNull null
        val n = f.name ?: return@mapNotNull null
        Facility(facilityCode(c), n, f.where.orEmpty())
    }

    return StationDetail(
        station = st,
        firstLast = firstLastList,
        timetable = timetableMap,
        congestion = congestion.orEmpty(),
        facilities = facilityList
    )
}