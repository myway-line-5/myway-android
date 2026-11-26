package com.myway.app.data.remote

import com.myway.app.data.remote.dto.InterpretRequestDto
import com.myway.app.data.remote.dto.InterpretResponseDto
import com.myway.app.data.remote.dto.RouteSearchRequestDto
import com.myway.app.data.remote.dto.RouteSearchResponseDto
import com.myway.app.data.remote.dto.StationDetailResponseDto
import com.myway.app.data.remote.dto.StationListResponseDto
import com.myway.app.data.remote.dto.StationResolveRequestDto
import com.myway.app.data.remote.dto.StationResolveResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MyWayApi {

    @GET("v1/stations")
    suspend fun getStations(): StationListResponseDto

    @GET("v1/stations/search")
    suspend fun searchStations(@Query("q") query: String): StationListResponseDto

    @GET("v1/stations/{id}/detail")
    suspend fun getStationDetail(@Path("id") stationId: String): StationDetailResponseDto

    @POST("v1/routes/search")
    suspend fun searchRoute(@Body request: RouteSearchRequestDto): RouteSearchResponseDto

    @POST("v1/conditions/interpret")
    suspend fun interpret(@Body request: InterpretRequestDto): InterpretResponseDto

    @POST("v1/stations/resolve")
    suspend fun resolveStation(@Body request: StationResolveRequestDto): StationResolveResponseDto
}