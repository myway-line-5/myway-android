package com.myway.app.ui.common

import android.os.LocaleList
import android.widget.EditText
import java.util.Locale

/**
 * 입력칸 키보드를 한국어·영어 둘 다 쓸 수 있게.
 * 앱 언어를 English 로 바꾸면 키보드(Gboard 등)가 앱 언어를 따라 영어만 띄우는데,
 * 역 이름은 한국어로도 찾을 수 있어야 하므로 한국어를 먼저 알려 준다.
 * (한국어 키보드를 설치하지 않은 폰은 무시되어 영어 그대로 → 외국인 사용자 영향 없음)
 */
fun EditText.bilingualKeyboard() {
    imeHintLocales = LocaleList(Locale.KOREAN, Locale.ENGLISH)
}
