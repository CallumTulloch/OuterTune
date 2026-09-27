package com.zionhuang.innertube.models

/**
 * Main's structured music card associates this source video with a song's target video.
 * This is a provider reference for song-title attribution, not proof of the same recording,
 * an original title/language, or equivalent playback, artist or album identities.
 */
data class MainSongReference(
    val sourceVideoId: String,
    val targetVideoId: String,
)
