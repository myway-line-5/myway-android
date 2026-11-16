package com.myway.app.data.remote

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // adb reverse tcp:8000 tcp:8000 으로 연결한 로컬 서버 (폰·에뮬레이터 공통)
    private const val BASE_URL = "http://127.0.0.1:8000/"

    // 앱을 켠 동안 같은 값을 계속 사용. (나중에 SharedPreferences 저장 고려)
    private val sessionId: String = UUID.randomUUID().toString()

    private val okHttp = OkHttpClient.Builder()
        .addInterceptor(SessionInterceptor(sessionId))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // AI 해석은 서버 타임아웃이 25초라 여유 있게
        .build()

    val api: MyWayApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MyWayApi::class.java)
}