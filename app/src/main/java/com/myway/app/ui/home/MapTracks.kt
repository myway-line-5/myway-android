package com.myway.app.ui.home

/**
 * 노선도(map_all.png) 위 실제 선로 모양: 인접한 두 역 사이를 노선 그림의 색을 따라 자동으로 추적한 좌표.
 * 추적 잡음(지글거림)은 Douglas-Peucker(10px)로 걷어 냄 — 노선 그림 선 두께 안에서만 단순화.
 * 경로 지도에서 역과 역을 직선 대신 이 선을 따라 그려, 굽은 구간도 노선도와 딱 맞게 겹친다.
 * 노선도 이미지를 바꾸면 이 표도 다시 만들어야 함 (MapStations 와 같음).
 */
object MapTracks {

    /** "노선|역A|역B" → [x0,y0,x1,y1,…] (역A → 역B 순서, 양 끝 = 역 좌표) */
    private val tracks: Map<String, IntArray> = mapOf(
        "2|시청|을지로입구" to intArrayOf(1668,1362,1741,1325),
        "2|을지로입구|을지로3가" to intArrayOf(1741,1325,1841,1299),
        "2|을지로3가|을지로4가" to intArrayOf(1841,1299,1994,1283),
        "2|을지로4가|동대문역사문화공원" to intArrayOf(1994,1283,2146,1297),
        "2|동대문역사문화공원|신당" to intArrayOf(2146,1297,2231,1308,2333,1352),
        "2|신당|상왕십리" to intArrayOf(2333,1352,2452,1419),
        "2|상왕십리|왕십리" to intArrayOf(2452,1419,2524,1463,2578,1520),
        "2|왕십리|한양대" to intArrayOf(2578,1520,2677,1640),
        "2|한양대|뚝섬" to intArrayOf(2677,1640,2719,1712),
        "2|뚝섬|성수" to intArrayOf(2719,1712,2762,1801),
        "2|성수|건대입구" to intArrayOf(2762,1801,2792,1858,2802,1938),
        "2|건대입구|구의" to intArrayOf(2802,1938,2814,2005),
        "2|구의|강변" to intArrayOf(2814,2005,2817,2080),
        "2|강변|잠실나루" to intArrayOf(2817,2080,2813,2191),
        "2|잠실나루|잠실" to intArrayOf(2813,2191,2800,2268),
        "2|잠실|잠실새내" to intArrayOf(2800,2268,2780,2346),
        "2|잠실새내|종합운동장" to intArrayOf(2780,2346,2760,2421,2720,2489),
        "2|종합운동장|삼성" to intArrayOf(2720,2489,2682,2564,2633,2618),
        "2|삼성|선릉" to intArrayOf(2633,2618,2524,2729),
        "2|선릉|역삼" to intArrayOf(2524,2729,2450,2782),
        "2|역삼|강남" to intArrayOf(2450,2782,2371,2830),
        "2|강남|교대" to intArrayOf(2371,2830,2248,2880),
        "2|교대|서초" to intArrayOf(2248,2880,2171,2899),
        "2|서초|방배" to intArrayOf(2171,2899,2096,2912),
        "2|방배|사당" to intArrayOf(2096,2912,1959,2917),
        "2|사당|낙성대" to intArrayOf(1959,2917,1867,2907),
        "2|낙성대|서울대입구" to intArrayOf(1867,2907,1753,2881),
        "2|서울대입구|봉천" to intArrayOf(1753,2881,1669,2847),
        "2|봉천|신림" to intArrayOf(1669,2847,1587,2806),
        "2|신림|신대방" to intArrayOf(1587,2806,1533,2770),
        "2|신대방|구로디지털단지" to intArrayOf(1533,2770,1470,2722),
        "2|구로디지털단지|대림" to intArrayOf(1470,2722,1411,2667),
        "2|대림|신도림" to intArrayOf(1411,2667,1312,2543),
        "2|신도림|문래" to intArrayOf(1312,2543,1259,2444),
        "2|문래|영등포구청" to intArrayOf(1259,2444,1229,2367),
        "2|영등포구청|당산" to intArrayOf(1229,2367,1190,2220,1183,2097),
        "2|당산|합정" to intArrayOf(1183,2097,1190,1946,1209,1894),
        "2|합정|홍대입구" to intArrayOf(1209,1894,1228,1810,1263,1746),
        "2|홍대입구|신촌" to intArrayOf(1263,1746,1314,1658),
        "2|신촌|이대" to intArrayOf(1314,1658,1368,1581),
        "2|이대|아현" to intArrayOf(1368,1581,1450,1495),
        "2|아현|충정로" to intArrayOf(1450,1495,1554,1416),
        "2|충정로|시청" to intArrayOf(1554,1416,1656,1355,1668,1362),
        "2|성수|용답" to intArrayOf(2762,1801,2896,1670),
        "2|용답|신답" to intArrayOf(2896,1670,2907,1637,2739,1463),
        "2|신답|용두" to intArrayOf(2739,1463,2595,1319),
        "2|용두|신설동" to intArrayOf(2595,1319,2467,1189),
        "2|신도림|도림천" to intArrayOf(1312,2543,1271,2538,1158,2426),
        "2|도림천|양천구청" to intArrayOf(1158,2426,1080,2347),
        "2|양천구청|신정네거리" to intArrayOf(1080,2347,951,2218),
        "2|신정네거리|까치산" to intArrayOf(951,2218,922,2178,919,2075),
        "5|방화|개화산" to intArrayOf(600,1302,600,1359),
        "5|개화산|김포공항" to intArrayOf(600,1359,600,1473),
        "5|김포공항|송정" to intArrayOf(600,1473,600,1660),
        "5|송정|마곡" to intArrayOf(600,1660,604,1749,629,1768),
        "5|마곡|발산" to intArrayOf(629,1768,693,1833),
        "5|발산|우장산" to intArrayOf(693,1833,762,1901),
        "5|우장산|화곡" to intArrayOf(762,1901,840,1979),
        "5|화곡|까치산" to intArrayOf(840,1979,919,2059),
        "5|까치산|신정" to intArrayOf(919,2059,964,2103),
        "5|신정|목동" to intArrayOf(964,2103,1017,2156),
        "5|목동|오목교" to intArrayOf(1017,2156,1079,2218),
        "5|오목교|양평" to intArrayOf(1079,2218,1135,2274),
        "5|양평|영등포구청" to intArrayOf(1135,2274,1217,2356),
        "5|영등포구청|영등포시장" to intArrayOf(1217,2356,1354,2371),
        "5|영등포시장|신길" to intArrayOf(1354,2371,1505,2372),
        "5|신길|여의도" to intArrayOf(1505,2372,1547,2356,1554,2292),
        "5|여의도|여의나루" to intArrayOf(1554,2292,1554,2229),
        "5|여의나루|마포" to intArrayOf(1554,2229,1554,2127),
        "5|마포|공덕" to intArrayOf(1554,2127,1554,1963),
        "5|공덕|애오개" to intArrayOf(1554,1963,1554,1743),
        "5|애오개|충정로" to intArrayOf(1554,1743,1554,1400),
        "5|충정로|서대문" to intArrayOf(1554,1400,1554,1320),
        "5|서대문|광화문" to intArrayOf(1554,1320,1554,1227),
        "5|광화문|종로3가" to intArrayOf(1554,1227,1573,1191,1841,1186),
        "5|종로3가|을지로4가" to intArrayOf(1841,1186,1973,1186,1989,1205,1994,1267),
        "5|을지로4가|동대문역사문화공원" to intArrayOf(1994,1267,1999,1300,2146,1318),
        "5|동대문역사문화공원|청구" to intArrayOf(2146,1318,2234,1335,2255,1355,2258,1494,2280,1514,2333,1520),
        "5|청구|신금호" to intArrayOf(2333,1520,2409,1520),
        "5|신금호|행당" to intArrayOf(2409,1520,2473,1520),
        "5|행당|왕십리" to intArrayOf(2473,1520,2620,1520),
        "5|왕십리|마장" to intArrayOf(2620,1520,2782,1520),
        "5|마장|답십리" to intArrayOf(2782,1520,2878,1520),
        "5|답십리|장한평" to intArrayOf(2878,1520,2955,1520),
        "5|장한평|군자" to intArrayOf(2955,1520,3032,1520),
        "5|군자|아차산" to intArrayOf(3032,1520,3120,1533,3127,1631),
        "5|아차산|광나루" to intArrayOf(3127,1631,3127,1743),
        "5|광나루|천호" to intArrayOf(3127,1743,3127,1956),
        "5|천호|강동" to intArrayOf(3127,1956,3127,2133),
        "5|강동|길동" to intArrayOf(3127,2133,3195,2065),
        "5|길동|굽은다리" to intArrayOf(3195,2065,3246,2014),
        "5|굽은다리|명일" to intArrayOf(3246,2014,3297,1963),
        "5|명일|고덕" to intArrayOf(3297,1963,3348,1912),
        "5|고덕|상일동" to intArrayOf(3348,1912,3399,1860),
        "5|상일동|강일" to intArrayOf(3399,1860,3450,1809),
        "5|강일|미사" to intArrayOf(3450,1809,3501,1758),
        "5|미사|하남풍산" to intArrayOf(3501,1758,3587,1738),
        "5|하남풍산|하남시청" to intArrayOf(3587,1738,3670,1738),
        "5|하남시청|하남검단산" to intArrayOf(3670,1738,3757,1738),
        "5|강동|둔촌동" to intArrayOf(3127,2133,3127,2321),
        "5|둔촌동|올림픽공원" to intArrayOf(3127,2321,3127,2473),
        "5|올림픽공원|방이" to intArrayOf(3127,2473,3127,2595),
        "5|방이|오금" to intArrayOf(3127,2595,3131,2683,3155,2698),
        "5|오금|개롱" to intArrayOf(3155,2698,3227,2698),
        "5|개롱|거여" to intArrayOf(3227,2698,3292,2698),
        "5|거여|마천" to intArrayOf(3292,2698,3353,2698),
        "9|개화|김포공항" to intArrayOf(508,1495,600,1494),
        "9|김포공항|공항시장" to intArrayOf(600,1494,673,1495),
        "9|공항시장|신방화" to intArrayOf(673,1495,756,1495),
        "9|신방화|마곡나루" to intArrayOf(756,1495,814,1496),
        "9|마곡나루|양천향교" to intArrayOf(814,1496,877,1507,885,1593),
        "9|양천향교|가양" to intArrayOf(885,1593,885,1651),
        "9|가양|증미" to intArrayOf(885,1651,885,1746),
        "9|증미|등촌" to intArrayOf(885,1746,889,1796,910,1823),
        "9|등촌|염창" to intArrayOf(910,1823,978,1891),
        "9|염창|신목동" to intArrayOf(978,1891,1046,1959),
        "9|신목동|선유도" to intArrayOf(1046,1959,1113,2026),
        "9|선유도|당산" to intArrayOf(1113,2026,1171,2086),
        "9|당산|국회의사당" to intArrayOf(1171,2086,1362,2268,1426,2275),
        "9|국회의사당|여의도" to intArrayOf(1426,2275,1554,2276),
        "9|여의도|샛강" to intArrayOf(1554,2276,1676,2276),
        "9|샛강|노량진" to intArrayOf(1676,2276,1807,2276),
        "9|노량진|노들" to intArrayOf(1807,2276,1854,2275),
        "9|노들|흑석" to intArrayOf(1854,2275,1911,2275),
        "9|흑석|동작" to intArrayOf(1911,2275,1959,2276),
        "9|동작|구반포" to intArrayOf(1959,2276,2000,2279,2074,2347),
        "9|구반포|신반포" to intArrayOf(2074,2347,2129,2402),
        "9|신반포|고속터미널" to intArrayOf(2129,2402,2207,2489),
        "9|고속터미널|사평" to intArrayOf(2207,2489,2308,2489),
        "9|사평|신논현" to intArrayOf(2308,2489,2371,2488),
        "9|신논현|언주" to intArrayOf(2371,2488,2450,2489),
        "9|언주|선정릉" to intArrayOf(2450,2489,2524,2488),
        "9|선정릉|삼성중앙" to intArrayOf(2524,2488,2592,2489),
        "9|삼성중앙|봉은사" to intArrayOf(2592,2489,2658,2489),
        "9|봉은사|종합운동장" to intArrayOf(2658,2489,2736,2489),
        "9|종합운동장|삼전" to intArrayOf(2736,2489,2806,2489),
        "9|삼전|석촌고분" to intArrayOf(2806,2489,2875,2489),
        "9|석촌고분|석촌" to intArrayOf(2875,2489,2950,2489),
        "9|석촌|송파나루" to intArrayOf(2950,2489,3006,2489),
        "9|송파나루|한성백제" to intArrayOf(3006,2489,3065,2489),
        "9|한성백제|올림픽공원" to intArrayOf(3065,2489,3127,2489),
        "9|올림픽공원|둔촌오륜" to intArrayOf(3127,2489,3215,2395),
        "9|둔촌오륜|중앙보훈병원" to intArrayOf(3215,2395,3290,2320),
    )

