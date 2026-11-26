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
import com.myway.app.ui.common.StationNames

/**
 * 화면 확인용 가짜 AI 해석. 진짜는 메인 서버 → AI 서비스(Gemini)가 한다.
 * 시험 문장:
 *  - "여의도에서 을지로3가까지 환승 적게"  → 해석 확인(2-5)
 *  - "여의에서 시청까지"                  → 역 후보(2-8)
 *  - "엘리베이터만 타고 가고 싶어"          → 미지원(2-9)
 *  - "연결실패"                           → AI 연결 실패(2-10)
 *  - "강남 가고 싶어"                      → 도착만 이해 → 출발 고르기
 *  - "왕십리에서 환승 적게 사당으로"          → 사이에 조건이 끼어도 출발·도착 인식
 *  - "아무말 대잔치"                       → 이해 못 함 → 예시 문장 제안
 */
class FakeConditionRepository(
    private val stations: StationRepository = RepositoryProvider.stationRepository()
) : ConditionRepository {

    override suspend fun interpret(userMessage: String, current: RouteCondition?): Resource<InterpretResult> {
        delay(1200)
        var text = userMessage.trim()
        if ("연결실패" in text.replace(" ", "")) return Resource.Failure(AppError.AiUnavailable)
        if (UNSUPPORTED.any { it in text }) return Resource.Failure(AppError.UnsupportedCondition)

        val all = (stations.getStations() as? Resource.Success)?.data.orEmpty()
        if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) return english(userMessage.trim(), current, all)
        // 음성 인식은 "을지로 3가"처럼 띄어 씀 → 역 이름 안의 빈칸을 붙여서 "3가"만 잘려 나가지 않게
        text = joinStationNames(text, all)
        // 1) 문장 속 실제 역 이름을 위치 순서대로 찾음 → "왕십리에서 환승 적게 사당으로"처럼 사이에 조건이 끼어도 됨
        //    역 뒤에 '에서·부터·출발'이 오면 출발, 아니면 도착. 표시 없이 역이 둘이면 앞 = 출발, 뒤 = 도착
        val found = findStations(text, all)
        var originRaw: String? = found.firstOrNull { (n, at) -> ORIGIN_MARK.containsMatchIn(text.substring(at + n.length)) }?.first
        var destRaw: String? = found.firstOrNull { it.first != originRaw }?.first
        if (originRaw == null && found.size >= 2) { originRaw = found[0].first; destRaw = found[1].first }
        // 2) 목록에 없는 말(오타·줄임말 "여의")은 "A에서 B까지" 틀로 잡아 후보를 보여 줌. 조건 낱말은 역으로 안 봄
        val m = Regex("(\\S+?)역?\\s*에서\\s*(\\S+?)역?(?:까지|(?:으로|로)\\s*가|\\s|$)").find(text)
        fun plain(w: String?) = w?.trim()?.takeIf { it.isNotEmpty() && CONDITION_WORDS.none { c -> c in it } }
        if (originRaw == null) originRaw = plain(m?.groupValues?.get(1))
        if (destRaw == null) destRaw = plain(m?.groupValues?.get(2))?.takeIf { it != originRaw }
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

    /**
     * 영어 문장: "from Yeouido to Gangnam, fewer transfers", "Gangnam to City Hall within 40 min" 등.
     * 역은 한국어 이름으로 바꿔 넘겨서 뒤 흐름(역 확정·후보)은 한국어와 똑같이 탄다.
     */
    /** "을지로 3가", "동대문 역사 문화 공원" → 역 이름 그대로 붙임 (긴 이름부터) */
    private fun joinStationNames(text: String, all: List<Station>): String {
        var t = text
        all.map { it.name }.distinct().filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { n ->
            val spaced = n.toCharArray().joinToString("\\s*") { Regex.escape(it.toString()) }
            t = Regex(spaced).replace(t, n)
        }
        return t
    }

    /** 문장 속 역 이름과 위치 (긴 이름 먼저, 겹치지 않게 → "서울역"과 "역" 같은 겹침 방지), 앞에서부터 순서대로 */
    private fun findStations(text: String, all: List<Station>): List<Pair<String, Int>> {
        val taken = mutableListOf<IntRange>()
        val out = mutableListOf<Pair<String, Int>>()
        all.map { it.name }.distinct().sortedByDescending { it.length }.forEach { n ->
            var i = text.indexOf(n)
            while (i >= 0) {
                val r = i until i + n.length
                if (taken.none { it.first <= r.last && r.first <= it.last }) { taken += r; out += n to i }
                i = text.indexOf(n, i + 1)
            }
        }
        return out.sortedBy { it.second }
    }

    private fun english(text: String, current: RouteCondition?, all: List<Station>): Resource<InterpretResult> {
        val t = text.lowercase()
        if (UNSUPPORTED_EN.any { it in t }) return Resource.Failure(AppError.UnsupportedCondition)
        val found = StationNames.findIn(text)
        var originRaw: String? = null
        var destRaw: String? = null
        found.forEach { (ko, at) ->
            val before = t.substring(0, at).trimEnd().substringAfterLast(' ')
            when {
                before == "from" || before == "leaving" -> originRaw = ko
                before in setOf("to", "for", "towards", "toward", "reach") -> destRaw = ko
                originRaw == null && found.size > 1 -> originRaw = ko
                else -> destRaw = destRaw ?: ko
            }
        }
        originRaw = originRaw ?: current?.origin?.rawName
        destRaw = destRaw ?: current?.destination?.rawName
        fun matches(raw: String?) = if (raw == null) emptyList() else all.filter { it.name == raw }
        fun slot(raw: String?) = raw?.let { r -> StationSlot(rawName = r, suggestedName = null, station = all.firstOrNull { it.name == r }) }

        val order = mutableListOf<Criterion>()
        if (Regex("(fewer|fewest|least|less|minimum|min) transfers?").containsMatchIn(t)) order += Criterion.TRANSFERS
        if (Regex("fast|quick|shortest|hurry").containsMatchIn(t)) order += Criterion.DURATION
        if (Regex("cheap|low(est)? fare|save money|lower fare").containsMatchIn(t)) order += Criterion.FARE
        if (order.isNotEmpty() && Criterion.DURATION !in order) order += Criterion.DURATION
        val pref = if (order.isEmpty()) current?.preference else Preference(PreferenceMode.LEXICOGRAPHIC, order.distinct().take(3), null)

        val condition = RouteCondition(
            origin = slot(originRaw),
            destination = slot(destRaw),
            constraints = Constraints(
                maxDurationMinutes = Regex("(?:within|in|under) (\\d+) ?min").find(t)?.groupValues?.get(1)?.toIntOrNull()
                    ?: current?.constraints?.maxDurationMinutes,
                maxTransfers = when {
                    Regex("no transfers?|without (a )?transfers?|direct").containsMatchIn(t) -> 0
                    else -> Regex("(?:at most|max(?:imum)?|up to) (\\d+) transfers?").find(t)?.groupValues?.get(1)?.toIntOrNull()
                        ?: current?.constraints?.maxTransfers
                },
                maxFareKrw = Regex("(?:under|below|less than|up to) (?:₩|krw ?)?([\\d,]+) ?(?:won|krw)").find(t)?.groupValues?.get(1)
                    ?.replace(",", "")?.toIntOrNull() ?: current?.constraints?.maxFareKrw
            ),
            preference = pref
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
        /** 역 바로 뒤에 오면 출발역 */
        private val ORIGIN_MARK = Regex("^역?\\s*(에서|부터|출발)")
        /** "A에서 B" 틀에서 역으로 보면 안 되는 조건 낱말 */
        private val CONDITION_WORDS = listOf("환승", "갈아", "빨리", "빠른", "최단", "급해", "싸게", "저렴", "요금", "분", "원", "이내", "없이", "출발", "가고", "가줘", "갈래", "싶어")
        private val UNSUPPORTED = listOf("엘리베이터", "에스컬레이터", "계단", "화장실", "편의점", "자리")
        private val UNSUPPORTED_EN = listOf("elevator", "escalator", "stairs", "restroom", "toilet", "convenience store", "seat")
    }
}
