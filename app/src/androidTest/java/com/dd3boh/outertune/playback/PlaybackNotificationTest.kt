package com.dd3boh.outertune.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.MediaSessionConstants.ACTION_TOGGLE_LIKE
import com.dd3boh.outertune.constants.MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE
import com.dd3boh.outertune.constants.MediaSessionConstants.ACTION_TOGGLE_SHUFFLE
import com.dd3boh.outertune.constants.MediaSessionConstants.ACTION_TOGGLE_START_RADIO
import org.junit.Assert.*
import org.junit.Test

/** Runs on Android because real CommandButton equality and PendingIntent cancellation matter here. */
class PlaybackNotificationTest {
    @Test
    fun repeatedMetadataEmissionsPublishOnceButEveryVisibleControlChangeIsPublished() {
        val published = mutableListOf<List<CommandButton>>()
        val updater = NotificationLayoutUpdater { published += it }
        repeat(2_000) { updater.update(buttons()) }
        assertEquals(1, published.size)

        // Track identity/name changes leave controls alone; these are the actual visible changes.
        val original = buttons()
        val changes = listOf(
            original.mapIndexed { i, b -> if (i == 0) b.copy(displayName = "Shuffle off") else b },
            original.mapIndexed { i, b -> if (i == 0) b.copy(icon = android.R.drawable.ic_media_pause) else b },
            original.mapIndexed { i, b -> if (i == 1) b.copy(displayName = "Repeat one") else b },
            original.mapIndexed { i, b -> if (i == 2) b.copy(displayName = "Unlike") else b },
            original.mapIndexed { i, b -> if (i == 2) b.copy(enabled = false) else b },
            original.mapIndexed { i, b -> if (i == 3) b.copy(enabled = false) else b },
        )
        changes.forEach { layout ->
            val before = published.size
            updater.update(layout)
            updater.update(layout)
            assertEquals(before + 1, published.size)
            assertEquals(layout, published.last())
        }
    }

    @Test
    fun commandAndButtonExtrasAreNeverHiddenByMedia3Equality() {
        val published = mutableListOf<List<CommandButton>>()
        val updater = NotificationLayoutUpdater { published += it }
        updater.update(listOf(button(ACTION_TOGGLE_LIKE)))
        updater.update(listOf(button(ACTION_TOGGLE_LIKE, extras = Bundle().apply { putString("id", "one") })))
        updater.update(listOf(button(ACTION_TOGGLE_LIKE, extras = Bundle().apply { putString("id", "two") })))
        updater.update(listOf(button(ACTION_TOGGLE_LIKE).copy(buttonExtras = Bundle().apply {
            putInt("compact", 1)
        })))
        updater.update(listOf(button(ACTION_TOGGLE_LIKE)))
        updater.update(listOf(button(ACTION_TOGGLE_LIKE)))
        assertEquals(5, published.size)
    }

    @Test
    fun thousandsOfRebuildsKeepFourIntentsAndRefreshTitlesAndIcons() = withSessions { f ->
        val actions = PlaybackNotificationActions(f.context)
        try {
            val initial = buttons().map {
                actions.wrap(f.first, f.factory).createCustomActionFromCustomCommandButton(f.first, it)
            }
            repeat(2_000) {
                buttons().forEachIndexed { index, button ->
                    val action = actions.wrap(f.first, f.factory)
                        .createCustomActionFromCustomCommandButton(f.first, button)
                    assertEquals(initial[index].actionIntent, action.actionIntent)
                }
            }
            assertEquals(4, f.factory.customCalls)
            assertEquals(4, initial.map { it.actionIntent }.toSet().size)

            val changed = actions.wrap(f.first, f.factory).createCustomActionFromCustomCommandButton(
                f.first, button(ACTION_TOGGLE_LIKE, title = "Unlike", icon = android.R.drawable.ic_media_pause),
            )
            assertEquals(initial[2].actionIntent, changed.actionIntent)
            assertEquals("Unlike", changed.title)
            assertEquals(android.R.drawable.ic_media_pause, changed.iconCompat!!.resId)
            assertEquals(4, f.factory.customCalls)
        } finally {
            actions.release()
        }
    }

    @Test
    fun payloadsAndUnknownCommandsDelegateWithoutReusingOrOverwritingEmptyCommand() = withSessions { f ->
        val actions = PlaybackNotificationActions(f.context)
        try {
            val factory = actions.wrap(f.first, f.factory)
            val empty = factory.createCustomActionFromCustomCommandButton(f.first, button(ACTION_TOGGLE_LIKE))
            val one = Bundle().apply { putString("id", "one") }
            val two = Bundle().apply { putString("id", "two") }
            val first = factory.createCustomActionFromCustomCommandButton(f.first, button(ACTION_TOGGLE_LIKE, extras = one))
            val second = factory.createCustomActionFromCustomCommandButton(f.first, button(ACTION_TOGGLE_LIKE, extras = two))
            assertNotEquals(first.actionIntent, second.actionIntent)
            assertNotEquals(empty.actionIntent, first.actionIntent)
            assertEquals("one", f.factory.payloads[1].getString("id"))
            assertEquals("two", f.factory.payloads[2].getString("id"))
            val again = factory.createCustomActionFromCustomCommandButton(f.first, button(ACTION_TOGGLE_LIKE))
            assertEquals(empty.actionIntent, again.actionIntent)

            val unknown1 = factory.createCustomActionFromCustomCommandButton(f.first, button("UNKNOWN"))
            val unknown2 = factory.createCustomActionFromCustomCommandButton(f.first, button("UNKNOWN"))
            assertNotEquals(unknown1.actionIntent, unknown2.actionIntent)
            assertEquals(5, f.factory.customCalls)
        } finally {
            actions.release()
        }
    }

