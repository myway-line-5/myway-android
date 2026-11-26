package com.myway.app.data.repository

import com.myway.app.data.remote.MyWayApi
import com.myway.app.data.remote.dto.toDomain
import com.myway.app.data.remote.safeApiCall
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle
import java.time.DayOfWeek
import java.time.LocalDate

/** 역 상세(첫차·막차·시간표·편의시설)를 메인 서버에서 받는다. 혼잡도는 서버 데이터가 생기면 자동으로 채워짐 */
class RealStationDetailRepository(
    private val api: MyWayApi
) : StationDetailRepository {

    override suspend fun getDetail(station: Station): Resource<StationDetail> {
        val id = serverId(station) ?: return Resource.Failure(AppError.StationNotFound(station.name))
        val today = when (LocalDate.now().dayOfWeek) {
            DayOfWeek.SATURDAY -> DayType.SATURDAY
            DayOfWeek.SUNDAY -> DayType.HOLIDAY
            else -> DayType.WEEKDAY
        }
        return when (val r = safeApiCall { api.getStationDetail(id) }) {
            is Resource.Success -> Resource.Success(r.data.toDomain(station, today))
            is Resource.Failure -> r
        }
    }

    /** 노선도 임시 역("map:2:강남")은 서버에서 같은 이름·호선 역 ID를 찾아 씀 */
    private suspend fun serverId(s: Station): String? {
        if (!s.stationId.startsWith("map:")) return s.stationId
        val found = (safeApiCall { api.searchStations(s.name).toDomain() } as? Resource.Success)?.data.orEmpty()
            .filter { it.name == s.name || it.name.startsWith("${s.name}(") }
        return (found.firstOrNull { LineStyle.key(it.line) == LineStyle.key(s.line) } ?: found.firstOrNull())?.stationId
    }
}
