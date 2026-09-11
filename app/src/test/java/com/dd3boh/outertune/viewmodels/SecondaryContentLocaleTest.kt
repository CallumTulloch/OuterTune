package com.dd3boh.outertune.viewmodels

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.pages.MoodAndGenres
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class SecondaryContentLocaleTest {
    @Test fun `account refresh clears all three categories and rejects late old language pages`() = fixture { f ->
        val pending = mutableListOf<LibraryRequest>()
        val vm = AccountViewModel(AccountViewModel.Runtime(scope = f.scope, locales = f.locales,
            library = { id, locale -> LibraryRequest(id, locale).also(pending::add).result.await() },
            onFailure = {},
        ))
        f.run()
        assertEquals(3, pending.size)
        val old = pending.toList()
        old.first().result.complete(Result.success(LibraryPage(listOf(playlist("Old")), null)))
        f.run()
        assertEquals("Old", vm.playlists.value!!.single().title)

        f.locales.value = japanese
        old.drop(1).forEach { it.result.complete(Result.success(LibraryPage(listOf(album("Old"), artist("Old")), null))) }
        f.run()
        assertNull(vm.playlists.value)
        assertNull(vm.albums.value)
        assertNull(vm.artists.value)
        assertEquals(0, vm.isLoading.value)
        assertEquals(3, pending.count { it.locale == japanese })
        pending.takeLast(3).forEach { request ->
            val item: YTItem = when (request.id) {
                "FEmusic_liked_playlists" -> playlist("日本語")
                "FEmusic_liked_albums" -> album("日本語")
                else -> artist("日本語")
            }
            request.result.complete(Result.success(LibraryPage(listOf(item), null)))
        }
        f.run()
        assertEquals("日本語", vm.playlists.value!!.single().title)
        assertEquals("日本語", vm.albums.value!!.single().title)
        assertEquals("日本語", vm.artists.value!!.single().title)
        assertEquals(3, vm.isLoading.value)
    }

    @Test fun `account category failure ends its loading while other new locale categories succeed`() = fixture { f ->
        val vm = AccountViewModel(AccountViewModel.Runtime(scope = f.scope, locales = f.locales,
            library = { id, _ ->
                if (id == "FEmusic_liked_playlists") Result.failure(IllegalStateException("offline"))
                else Result.success(LibraryPage(listOf(album("Album"), artist("Artist")), null))
            }, onFailure = {},
        ))
        f.run()
        assertNull(vm.playlists.value)
        assertNotNull(vm.albums.value)
        assertNotNull(vm.artists.value)
        assertEquals(3, vm.isLoading.value)
    }

    @Test fun `mood headings follow shared locale and never retain old language after new failure`() = fixture { f ->
        val calls = mutableListOf<Pair<YouTubeLocale, CompletableDeferred<Result<List<MoodAndGenres>>>>>()
        val vm = MoodAndGenresViewModel(MoodAndGenresViewModel.Runtime(scope = f.scope, locales = f.locales,
            fetch = { locale -> CompletableDeferred<Result<List<MoodAndGenres>>>().also { calls += locale to it }.await() },
            onFailure = {},
        ))
        f.run()
        calls.single().second.complete(Result.success(listOf(MoodAndGenres("Old heading", emptyList()))))
        f.run()
        assertEquals("Old heading", vm.moodAndGenres.value!!.single().title)
        f.locales.value = japanese
        f.run()
        assertNull(vm.moodAndGenres.value)
        assertEquals(listOf(english, japanese), calls.map { it.first })
        calls.last().second.complete(Result.failure(IllegalStateException("offline")))
        f.run()
        assertNull(vm.moodAndGenres.value)
    }

    private data class LibraryRequest(val id: String, val locale: YouTubeLocale,
        val result: CompletableDeferred<Result<LibraryPage>> = CompletableDeferred())

    private fun fixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        try { test(f) } finally { f.scope.cancel(); f.run() }
    }
    private class Fixture {
        private val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val locales = MutableStateFlow(english)
        fun run() = dispatcher.runCurrent()
    }
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    companion object {
        private val english = YouTubeLocale("JP", "en")
        private val japanese = YouTubeLocale("JP", "ja")
        private fun playlist(title: String) = PlaylistItem("playlist", title, null, null, null, null, null, null)
        private fun album(title: String) = AlbumItem("album", null, title = title, artists = null, thumbnail = "")
        private fun artist(title: String) = ArtistItem("artist", title, null, shuffleEndpoint = null, radioEndpoint = null)
    }
}
