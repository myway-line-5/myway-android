package com.myway.app.domain.model

enum class PreferenceMode { WEIGHTED, LEXICOGRAPHIC }

enum class Criterion { DURATION, TRANSFERS, FARE }

data class Preference(
    val mode: PreferenceMode,
    val priority: List<Criterion>,        // 비교 순서. 중복 없는 1~3개
    val weights: Map<Criterion, Double>?  // WEIGHTED일 때 사용. LEXICOGRAPHIC이면 null 가능
)