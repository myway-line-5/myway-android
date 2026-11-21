package com.myway.app.data.repository

import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Constraints
import com.myway.app.domain.model.Criterion
import com.myway.app.domain.model.Preference
import com.myway.app.domain.model.PreferenceMode
import com.myway.app.domain.model.RouteCondition
import com.myway.app.domain.model.Station
import com.myway.app.domain.model.StationSlot
import kotlinx.coroutines.delay

/**
 * 화면 확인용 가짜 AI 해석. 진짜는 메인 서버 → AI 서비스(Gemini)가 한다.
 * 시험 문장:
 *  - "여의도에서 을지로3가까지 환승 적게"  → 해석 확인(2-5)
 *  - "여의에서 시청까지"                  → 역 후보(2-8)
 *  - "엘리베이터만 타고 가고 싶어"          → 미지원(2-9)
 *  - "연결실패"                           → AI 연결 실패(2-10)
 *  - "강남 가고 싶어"                      → 도착만 이해 → 출발 고르기
 *  - "아무말 대잔치"                       → 이해 못 함 → 예시 문장 제안
 */
class FakeConditionRepository(
    private val stations: StationRepository = RepositoryProvider.stationRepository()
) : ConditionRepository {

    override suspend fun interpret(userMessage: String, current: RouteCondition?): Resource<InterpretResult> {
        delay(1200)
        val text = userMessage.trim()
        if ("연결실패" in text.replace(" ", "")) return Resource.Failure(AppError.AiUnavailable)
        if (UNSUPPORTED.any { it in text }) return Resource.Failure(AppError.UnsupportedCondition)

        val all = (stations.getStations() as? Resource.Success)?.data.orEmpty()
        val m = Regex("(\\S+?)역?\\s*에서\\s*(\\S+?)역?(?:까지|(?:으로|로)\\s*가|\\s|$)").find(text)
        // "A에서 B까지"가 아니면 문장 속 역 이름을 찾는다: "강남 가고 싶어" → 도착, "강남에서 출발" → 출발
        var originRaw = m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        var destRaw = m?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
        if (m == null) {
            val named = all.map { it.name }.distinct().sortedByDescending { it.length }.firstOrNull { it in text }
            if (named != null) {
                if (Regex(Regex.escape(named) + "역?\\s*에서").containsMatchIn(text)) originRaw = named else destRaw = named
            }
        }
        originRaw = originRaw ?: current?.origin?.rawName
        destRaw = destRaw ?: current?.destination?.rawName
        fun matches(raw: String?) = if (raw == null) emptyList() else all.filter { raw in it.name }
        fun slot(raw: String?) = raw?.let { r ->
            StationSlot(rawName = r, suggestedName = null, station = all.firstOrNull { it.name == r })
        }

        val condition = RouteCondition(
            origin = slot(originRaw),
            destination = slot(destRaw),
            constraints = Constraints(
                maxDurationMinutes = Regex("(\\d+)\\s*분\\s*(?:이내|안에|안으로)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: current?.constraints?.maxDurationMinutes,
                maxTransfers = when {
                    "환승 없이" in text || "갈아타지 않" in text -> 0
                    else -> Regex("환승\\s*(\\d+)\\s*(?:번|회)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        ?: current?.constraints?.maxTransfers
                },
                maxFareKrw = Regex("(\\d[\\d,]*)\\s*원\\s*(?:이하|안)").find(text)?.groupValues?.get(1)
                    ?.replace(",", "")?.toIntOrNull() ?: current?.constraints?.maxFareKrw
            ),
            preference = preference(text) ?: current?.preference
        )
        return Resource.Success(InterpretResult(condition, matches(originRaw), matches(destRaw)))
    }

    private fun preference(text: String): Preference? {
        val order = mutableListOf<Criterion>()
        if (Regex("환승\\s*(적게|적은|최소)|갈아타기").containsMatchIn(text)) order += Criterion.TRANSFERS
        if (Regex("빨리|빠른|최단|급해").containsMatchIn(text)) order += Criterion.DURATION
        if (Regex("싸게|저렴|요금|돈").containsMatchIn(text)) order += Criterion.FARE
        if (order.isEmpty()) return null
        if (Criterion.DURATION !in order) order += Criterion.DURATION   // 동률이면 시간 짧은 쪽
        return Preference(PreferenceMode.LEXICOGRAPHIC, order.distinct().take(3), null)
    }

    companion object {
        private val UNSUPPORTED = listOf("엘리베이터", "에스컬레이터", "계단", "화장실", "편의점", "자리")
    }
}
