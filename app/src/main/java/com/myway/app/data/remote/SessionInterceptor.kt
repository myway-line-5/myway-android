package com.myway.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response

class SessionInterceptor(private val sessionId: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Session-ID", sessionId)
            .build()
        return chain.proceed(request)
    }
}