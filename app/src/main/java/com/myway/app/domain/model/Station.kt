package com.myway.app.domain.model

data class Station(
    val id: String,        // 서버가 정한 역 ID. 노선별로 다름 (역명만으로 구분하면 안 됨)
    val name: String,
    val line: String,
    val isActive: Boolean  // 데이터상 서비스 대상 여부. 당일 막차 종료와는 다른 개념
)