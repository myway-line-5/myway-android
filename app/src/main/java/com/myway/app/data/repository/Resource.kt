package com.myway.app.data.repository

import com.myway.app.domain.model.AppError

sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Failure(val error: AppError) : Resource<Nothing>()
}