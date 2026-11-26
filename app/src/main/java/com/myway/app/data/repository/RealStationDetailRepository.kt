package com.myway.app.data.repository

import com.myway.app.data.remote.MyWayApi
import com.myway.app.data.remote.dto.toDomain
import com.myway.app.data.remote.safeApiCall
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle

class RealStationDetailRepository(
    private val api: MyWayApi
) : StationDetailRepository {

    override suspend fun getDetail(station: Station): Resource<StationDetail> {
        val real = resolve(station) ?: return Resource.Failure(AppError.StationNotFound(station.name))
        return when (val res = safeApiCall { api.getStationDetail(real.stationId) }) {
            is Resource.Failure -> res
            is Resource.Success -> Resource.Success(res.data.toDomain(real))
        }
    }

    // 노선도 임시 역 "map:{호선}:{이름}"은 서버 역 ID가 아니라서 이름으로 같은 호선의 서버 역을 찾는다
    private suspend fun resolve(s: Station): Station? {
        if (!s.stationId.startsWith("map:")) return s
        val name = s.stationId.split(":", limit = 3).getOrNull(2) ?: return null
        val res = safeApiCall { api.searchStations(name).toDomain() }
        val found = (res as? Resource.Success)?.data.orEmpty()
            // 서버 이름이 "흑석(중앙대입구)"처럼 괄호가 붙어도 같은 역으로 본다
            .filter { it.name == name || it.name.startsWith("$name(") }
        val lineKey = LineStyle.key(s.line)
        val sameLine = found.filter { LineStyle.key(it.line) == lineKey }
        return sameLine.firstOrNull { it.isActive } ?: sameLine.firstOrNull()
    }
}