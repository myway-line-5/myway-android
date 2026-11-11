package com.myway.app.data.remote

import com.myway.app.data.repository.Resource
import com.myway.app.domain.model.AppError
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

object ErrorMapper {

    // 네트워크 호출 중 발생한 예외 -> 앱 에러
    fun fromThrowable(t: Throwable): AppError = when (t) {
        is IOException -> AppError.Network      // 연결 끊김, 시간 초과 (SocketTimeout 포함)
        is HttpException -> AppError.Server      // 500, 422 등 서버가 준 오류 코드
        else -> AppError.Server                  // 응답 형식이 이상한 경우 등
    }
}

// 호출을 감싸서 Resource로 바꿔 주는 도우미
suspend fun <T> safeApiCall(block: suspend () -> T): Resource<T> =
    try {
        Resource.Success(block())
    } catch (e: CancellationException) {
        throw e   // 화면이 닫혀서 취소된 건 에러가 아니므로 그대로 던짐
    } catch (e: Exception) {
        Resource.Failure(ErrorMapper.fromThrowable(e))
    }