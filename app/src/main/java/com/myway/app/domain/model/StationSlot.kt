package com.myway.app.domain.model

data class StationSlot(
    val rawName: String,         // 현재 해석 기준의 역 표현. 사용자가 직접 고르면 선택한 역명으로 바뀜
    val suggestedName: String?,  // AI의 오타 교정 제안. 확정이 아님
    val station: Station?        // 메인 서버가 확정한 역. null이면 아직 확정 전
)