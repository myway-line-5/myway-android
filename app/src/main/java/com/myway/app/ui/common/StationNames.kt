package com.myway.app.ui.common

/**
 * 역 이름 표시 (영어 화면용). 앱 안에서는 한국어 이름이 기준(검색·저장·서버)이고, 보여 줄 때만 바꾼다.
 * 노선도 그림 속 글자는 그림이라 바꾸지 않음. 서버가 영문명을 주면 이 표 대신 그 값을 쓰면 됨.
 */
object StationNames {

    /** MainActivity 가 화면 언어로 정함 */
    @Volatile var english = false

    /** 화면에 보일 역 이름 */
    fun of(ko: String): String = if (english) EN[ko] ?: ko else ko

    /** 영어 이름 → 한국어 이름 (영어 검색·AI 영어 문장용). 대소문자·띄어쓰기·마침표 무시 */
    fun fromEnglish(text: String): String? = BY_KEY[key(text)]

    /**
     * 영어 문장 속 역 이름들 → (한국어 이름, 위치) 앞에서부터 순서대로.
     * 긴 이름부터 찾고 겹치는 짧은 이름은 버림 ("Jamsilsaenae" 안의 "Jamsil" 등)
     */
    fun findIn(sentence: String): List<Pair<String, Int>> {
        val s = sentence.lowercase()
        val taken = mutableListOf<IntRange>()
        val found = mutableListOf<Pair<String, Int>>()
        EN.entries.sortedByDescending { it.value.length }.forEach { (ko, en) ->
            var i = s.indexOf(en.lowercase())
            while (i >= 0) {
                val r = i until i + en.length
                if (taken.none { it.first <= r.last && r.first <= it.last }) { taken += r; found += ko to i }
                i = s.indexOf(en.lowercase(), i + 1)
            }
        }
        return found.sortedBy { it.second }
    }

    /** 영문 이름에 이 글자가 들어가는 역 (검색) */
    fun searchEnglish(q: String): Set<String> {
        val k = key(q)
        if (k.isEmpty()) return emptySet()
        return EN.filterValues { key(it).contains(k) }.keys
    }

    /** 방면 표시: "개화행" → "To Gaehwa", 순환선 등 */
    fun direction(ko: String): String {
        if (!english) return ko
        DIRS[ko]?.let { return it }
        return if (ko.endsWith("행")) "To " + of(ko.dropLast(1)) else ko
    }

    private fun key(t: String) = t.lowercase().filter { it.isLetterOrDigit() }

    private val DIRS = mapOf("내선순환" to "Inner circle", "외선순환" to "Outer circle", "상행" to "Upbound", "하행" to "Downbound")

