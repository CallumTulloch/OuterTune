package com.zionhuang.innertube.models

/**
 * Candidate names supplied by Main YouTube for an already verified art track.
 * The channel author is not a performer credit, and these values do not establish an original language.
 */
data class ArtTrackOriginalMetadata(
    val videoId: String,
    val title: String,
    val author: String?,
    val channelId: String?,
    val shortDescription: String?,
)
