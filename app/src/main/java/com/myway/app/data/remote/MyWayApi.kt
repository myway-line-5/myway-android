package com.myway.app.data.remote

import com.myway.app.data.remote.dto.StationListResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

interface MyWayApi {

    @GET("v1/stations")
    suspend fun getStations(): StationListResponseDto

    @GET("v1/stations/search")
    suspend fun searchStations(@Query("q") query: String): StationListResponseDto
}