    private val EN: Map<String, String> = mapOf(
        "가양" to "Gayang", "강남" to "Gangnam", "강동" to "Gangdong", "강변" to "Gangbyeon", "강일" to "Gangil",
        "개롱" to "Gaerong", "개화" to "Gaehwa", "개화산" to "Gaehwasan", "거여" to "Geoyeo", "건대입구" to "Konkuk Univ.",
        "고덕" to "Godeok", "고속터미널" to "Express Bus Terminal", "공덕" to "Gongdeok", "공항시장" to "Gonghang Market",
        "광나루" to "Gwangnaru", "광화문" to "Gwanghwamun", "교대" to "Seoul Nat'l Univ. of Education",
        "구로디지털단지" to "Guro Digital Complex", "구반포" to "Gubanpo", "구의" to "Guui", "국회의사당" to "National Assembly",
        "군자" to "Gunja", "굽은다리" to "Gubeundari", "길동" to "Gil-dong", "김포공항" to "Gimpo Int'l Airport",
        "까치산" to "Kkachisan", "낙성대" to "Nakseongdae", "노들" to "Nodeul", "노량진" to "Noryangjin", "답십리" to "Dapsimni",
        "당산" to "Dangsan", "대림" to "Daerim", "도림천" to "Dorimcheon", "동대문역사문화공원" to "Dongdaemun History & Culture Park",
        "동작" to "Dongjak", "둔촌동" to "Dunchon-dong", "둔촌오륜" to "Dunchon Oryun", "등촌" to "Deungchon", "뚝섬" to "Ttukseom",
        "마곡" to "Magok", "마곡나루" to "Magongnaru", "마장" to "Majang", "마천" to "Macheon", "마포" to "Mapo",
        "명일" to "Myeongil", "목동" to "Mok-dong", "문래" to "Mullae", "미사" to "Misa", "발산" to "Balsan", "방배" to "Bangbae",
        "방이" to "Bangi", "방화" to "Banghwa", "봉은사" to "Bongeunsa", "봉천" to "Bongcheon", "사당" to "Sadang",
        "사평" to "Sapyeong", "삼성" to "Samseong", "삼성중앙" to "Samseong Jungang", "삼전" to "Samjeon",
        "상왕십리" to "Sangwangsimni", "상일동" to "Sangil-dong", "샛강" to "Saetgang", "서대문" to "Seodaemun",
        "서울대입구" to "Seoul Nat'l Univ.", "서초" to "Seocho", "석촌" to "Seokchon", "석촌고분" to "Seokchon Gobun",
        "선릉" to "Seolleung", "선유도" to "Seonyudo", "선정릉" to "Seonjeongneung", "성수" to "Seongsu", "송정" to "Songjeong",
        "송파나루" to "Songpanaru", "시청" to "City Hall", "신금호" to "Singeumho", "신길" to "Singil", "신논현" to "Sinnonhyeon",
        "신답" to "Sindap", "신당" to "Sindang", "신대방" to "Sindaebang", "신도림" to "Sindorim", "신림" to "Sillim",
        "신목동" to "Sinmok-dong", "신반포" to "Sinbanpo", "신방화" to "Sinbanghwa", "신설동" to "Sinseol-dong", "신정" to "Sinjeong",
        "신정네거리" to "Sinjeongnegeori", "신촌" to "Sinchon", "아차산" to "Achasan", "아현" to "Ahyeon", "애오개" to "Aeogae",
        "양천구청" to "Yangcheon-gu Office", "양천향교" to "Yangcheon Hyanggyo", "양평" to "Yangpyeong", "언주" to "Eonju",
        "여의나루" to "Yeouinaru", "여의도" to "Yeouido", "역삼" to "Yeoksam", "염창" to "Yeomchang",
        "영등포구청" to "Yeongdeungpo-gu Office", "영등포시장" to "Yeongdeungpo Market", "오금" to "Ogeum", "오목교" to "Omokgyo",
        "올림픽공원" to "Olympic Park", "왕십리" to "Wangsimni", "용답" to "Yongdap", "용두" to "Yongdu", "우장산" to "Ujangsan",
        "을지로3가" to "Euljiro 3-ga", "을지로4가" to "Euljiro 4-ga", "을지로입구" to "Euljiro 1-ga", "이대" to "Ewha Womans Univ.",
        "잠실" to "Jamsil", "잠실나루" to "Jamsillaru", "잠실새내" to "Jamsilsaenae", "장한평" to "Janghanpyeong",
        "종로3가" to "Jongno 3-ga", "종합운동장" to "Sports Complex", "중앙보훈병원" to "VHS Medical Center", "증미" to "Jeungmi",
        "천호" to "Cheonho", "청구" to "Cheonggu", "충정로" to "Chungjeongno", "하남검단산" to "Hanam Geomdansan",
        "하남시청" to "Hanam City Hall", "하남풍산" to "Hanam Pungsan", "한성백제" to "Hanseong Baekje", "한양대" to "Hanyang Univ.",
        "합정" to "Hapjeong", "행당" to "Haengdang", "홍대입구" to "Hongik Univ.", "화곡" to "Hwagok", "흑석" to "Heukseok",
        "시험용비활성역" to "Test (inactive)"
    )

    private val BY_KEY: Map<String, String> = EN.entries.associate { key(it.value) to it.key }
}

/** "강남".displayName() → 영어 화면이면 "Gangnam" */
fun String.displayName(): String = StationNames.of(this)
