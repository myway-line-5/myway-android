package com.myway.app.data.repository

import com.myway.app.data.remote.MyWayApi
import com.myway.app.data.remote.dto.InterpretRequestDto
import com.myway.app.data.remote.dto.StationResolveRequestDto
import com.myway.app.data.remote.dto.toDomain
import com.myway.app.data.remote.safeApiCall
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.Station
import com.myway.app.domain.model.StationSlot

class RealConditionRepository(
    private val api: MyWayApi
) : ConditionRepository {

    // current 는 쓰지 않는다: 직전 조건은 메인 서버가 세션(X-Session-ID)에 들고 있다
    override suspend fun interpret(
        userMessage: String,
        current: RouteCondition?
    ): Resource<InterpretResult> {
        val body = when (val res = safeApiCall { api.interpret(InterpretRequestDto(userMessage)) }) {
            is Resource.Failure -> return res
            is Resource.Success -> res.data
        }
        android.util.Log.d("MyWayAi", "result=${body.result} code=${body.errorCode} provider=${body.aiProvider}")

        return when (body.result) {
            "success" -> {
                val condition = body.conditions?.toDomain()
                    ?: return Resource.Failure(AppError.Server)
                Resource.Success(
                    InterpretResult(
                        condition = condition,
                        originMatches = matches(condition.origin),
                        destinationMatches = matches(condition.destination)
                    )
                )
            }
            // 지하철과 무관한 문장, 모순된 조건 등 -> "이해하지 못했어요" 안내
            "business_failure" -> Resource.Failure(AppError.UnsupportedCondition)
            // 시간 초과, AI 장애, 늦은 응답 -> "AI 연결 실패" 안내 (기존 조건 유지)
            "communication_failure" -> Resource.Failure(AppError.AiUnavailable)
            else -> Resource.Failure(AppError.Server)
        }
    }

    // 사용자가 쓴 이름으로 서버가 찾은 후보 역. 없으면 오타 교정 제안의 후보를 쓴다
    private suspend fun matches(slot: StationSlot?): List<Station> {
        if (slot == null) return emptyList()
        val res = safeApiCall {
            val r = api.resolveStation(StationResolveRequestDto(slot.rawName, slot.suggestedName))
            val list = r.candidates.orEmpty().ifEmpty { r.suggestedCandidates.orEmpty() }
            list.mapNotNull { it.toDomain() }
        }
        return (res as? Resource.Success)?.data.orEmpty()
    }
}