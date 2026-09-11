package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.AudioQuality
import com.zionhuang.innertube.models.response.PlayerResponse

internal fun selectPlaybackFormat(
    formats: List<PlayerResponse.StreamingData.Format>,
    audioQuality: AudioQuality,
    isMetered: Boolean,
    requiredItag: Int?,
): PlayerResponse.StreamingData.Format? {
    val audioFormats = formats.filter { it.isAudio }
    // Bytes already read/cached belong to one representation. A different itag at the same
    // byte offset would concatenate incompatible audio, so try another client if it is absent.
    if (requiredItag != null) return audioFormats.firstOrNull { it.itag == requiredItag }
    return audioFormats.maxByOrNull {
        it.bitrate * when (audioQuality) {
            AudioQuality.AUTO -> if (isMetered) -1 else 1
            AudioQuality.HIGH -> 1
            AudioQuality.LOW -> -1
        } + if (it.mimeType.startsWith("audio/webm")) 10240 else 0
    }
}