    @Test
    fun sessionReplacementAndReleaseCancelOldTokensAndCannotReuseThem() = withSessions { f ->
        val actions = PlaybackNotificationActions(f.context)
        val old = actions.wrap(f.first, f.factory)
            .createCustomActionFromCustomCommandButton(f.first, button(ACTION_TOGGLE_LIKE)).actionIntent!!
        val current = actions.wrap(f.second, f.factory)
            .createCustomActionFromCustomCommandButton(f.second, button(ACTION_TOGGLE_LIKE)).actionIntent!!
        assertNotEquals(old, current)
        assertCancelled(old)
        actions.release()
        assertCancelled(current)
        actions.release() // idempotent, including destruction before any notification was shown.
        val renewed = actions.wrap(f.second, f.factory)
            .createCustomActionFromCustomCommandButton(f.second, button(ACTION_TOGGLE_LIKE)).actionIntent!!
        assertNotEquals(current, renewed)
        actions.release()
    }

    @Test
    fun standardAndDismissalActionsUseTheOriginalFactory() = withSessions { f ->
        val actions = PlaybackNotificationActions(f.context)
        try {
            val factory = actions.wrap(f.first, f.factory)
            val icon = IconCompat.createWithResource(f.context, android.R.drawable.ic_media_play)
            factory.createMediaAction(f.first, icon, "Play", Player.COMMAND_PLAY_PAUSE)
            factory.createMediaActionPendingIntent(f.first, Player.COMMAND_STOP.toLong())
            factory.createNotificationDismissalIntent(f.first)
            assertEquals(1, f.factory.mediaCalls)
            assertEquals(2, f.factory.mediaIntentCalls)
            assertEquals(1, f.factory.dismissalCalls)
            assertEquals(0, f.factory.customCalls)
        } finally {
            actions.release()
        }
    }

    private fun buttons() = listOf(
        button(ACTION_TOGGLE_SHUFFLE), button(ACTION_TOGGLE_REPEAT_MODE),
        button(ACTION_TOGGLE_LIKE), button(ACTION_TOGGLE_START_RADIO),
    )

    private fun button(
        action: String,
        title: String = action,
        icon: Int = android.R.drawable.ic_media_play,
        extras: Bundle = Bundle.EMPTY,
    ) = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setSessionCommand(SessionCommand(action, extras))
        .setDisplayName(title)
        .setCustomIconResId(icon)
        .build()

    private fun CommandButton.copy(
        displayName: String = this.displayName.toString(),
        icon: Int = iconResId,
        enabled: Boolean = isEnabled,
        buttonExtras: Bundle = extras,
    ): CommandButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setSessionCommand(sessionCommand!!)
        .setDisplayName(displayName)
        .setCustomIconResId(icon)
        .setEnabled(enabled)
        .setExtras(buttonExtras)
        .build()

    private fun assertCancelled(intent: PendingIntent) {
        try {
            intent.send()
            fail("Released notification action should be cancelled")
        } catch (_: PendingIntent.CanceledException) {
            // Expected: stale notification controls cannot target the released session.
        }
    }

    private fun withSessions(test: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val firstPlayer = ExoPlayer.Builder(context).build()
            val secondPlayer = ExoPlayer.Builder(context).build()
            val first = MediaSession.Builder(context, firstPlayer).setId("notification-test-one").build()
            val second = MediaSession.Builder(context, secondPlayer).setId("notification-test-two").build()
            val factory = RecordingFactory(context)
            try {
                test(Fixture(context, first, second, factory))
            } catch (error: Throwable) {
                failure = error
            } finally {
                factory.created.forEach(PendingIntent::cancel)
                first.release()
                second.release()
                firstPlayer.release()
                secondPlayer.release()
            }
        }
        failure?.let { throw it }
    }

    private data class Fixture(
        val context: Context,
        val first: MediaSession,
        val second: MediaSession,
        val factory: RecordingFactory,
    )

    private class RecordingFactory(private val context: Context) : MediaNotification.ActionFactory {
        var customCalls = 0
        var mediaCalls = 0
        var mediaIntentCalls = 0
        var dismissalCalls = 0
        val created = mutableListOf<PendingIntent>()
        val payloads = mutableListOf<Bundle>()

        override fun createCustomAction(
            mediaSession: MediaSession, icon: IconCompat, title: CharSequence, customAction: String, extras: Bundle,
        ): NotificationCompat.Action {
            customCalls++
            payloads += extras
            return NotificationCompat.Action(icon, title, intent().also { created += it })
        }

        override fun createCustomActionFromCustomCommandButton(
            mediaSession: MediaSession, customCommandButton: CommandButton,
        ): NotificationCompat.Action {
            val command = customCommandButton.sessionCommand!!
            return createCustomAction(mediaSession, IconCompat.createWithResource(context, customCommandButton.iconResId),
                customCommandButton.displayName, command.customAction, command.customExtras)
        }

        override fun createMediaAction(
            mediaSession: MediaSession, icon: IconCompat, title: CharSequence, command: Int,
        ): NotificationCompat.Action {
            mediaCalls++
            return NotificationCompat.Action(icon, title, createMediaActionPendingIntent(mediaSession, command.toLong()))
        }

        override fun createMediaActionPendingIntent(mediaSession: MediaSession, command: Long): PendingIntent {
            mediaIntentCalls++
            return intent().also { created += it }
        }

        override fun createNotificationDismissalIntent(mediaSession: MediaSession): PendingIntent {
            dismissalCalls++
            return intent().also { created += it }
        }

        private fun intent(): PendingIntent = PendingIntent.getBroadcast(
            context, created.size,
            Intent("com.dd3boh.outertune.NOTIFICATION_TEST").setPackage(context.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
