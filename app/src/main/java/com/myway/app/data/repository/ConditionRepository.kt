package com.myway.app.data.repository

import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.Station

/**
 * AI 해석 결과 (설계서 4.2.3).
 * condition: 메인이 돌려준 현재 편집 조건 전체 (수정되면 통째로 교체)
 * *Matches: raw_name 으로 메인이 찾은 역 후보. 여러 장소면 앱이 후보 선택(화면 2-8)을 띄운다.
 */
data class InterpretResult(
    val condition: RouteCondition,
    val originMatches: List<Station> = emptyList(),
    val destinationMatches: List<Station> = emptyList()
)

interface ConditionRepository {
    /**
     * 문장 + 현재 조건(최초·새 검색은 null)을 메인 서버로 보낸다. 앱은 AI 주소·키를 모른다.
     * 실패: AppError.UnsupportedCondition(2-9) / AiUnavailable(2-10) / Network
     */
    suspend fun interpret(userMessage: String, current: RouteCondition?): Resource<InterpretResult>
}
