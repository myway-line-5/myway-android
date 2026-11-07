package com.myway.app.domain.model

sealed class AppError {

    // 입력·검색 관련
    data class StationNotFound(val query: String) : AppError()   // 검색 결과 없음 (화면 27)
    data class StationInactive(val station: Station) : AppError() // 비활성 역 (화면 29)
    object AmbiguousStation : AppError()                          // 모호한 역, 후보 선택 (화면 42)

    // 조건·경로 관련
    object UnsupportedCondition : AppError()  // 미지원 조건 (화면 43)
    object RouteNotFound : AppError()         // 제한을 만족하는 경로 없음

    // 통신 관련
    object AiUnavailable : AppError()  // AI 통신 실패 (화면 44). 기존 조건 유지
    object Network : AppError()        // 네트워크 끊김, 시간 초과 (화면 54)
    object Server : AppError()         // 그 외 서버 오류 (500, 비정상 응답)

    // Phase 2
    object SpeechRecognitionFailed : AppError() // 음성 인식 실패 (화면 41)
}