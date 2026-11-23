package com.myway.app.data.remote

import com.myway.app.data.remote.dto.RouteSearchRequestDto
import com.myway.app.data.remote.dto.RouteSearchResponseDto
import com.myway.app.data.remote.dto.StationListResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface MyWayApi {

    @GET("v1/stations")
    suspend fun getStations(): StationListResponseDto

    @GET("v1/stations/search")
    suspend fun searchStations(@Query("q") query: String): StationListResponseDto

    @POST("v1/routes/search")
    suspend fun searchRoute(@Body request: RouteSearchRequestDto): RouteSearchResponseDto
}