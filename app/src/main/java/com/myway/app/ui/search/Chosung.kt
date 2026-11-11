package com.myway.app.ui.search

/** 초성 검색: "ㄱㄴ" → 강남 */
object Chosung {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    fun isChosungOnly(text: String): Boolean =
        text.isNotEmpty() && text.all { it in CHO }

    private fun extract(text: String): String = buildString {
        text.forEach { c -> append(if (c in '가'..'힣') CHO[(c - '가') / 588] else c) }
    }

    fun matches(name: String, query: String): Boolean = extract(name).contains(query)
}
