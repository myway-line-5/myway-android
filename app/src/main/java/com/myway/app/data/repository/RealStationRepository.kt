package com.myway.app.data.repository

import com.myway.app.data.remote.MyWayApi
import com.myway.app.data.remote.dto.toDomain
import com.myway.app.data.remote.safeApiCall
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Station

class RealStationRepository(
    private val api: MyWayApi
) : StationRepository {

    override suspend fun getStations(): Resource<List<Station>> =
        safeApiCall { api.getStations().toDomain().onePerLine() }

    override suspend fun searchStations(query: String): Resource<List<Station>> =
        when (val result = safeApiCall { api.searchStations(query).toDomain().onePerLine() }) {
            is Resource.Success ->
                if (result.data.isEmpty()) Resource.Failure(AppError.StationNotFound(query))
                else result
            is Resource.Failure -> result
        }
}

/**
 * 지선이 갈라지는 역(성수·신도림·강동)은 서버에 점이 2개라서 같은 호선이 두 번 나온다.
 * 같은 이름+호선이면 하나만 남긴다. 본선(MAIN)이 있으면 그걸 우선 사용.
 * (두 점은 서버에서 서로 이어져 있어서 어느 쪽 ID로 탐색해도 길이 찾아진다)
 */
private fun List<Station>.onePerLine(): List<Station> =
    groupBy { it.name to it.line }
        .map { (_, group) -> group.firstOrNull { "_MAIN_" in it.stationId } ?: group.first() }