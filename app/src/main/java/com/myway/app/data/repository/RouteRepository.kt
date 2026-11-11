package com.myway.app.data.repository

import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.RouteResult

interface RouteRepository {
    // 조건에 맞는 경로 목록. 기준(최단시간/최소환승/최소비용)별로 한 개씩 담긴다.
    suspend fun findRoutes(condition: RouteCondition): Resource<List<RouteResult>>
}