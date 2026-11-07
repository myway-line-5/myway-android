package com.myway.app.data.repository

import com.myway.app.domain.model.Station

interface StationRepository {

    // 전체 역 목록 (2·5·9호선)
    suspend fun getStations(): Resource<List<Station>>

    // 입력어로 역 검색. 결과가 없으면 AppError.StationNotFound
    suspend fun searchStations(query: String): Resource<List<Station>>
}