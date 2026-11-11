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
        safeApiCall { api.getStations().toDomain() }

    override suspend fun searchStations(query: String): Resource<List<Station>> =
        when (val result = safeApiCall { api.searchStations(query).toDomain() }) {
            is Resource.Success ->
                if (result.data.isEmpty()) Resource.Failure(AppError.StationNotFound(query))
                else result
            is Resource.Failure -> result
        }
}