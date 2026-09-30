package com.dd3boh.outertune.playback

import androidx.media3.session.CommandButton

/** Publishes only changes to the controls, independently of song/metadata database emissions. */
internal class NotificationLayoutUpdater(
    private val publish: (List<CommandButton>) -> Unit,
) {
    private var previous: List<CommandButton>? = null

    fun update(layout: List<CommandButton>) {
        // CommandButton/SessionCommand equality deliberately ignores extras. Do not suppress an
        // update carrying a payload or presentation extras that equality cannot compare safely.
        val comparable = layout.all {
            it.extras.isEmpty && (it.sessionCommand?.customExtras?.isEmpty != false)
        }
        if (comparable && layout == previous) return
        publish(layout)
        previous = if (comparable) layout.toList() else null
    }
}
