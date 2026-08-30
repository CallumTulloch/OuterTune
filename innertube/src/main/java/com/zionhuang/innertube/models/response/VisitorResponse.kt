package com.zionhuang.innertube.models.response

import com.zionhuang.innertube.models.ResponseContext
import kotlinx.serialization.Serializable

@Serializable
data class VisitorResponse(
    val responseContext: ResponseContext,
)
