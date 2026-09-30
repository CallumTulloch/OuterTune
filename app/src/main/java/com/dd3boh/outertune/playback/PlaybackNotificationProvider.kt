package com.dd3boh.outertune.playback

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.dd3boh.outertune.constants.MediaSessionConstants.ACTION_TOGGLE_LIKE
import com.dd3boh.outertune.constants.MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE
import com.dd3boh.outertune.constants.MediaSessionConstants.ACTION_TOGGLE_SHUFFLE
import com.dd3boh.outertune.constants.MediaSessionConstants.ACTION_TOGGLE_START_RADIO
import com.google.common.collect.ImmutableList

internal class PlaybackNotificationProvider(
    context: Context,
    notificationId: Int,
    channelId: String,
    channelName: Int,
) : DefaultMediaNotificationProvider(context, { notificationId }, channelId, channelName) {
    private val actions = PlaybackNotificationActions(context)

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray = super.addNotificationActions(
        mediaSession, mediaButtons, builder, actions.wrap(mediaSession, actionFactory),
    )

    fun release() = actions.release()
}

/** Reuses only the four payload-free app commands; Media3 still creates their actual intents. */
internal class PlaybackNotificationActions(private val context: Context) {
    private var session: MediaSession? = null
    private val intents = mutableMapOf<String, PendingIntent>()

    fun wrap(
        mediaSession: MediaSession,
        delegate: MediaNotification.ActionFactory,
    ): MediaNotification.ActionFactory {
        if (session !== mediaSession) {
            release()
            session = mediaSession
        }
        return object : MediaNotification.ActionFactory by delegate {
            // Kotlin's delegation inherits this Java default method instead of forwarding a
            // factory's override, which may attach notification-dismissal-specific intent extras.
            override fun createNotificationDismissalIntent(mediaSession: MediaSession): PendingIntent =
                delegate.createNotificationDismissalIntent(mediaSession)

            override fun createCustomAction(
                mediaSession: MediaSession,
                icon: IconCompat,
                title: CharSequence,
                customAction: String,
                extras: Bundle,
            ): NotificationCompat.Action = reuseAction(
                mediaSession, customAction, extras, icon, title,
            ) { delegate.createCustomAction(mediaSession, icon, title, customAction, extras) }

            override fun createCustomActionFromCustomCommandButton(
                mediaSession: MediaSession,
                customCommandButton: CommandButton,
            ): NotificationCompat.Action {
                val command = customCommandButton.sessionCommand
                if (command == null || command.commandCode != SessionCommand.COMMAND_CODE_CUSTOM) {
                    return delegate.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton)
                }
                return reuseAction(
                    mediaSession,
                    command.customAction,
                    command.customExtras,
                    IconCompat.createWithResource(context, customCommandButton.iconResId),
                    customCommandButton.displayName,
                ) { delegate.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton) }
            }
        }
    }

    private fun reuseAction(
        mediaSession: MediaSession,
        action: String,
        extras: Bundle,
        icon: IconCompat,
        title: CharSequence,
        create: () -> NotificationCompat.Action,
    ): NotificationCompat.Action {
        // Never reuse a token across sessions or coalesce distinct command payloads. This allowlist
        // bounds retained tokens to four even if another provider begins sending arbitrary actions.
        if (session !== mediaSession || action !in reusableActions || !extras.isEmpty) return create()
        val intent = intents[action] ?: return create().also { created ->
            created.actionIntent?.let { intents[action] = it }
        }
        return NotificationCompat.Action(icon, title, intent)
    }

    fun release() {
        intents.values.forEach(PendingIntent::cancel)
        intents.clear()
        session = null
    }

    private companion object {
        val reusableActions = setOf(
            ACTION_TOGGLE_SHUFFLE, ACTION_TOGGLE_REPEAT_MODE, ACTION_TOGGLE_LIKE, ACTION_TOGGLE_START_RADIO,
        )
    }
}