    /** 인접한 두 역 + 노선도 위 선로 길이(px). 서버 없이 경로를 계산할 때 쓴다 (FakeRouteRepository) */
    data class Link(val line: String, val a: String, val b: String, val px: Float)

    val links: List<Link> by lazy {
        tracks.map { (k, p) ->
            val (line, a, b) = k.split("|")
            var len = 0f
            for (i in 0 until p.size / 2 - 1) {
                val dx = (p[i * 2 + 2] - p[i * 2]).toFloat(); val dy = (p[i * 2 + 3] - p[i * 2 + 1]).toFloat()
                len += kotlin.math.sqrt(dx * dx + dy * dy)
            }
            Link(line, a, b, len)
        }
    }

    /** a → b 사이 선로 좌표 (양 끝 제외). 인접 역이 아니면 null → 직선으로 그림 */
    fun between(line: String, a: String, b: String): FloatArray? {
        tracks["$line|$a|$b"]?.let { return inner(it, reverse = false) }
        tracks["$line|$b|$a"]?.let { return inner(it, reverse = true) }
        return null
    }

    private fun inner(p: IntArray, reverse: Boolean): FloatArray {
        val n = p.size / 2
        if (n <= 2) return FloatArray(0)
        val out = FloatArray((n - 2) * 2)
        for (k in 1 until n - 1) {
            val i = if (reverse) n - 1 - k else k
            out[(k - 1) * 2] = p[i * 2].toFloat()
            out[(k - 1) * 2 + 1] = p[i * 2 + 1].toFloat()
        }
        return out
    }
}
