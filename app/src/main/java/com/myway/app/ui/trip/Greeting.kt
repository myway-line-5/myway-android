package com.myway.app.ui.trip

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.myway.app.R
import java.time.LocalTime

/**
 * 도착 인사: 시간대에 맞춘 한마디 (화면 카드 · 도착 알림 · 도착 음성에 같이 씀).
 * 날씨를 붙이려면 서버에 날씨 API(기상청 단기예보)를 연결해 sub 대신 "맑음 · 12°" 같은 줄을 넣으면 된다.
 */
data class Greeting(@StringRes val title: Int, @StringRes val sub: Int, @DrawableRes val icon: Int) {
    companion object {
        fun at(t: LocalTime = LocalTime.now()): Greeting = when (t.hour) {
            in 5..10 -> Greeting(R.string.greet_morning, R.string.greet_morning_sub, R.drawable.ic_greet_day)
            in 11..13 -> Greeting(R.string.greet_lunch, R.string.greet_lunch_sub, R.drawable.ic_greet_day)
            in 14..17 -> Greeting(R.string.greet_afternoon, R.string.greet_afternoon_sub, R.drawable.ic_greet_day)
            in 18..21 -> Greeting(R.string.greet_evening, R.string.greet_evening_sub, R.drawable.ic_greet_night)
            else -> Greeting(R.string.greet_night, R.string.greet_night_sub, R.drawable.ic_greet_night)
        }
    }
}
