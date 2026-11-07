package com.myway.app.data.repository

import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Station

class FakeStationRepository : StationRepository {

    // 가짜 역 데이터. stationId는 노선별로 다름 (같은 이름이어도 노선이 다르면 다른 역)
    private val stations = listOf(
        Station("201", "시청", "2호선", true),
        Station("202", "을지로입구", "2호선", true),
        Station("203", "을지로3가", "2호선", true),
        Station("222", "강남", "2호선", true),
        Station("216", "잠실", "2호선", true),
        Station("533", "을지로3가", "3호선", true),   // 동명 다른 노선 예시
        Station("510", "왕십리", "5호선", true),
        Station("539", "여의도", "5호선", true),
        Station("930", "여의도", "9호선", true),
        Station("999", "시험용비활성역", "9호선", false)  // 비활성 역 예시
    )

    override suspend fun getStations(): Resource<List<Station>> {
        return Resource.Success(stations)
    }

    override suspend fun searchStations(query: String): Resource<List<Station>> {
        val result = stations.filter { it.name.contains(query.trim()) }
        return if (result.isEmpty()) {
            Resource.Failure(AppError.StationNotFound(query))
        } else {
            Resource.Success(result)
        }
    }
}