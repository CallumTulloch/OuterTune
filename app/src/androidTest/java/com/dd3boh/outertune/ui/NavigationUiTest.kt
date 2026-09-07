package com.dd3boh.outertune.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.datastore.preferences.core.edit
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LibraryAlbumLikedOnlyKey
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.fixtures.SearchUiFixtureActivity
import com.dd3boh.outertune.playback.MediaControllerViewModel
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.ui.menu.MenuState
import com.dd3boh.outertune.ui.screens.library.FolderScreen
import com.dd3boh.outertune.ui.screens.library.LibraryLikedFilterMenu
import com.dd3boh.outertune.ui.utils.encodeFolderPathArgument
import com.dd3boh.outertune.viewmodels.LibraryFoldersViewModel
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.rememberPreference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class NavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var currentActivity: SearchUiFixtureActivity

    @Test(timeout = 90_000)
    fun folderSearchPlaysNestedResultHandlesBothEnterKeysAndKeepsHeaderStable() = withFixture { activity ->
        val connection = AtomicReference<PlayerConnection>()
        onMain {
            ViewModelProvider(activity)[MediaControllerViewModel::class.java].also { model ->
                model.addControllerCallback(activity.lifecycle) { _, _ ->
                    connection.set(PlayerConnection(model, model.getService()!!.database))
                }
                activity.lifecycle.addObserver(model)
            }
        }
        awaitCondition { connection.get() != null }
        val player = connection.get()
        awaitCondition { player.service.qbInit.value }
        val root = File(activity.getExternalFilesDir(null), "navigation-test-${UUID.randomUUID()}")
        val child = File(root, "child").apply { mkdirs() }
        val songs = listOf("Alpha", "Zulu").map { title ->
            val file = File(child, "$title.wav")
            writeSilence(file)
            SongEntity("nav-$title-${UUID.randomUUID()}", "Navigation $title", duration = 40,
                inLibrary = LocalDateTime.now(), isLocal = true, localPath = file.absolutePath)
        }
        runBlocking(Dispatchers.IO) { songs.forEach { player.database.insert(it) } }
        val vm = onMain { LibraryFoldersViewModel(activity, player.database,
            SavedStateHandle(mapOf("path" to encodeFolderPathArgument(root.absolutePath)))) }
        try {
            onMain {
                activity.fixtureView.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(
                            LocalDatabase provides player.database,
                            LocalPlayerConnection provides player,
                            LocalMenuState provides MenuState(rememberModalBottomSheetState()),
                            LocalPlayerAwareWindowInsets provides WindowInsets.safeDrawing,
                            LocalSnackbarHostState provides remember { SnackbarHostState() },
                        ) {
                            FolderScreen(rememberNavController(), TopAppBarDefaults.pinnedScrollBehavior(), vm, isRoot = true)
                        }
                    }
                }
            }
            awaitCondition { !vm.localSongDirectoryTree.value.isSkeleton && onMain { tagged("folder-search-bar") != null } }
            assertTrue("Repro requires no songs directly in the selected folder", vm.localSongDirectoryTree.value.files.isEmpty())
            val before = onMain { tagged("folder-search-bar")!!.boundsInWindow }
            clickTag("folder-search-toggle")
            awaitCondition { onMain { field()?.config?.getOrNull(SemanticsProperties.Focused) == true } }
            type("Navigation")
            onMain { assertTrue(field()!!.config[SemanticsActions.OnImeAction].action!!.invoke()) }
            awaitCondition { onMain { field()?.config?.getOrNull(SemanticsProperties.Focused) == false && tagged("folder-song-${songs[1].id}") != null } }
            assertBounds(before, onMain { tagged("folder-search-bar")!!.boundsInWindow })
            screenshot("folder-search-results")
            clickTag("folder-song-${songs[1].id}")
            awaitCondition { onMain { player.player.currentMediaItem?.mediaId == songs[1].id && player.player.isPlaying } }
            awaitCondition { onMain { player.player.currentPosition > 200 } }
            screenshot("folder-search-playing")
            val playingFirst = thumbnail(songs[1].id)
            SystemClock.sleep(180)
            val playingSecond = thumbnail(songs[1].id)
            assertFalse("The active song thumbnail must animate", playingFirst.sameAs(playingSecond))
            clickTag("folder-song-${songs[1].id}")
            awaitCondition { onMain { !player.player.playWhenReady && !player.isPlaying.value } }
            // Allow the tap ripple and the player-state composition to finish before comparing pixels.
            val pauseDeadline = SystemClock.uptimeMillis() + 4_000
            var pausedFirst = thumbnail(songs[1].id)
            while (true) {
                SystemClock.sleep(180)
                val next = thumbnail(songs[1].id)
                if (pausedFirst.sameAs(next)) break
                assertTrue("Paused indicator must settle", SystemClock.uptimeMillis() < pauseDeadline)
                pausedFirst = next
            }
            screenshot("folder-search-paused")

            onMain { field()!!.config[SemanticsActions.RequestFocus].action!!.invoke() }
            awaitCondition { onMain { field()?.config?.getOrNull(SemanticsProperties.Focused) == true } }
            type("Alpha")
            onMain {
                activity.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                activity.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            awaitCondition { onMain { field()?.config?.getOrNull(SemanticsProperties.Focused) == false } }
            awaitCondition { vm.searchResult.value.query == "Alpha" && vm.searchResult.value.songs.size == 1 }
            assertBounds(before, onMain { tagged("folder-search-bar")!!.boundsInWindow })
            clickTag("folder-search-clear")
            awaitCondition { vm.searchResult.value.query.isEmpty() && vm.searchResult.value.songs.isEmpty() }
            clickTag("folder-search-toggle")
            awaitCondition { onMain { field() == null } }
            assertBounds(before, onMain { tagged("folder-search-bar")!!.boundsInWindow })
        } finally {
            onMain { player.player.stop(); player.player.clearMediaItems(); activity.fixtureView.disposeComposition() }
            runBlocking(Dispatchers.IO) { songs.forEach { player.database.delete(it) } }
            root.deleteRecursively()
        }
    }

    @Test(timeout = 45_000)
    fun nestedLikedMenuKeepsExpansionAlignsItsIconAndTogglesCheckedState() = withFixture { activity ->
        val saved = runBlocking { activity.dataStore.data.first()[LibraryAlbumLikedOnlyKey] }
        runBlocking { activity.dataStore.edit { it[LibraryAlbumLikedOnlyKey] = false } }
        fun render() = onMain { activity.fixtureView.setContent {
            MaterialTheme {
                var liked by rememberPreference(LibraryAlbumLikedOnlyKey, false)
                Column(Modifier.statusBarsPadding()) {
                    LibraryLikedFilterMenu(liked) { liked = it }
                    Text(if (liked) "liked-on" else "liked-off")
                }
            }
        } }
        render()
        val label = activity.getString(R.string.library_filter)
        val likedLabel = activity.getString(R.string.filter_liked_only)
        try { repeat(2) { iteration ->
            clickTag("library-liked-menu")
            awaitCondition { onMain { textNode(label) != null } }
            onMain {
                val icon = tagged("dropdown-expand-icon")!!.boundsInWindow
                val text = textNode(label)!!.boundsInWindow
                assertEquals("Expand icon and label centers", icon.center.y, text.center.y, 1f)
            }
            clickText(label)
            awaitCondition { onMain { textNode(likedLabel) != null } }
            onMain {
                val checked = nodes().single { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
                assertEquals(if (iteration == 0) ToggleableState.Off else ToggleableState.On,
                    checked.config[SemanticsProperties.ToggleableState])
                assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text == activity.getString(R.string.filter_downloaded) || text.text == activity.getString(R.string.folders)
                } == true })
            }
            screenshot("library-liked-menu-$iteration")
            clickText(likedLabel)
            awaitCondition { onMain { textNode(if (iteration == 0) "liked-on" else "liked-off") != null && textNode(label) == null } }
            assertEquals(iteration == 0, runBlocking { activity.dataStore.data.first()[LibraryAlbumLikedOnlyKey] })
            if (iteration == 0) {
                onMain { activity.fixtureView.disposeComposition() }
                render()
            }
        } } finally {
            runBlocking { activity.dataStore.edit {
                if (saved == null) it.remove(LibraryAlbumLikedOnlyKey) else it[LibraryAlbumLikedOnlyKey] = saved
            } }
        }
    }

    private fun writeSilence(file: File) {
        val length = 8_000 * 2 * 40
        val bytes = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + length).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(length)
        file.writeBytes(bytes.array())
    }

    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.height, actual.height, 1f)
        assertEquals(expected.width, actual.width, 1f)
    }

    private fun thumbnail(id: String): Bitmap {
        val bounds = onMain { tagged("folder-song-$id")!!.boundsInWindow }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val screen = instrumentation.uiAutomation.takeScreenshot()
        val size = (40 * density).toInt()
        return Bitmap.createBitmap(screen, (bounds.left + 16 * density).toInt(),
            (bounds.center.y - size / 2).toInt(), size, size)
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(300)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "navigation-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun withFixture(block: (SearchUiFixtureActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SearchUiFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SearchUiFixtureActivity
        currentActivity = activity
        try { block(activity) } finally { onMain { activity.fixtureView.disposeComposition(); activity.finish() } }
    }

    private fun nodes(): List<SemanticsNode> {
        fun walk(view: View): List<SemanticsNode> {
            if (view is ViewRootForTest) return view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            return if (view is ViewGroup) (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
        }
        return WindowInspector.getGlobalWindowViews().flatMap(::walk)
    }

    private fun tagged(tag: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun textNode(text: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
    private fun field() = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null }

    private fun clickNode(find: () -> SemanticsNode?) {
        awaitCondition { onMain { find() != null } }
        onMain {
            val node = find()!!
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }
    private fun clickTag(tag: String) {
        awaitCondition { onMain { tagged(tag) != null && currentActivity.hasWindowFocus() } }
        val point = onMain { tagged(tag)!!.boundsInWindow.center }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            try { onMain { currentActivity.window.decorView.dispatchTouchEvent(event) } }
            finally { event.recycle() }
            SystemClock.sleep(64)
        }
    }
    private fun clickText(text: String) = clickNode {
        var node = textNode(text)
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        node
    }
    private fun type(text: String) {
        onMain { assertTrue(field()!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))) }
        awaitCondition { onMain { field()?.config?.getOrNull(SemanticsProperties.EditableText)?.text == text } }
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Navigation UI did not reach the expected state" }
            SystemClock.sleep(16)
        }
    }
    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(block)) }
        return value.get().getOrThrow()
    }
}
