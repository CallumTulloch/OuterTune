package com.dd3boh.outertune.ui.utils

private val youtubeVideoThumbnailRegex = Regex(
    "^(https://(?:i\\.ytimg\\.com|img\\.youtube\\.com)/(vi(?:_webp)?)/[^/]+/)([^?]+)(\\?.*)?$"
)

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this

    youtubeVideoThumbnailRegex.matchEntire(this)?.let { match ->
        // Video thumbnails are commonly stored as sddefault (640x480), which is visibly
        // blurred when enlarged on the player screen. YouTube exposes the original 720p
        // thumbnail under maxresdefault for videos that have one.
        if (maxOf(width ?: 0, height ?: 0) > 480) {
            val extension = if (match.groupValues[2] == "vi_webp") "webp" else "jpg"
            return "${match.groupValues[1]}maxresdefault.$extension${match.groupValues[4]}"
        }
    }

    "https://(?:lh3|yt3)\\.(?:googleusercontent\\.com|ggpht\\.com)/.*=w(\\d+)-h(\\d+).*".toRegex().matchEntire(this)?.groupValues?.let { group ->
        val (W, H) = group.drop(1).map { it.toInt() }
        var w = width
        var h = height
        if (w != null && h == null) h = (w / W) * H
        if (w == null && h != null) w = (h / H) * W
        return "${split("=w")[0]}=w$w-h$h-p-l90-rj"
    }
    if (this matches "https://yt3\\.ggpht\\.com/.*=s(\\d+)".toRegex()) {
        return "$this-s${width ?: height}"
    }
    return this
}
