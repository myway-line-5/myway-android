package com.myway.app.data.repository

import com.myway.app.data.remote.MyWayApi
import com.myway.app.data.remote.dto.RouteSearchRequestDto
import com.myway.app.data.remote.dto.RouteSearchResponseDto
import com.myway.app.data.remote.dto.toDomain
import com.myway.app.data.remote.safeApiCall
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteResult
import com.myway.app.domain.model.Station

class RealRouteRepository(
    private val api: MyWayApi
) : RouteRepository {

    override suspend fun findRoutes(condition: RouteCondition): Resource<List<RouteResult>> {
        val origin = condition.origin?.station
        val destination = condition.destination?.station
        if (origin == null || destination == null) {
            return Resource.Failure(AppError.UnsupportedCondition)
        }
        // 노선도에서 만든 임시 역(서버에 없는 역)은 서버로 보내지 않는다
        if (origin.stationId.startsWith("map:") || destination.stationId.startsWith("map:")) {
            return Resource.Failure(AppError.UnsupportedCondition)
        }

        // TODO: condition.constraints / preference 는 서버 요청에 실을 자리가 생기면 추가
        return when (val res = safeApiCall {
            api.searchRoute(RouteSearchRequestDto(origin.stationId, destination.stationId))
        }) {
            is Resource.Failure -> res
            is Resource.Success -> res.data.toResource(origin, destination)
        }
    }

    // 서버 status/error_code -> 앱 결과
    private fun RouteSearchResponseDto.toResource(
        origin: Station,
        destination: Station
    ): Resource<List<RouteResult>> = when (status) {
        "ok" -> {
            val result = route?.toDomain()
            if (result != null) Resource.Success(listOf(result))
            else Resource.Failure(AppError.Server)
        }
        "no_route" -> Resource.Failure(AppError.RouteNotFound)
        "incomplete" -> Resource.Failure(AppError.Server)
        "error" -> when (errorCode) {
            "STATION_INACTIVE" ->
                Resource.Failure(AppError.StationInactive(if (!origin.isActive) origin else destination))
            "STATION_NOT_FOUND" -> Resource.Failure(AppError.StationNotFound(origin.name))
            else -> Resource.Failure(AppError.UnsupportedCondition)
        }
        else -> Resource.Failure(AppError.Server)
    }
}