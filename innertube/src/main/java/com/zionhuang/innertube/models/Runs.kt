package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Runs(
    val runs: List<Run>?,
)

@Serializable
data class Run(
    val text: String,
    val navigationEndpoint: NavigationEndpoint?,
)

fun List<Run>.splitBySeparator(): List<List<Run>> {
    val res = mutableListOf<List<Run>>()
    var tmp = mutableListOf<Run>()
    forEach { run ->
        if (run.text == " • ") {
            res.add(tmp)
            tmp = mutableListOf()
        } else {
            tmp.add(run)
        }
    }
    res.add(tmp)
    return res
}

fun List<List<Run>>.clean(): List<List<Run>> {
    val first = firstOrNull()?.singleOrNull()
    // Missing links are not evidence of a category label: unlinked names must survive.
    val labels = setOf("曲", "動画", "Song", "Video", "歌曲", "影片", "歌曲视频")
    return if (first?.navigationEndpoint == null && first?.text in labels) drop(1) else this
}

fun List<Run>.oddElements() = filterIndexed { index, _ ->
    index % 2 == 0
}

private val durationMetadataRegex = Regex("""^\d+:[0-5]\d(?::[0-5]\d)?$""")

/**
 * Extract artist runs from bullet-separated metadata while excluding a trailing duration.
 * Duration runs do not have a navigation endpoint; keeping endpoint-backed values also avoids
 * rejecting an unusually named artist such as "4:44".
 */
fun List<Run>.artistElements() = oddElements().filterNot { run ->
    run.navigationEndpoint == null && durationMetadataRegex.matches(run.text.trim())
}
