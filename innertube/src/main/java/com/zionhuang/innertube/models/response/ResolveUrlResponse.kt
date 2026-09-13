package com.zionhuang.innertube.models.response

import com.zionhuang.innertube.models.NavigationEndpoint
import kotlinx.serialization.Serializable

@Serializable
data class ResolveUrlResponse(val endpoint: NavigationEndpoint? = null)
