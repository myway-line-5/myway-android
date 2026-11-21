package com.myway.app.domain.model

data class RecentSearch(
    val searchId: Long,
    val originId: String,
    val destinationId: String,
    val originName: String,
    val destinationName: String,
    val searchedAt: String
)