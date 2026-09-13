package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.models.SimilarRecommendation
import com.dd3boh.outertune.utils.SyncUtils
import com.zionhuang.innertube.AuthenticationChangedException
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.ExplorePage
import com.zionhuang.innertube.pages.HomePage
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real home ViewModel and Room queries, with controlled remote replies and no account credentials. */
class HomeStartupTest {
    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(room)
        val locales = MutableStateFlow(YouTubeLocale("JP", "ja"))
        val authUpdates = MutableStateFlow(1L)
        lateinit var model: HomeViewModel
        private var closed = false

        suspend fun start(
            fetchHome: suspend (String?, String?, YouTubeLocale) -> Result<HomePage>,
            fetchExplore: suspend (YouTubeLocale) -> Result<ExplorePage> = { Result.success(explore("current")) },
            hasAccount: () -> Boolean = { false },
            fetchAccountPlaylists: suspend (YouTubeLocale) -> Result<List<PlaylistItem>> = {
                error("Anonymous home must not request account playlists")
            },
        ) = withContext(Dispatchers.Main) {
            model = HomeViewModel(database, SyncUtils(database, { true }, context), HomeViewModel.Runtime(
                locales = locales,
                authUpdates = authUpdates,
                fetchHome = fetchHome,
                fetchExplore = fetchExplore,
                hasAccount = hasAccount,
                fetchAccountPlaylists = fetchAccountPlaylists,
            ))
        }

        suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
        }

        suspend fun awaitCancelledRequests() {
            val canceled = withContext(Dispatchers.Main) {
                model.viewModelScope.coroutineContext[Job]!!.children.filter { it.isCancelled }.toList()
            }
            withTimeout(10_000) { canceled.joinAll() }
        }

        suspend fun close() {
            if (closed) return
            closed = true
            if (::model.isInitialized) {
                val job = withContext(Dispatchers.Main) {
                    model.viewModelScope.coroutineContext[Job].also { model.viewModelScope.cancel() }
                }
                withTimeout(10_000) { job?.join() }
            }
            room.close()
        }
    }

    @Test
    fun visitorPublicationDuringFirstHomeRequestAutomaticallyRecoversAndRejectsLateReplies() = runBlocking {
        // Exercise both the transport's stale-auth rejection and a callback ignoring cancellation.
        for (oldFails in listOf(true, false)) {
            val fixture = Fixture()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val firstReturned = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            try {
                fixture.start(fetchHome = { _, _, _ ->
                    if (calls.incrementAndGet() == 1) withContext(NonCancellable) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        firstReturned.complete(Unit)
                        if (oldFails) Result.failure(AuthenticationChangedException())
                        else Result.success(home("obsolete anonymous reply"))
                    } else Result.success(home("visitor ready"))
                })
                withTimeout(10_000) { firstStarted.await() }
                withContext(Dispatchers.Main) { fixture.authUpdates.value = 2L }
                fixture.await { fixture.model.homePage.value?.sections?.singleOrNull()?.title == "visitor ready" }
                releaseFirst.complete(Unit)
                withTimeout(10_000) { firstReturned.await() }
                fixture.awaitCancelledRequests()
                fixture.await { !fixture.model.isLoadingHome.value && !fixture.model.isRefreshing.value }
                withContext(Dispatchers.Main) {
                    assertEquals("visitor ready", fixture.model.homePage.value?.sections?.single()?.title)
                    assertFalse(fixture.model.homeLoadFailed.value)
                    assertFalse(fixture.model.loadFailed.value)
                    assertEquals(2, calls.get())
                }
            } finally { releaseFirst.complete(Unit); fixture.close() }
        }
    }

    @Test
    fun savedVisitorAndUnchangedRequestSettingsStartOnlyOneHomeRequest() = runBlocking {
        val fixture = Fixture()
        val calls = AtomicInteger()
        try {
            fixture.start(fetchHome = { _, _, locale ->
                assertEquals(YouTubeLocale("JP", "ja"), locale)
                calls.incrementAndGet()
                Result.success(home("saved visitor"))
            })
            fixture.await { fixture.model.homePage.value != null && !fixture.model.isRefreshing.value }
            withContext(Dispatchers.Main) {
                fixture.authUpdates.value = fixture.authUpdates.value
                fixture.locales.value = fixture.locales.value.copy()
            }
            withContext(Dispatchers.Main) {
                assertEquals(1, calls.get())
                assertFalse(fixture.model.homeLoadFailed.value)
            }
        } finally { fixture.close() }
    }

    @Test
    fun ordinaryNetworkFailureWaitsForExplicitRetryWithoutARequestLoop() = runBlocking {
        val fixture = Fixture()
        val calls = AtomicInteger()
        try {
            fixture.start(fetchHome = { _, _, _ ->
                if (calls.incrementAndGet() == 1) Result.failure(IOException("fixture offline"))
                else Result.success(home("manual retry"))
            })
            fixture.await { fixture.model.homeLoadFailed.value && !fixture.model.isLoadingHome.value }
            withContext(Dispatchers.Main) { repeat(8) { fixture.model.loadMoreYouTubeItems() } }
            fixture.await { !fixture.model.isRefreshing.value }
            assertEquals(1, calls.get())
            withContext(Dispatchers.Main) { fixture.model.retryHome() }
            fixture.await { fixture.model.homePage.value?.sections?.singleOrNull()?.title == "manual retry" }
            assertEquals(2, calls.get())
            assertFalse(fixture.model.homeLoadFailed.value)
        } finally { fixture.close() }
    }

    @Test
    fun accountChangeDuringSelectedChipContinuationDiscardsItsPageAndTokens() = runBlocking {
        val fixture = Fixture()
        val chip = HomePage.Chip("Old account chip", BrowseEndpoint("FEmusic_home", "old-chip-params"), null)
        val continuationStarted = CompletableDeferred<Unit>()
        val releaseContinuation = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        val releaseFresh = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
        try {
            fixture.start(fetchHome = { token, params, _ ->
                requests += token to params
                when {
                    token == "old-chip-token" -> withContext(NonCancellable) {
                        continuationStarted.complete(Unit)
                        releaseContinuation.await()
                        Result.success(home("obsolete continuation", "obsolete-next"))
                    }
                    token == "new-account-token" -> Result.success(home("new account continuation"))
                    fixture.authUpdates.value == 2L -> {
                        freshStarted.complete(Unit)
                        releaseFresh.await()
                        Result.success(home("new account", "new-account-token"))
                    }
                    params != null -> Result.success(home("old chip", "old-chip-token"))
                    else -> Result.success(home("old account", "old-default-token").copy(chips = listOf(chip)))
                }
            })
            fixture.await { fixture.model.homePage.value != null }
            withContext(Dispatchers.Main) { fixture.model.toggleChip(chip) }
            fixture.await { fixture.model.homePage.value?.continuation == "old-chip-token" }
            withContext(Dispatchers.Main) { fixture.model.loadMoreYouTubeItems() }
            withTimeout(10_000) { continuationStarted.await() }
            withContext(Dispatchers.Main) { fixture.authUpdates.value = 2L }
            withTimeout(10_000) { freshStarted.await() }
            withContext(Dispatchers.Main) {
                assertNull(fixture.model.homePage.value)
                assertNull(fixture.model.selectedChip.value)
                assertFalse(fixture.model.isLoadingMore.value)
                assertTrue(fixture.model.isLoadingHome.value)
                assertFalse(fixture.model.homeLoadFailed.value)
            }
            releaseContinuation.complete(Unit)
            fixture.awaitCancelledRequests()
            releaseFresh.complete(Unit)
            fixture.await { fixture.model.homePage.value?.continuation == "new-account-token" }
            withContext(Dispatchers.Main) { fixture.model.loadMoreYouTubeItems() }
            fixture.await { fixture.model.homePage.value?.sections?.size == 2 && !fixture.model.isLoadingMore.value }
            assertEquals(listOf("new account", "new account continuation"), fixture.model.homePage.value!!.sections.map { it.title })
            assertEquals(listOf(null to null, null to "old-chip-params", "old-chip-token" to null,
                null to null, "new-account-token" to null), requests.toList())
            assertNull(fixture.model.selectedChip.value)
            assertFalse(fixture.model.homeLoadFailed.value)
        } finally { releaseContinuation.complete(Unit); releaseFresh.complete(Unit); fixture.close() }
    }

    @Test
    fun localeAndAuthenticationChangesClearVisibleRemoteStateWhileNewRequestsArePending() = runBlocking {
        val fixture = Fixture()
        val releaseFresh = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        try {
            fixture.start(fetchHome = { _, _, locale ->
                if (locale.hl == "en") {
                    freshStarted.complete(Unit)
                    releaseFresh.await()
                }
                Result.success(home(locale.hl))
            }, fetchExplore = { locale ->
                if (locale.hl == "en") releaseFresh.await()
                Result.success(explore(locale.hl))
            }, hasAccount = { true }, fetchAccountPlaylists = { locale ->
                if (locale.hl == "en") releaseFresh.await()
                Result.success(listOf(playlist(locale.hl)))
            })
            fixture.await { fixture.model.homePage.value != null && !fixture.model.isRefreshing.value }
            withContext(Dispatchers.Main) {
                // A rendered recommendation can outlive the history that originally produced it.
                // Seed that state without adding history that would require unrelated network APIs.
                fixture.model.similarRecommendations.value = listOf(SimilarRecommendation(
                    Artist(ArtistEntity("LA-history", "History artist", isLocal = true), 1, 0), listOf(song("old recommendation"))))
                fixture.locales.value = YouTubeLocale("US", "en")
                fixture.authUpdates.value = 2L
            }
            withTimeout(10_000) { freshStarted.await() }
            withContext(Dispatchers.Main) {
                assertNull(fixture.model.homePage.value)
                assertNull(fixture.model.explorePage.value)
                assertNull(fixture.model.accountPlaylists.value)
                assertNull(fixture.model.similarRecommendations.value)
                assertTrue(fixture.model.allYtItems.value.isEmpty())
            }
            releaseFresh.complete(Unit)
            fixture.await { !fixture.model.isRefreshing.value && fixture.model.homePage.value?.sections?.singleOrNull()?.title == "en" }
            assertEquals("en", fixture.model.accountPlaylists.value?.single()?.title)
            assertEquals("en", fixture.model.explorePage.value?.newReleaseAlbums?.single()?.title)
            assertFalse(fixture.model.loadFailed.value)
        } finally { releaseFresh.complete(Unit); fixture.close() }
    }

    @Test
    fun lateAccountAndExploreRepliesCannotRepopulateAnOlderRequestContext() = runBlocking {
        for (holdAccount in listOf(true, false)) {
            val fixture = Fixture()
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val oldReturned = CompletableDeferred<Unit>()
            val oldRequest = CompletableDeferred<Job>()
            try {
                fixture.start(fetchHome = { _, _, locale -> Result.success(home(locale.hl)) },
                    fetchExplore = { locale ->
                        if (!holdAccount && locale.hl == "ja") {
                            oldRequest.complete(currentCoroutineContext()[Job]!!)
                            withContext(NonCancellable) {
                                oldStarted.complete(Unit)
                                releaseOld.await()
                                oldReturned.complete(Unit)
                            }
                        }
                        Result.success(explore(locale.hl))
                    }, hasAccount = { true }, fetchAccountPlaylists = { locale ->
                        if (holdAccount && locale.hl == "ja") {
                            oldRequest.complete(currentCoroutineContext()[Job]!!)
                            withContext(NonCancellable) {
                                oldStarted.complete(Unit)
                                releaseOld.await()
                                oldReturned.complete(Unit)
                            }
                        }
                        Result.success(listOf(playlist(locale.hl)))
                    })
                withTimeout(10_000) { oldStarted.await() }
                withContext(Dispatchers.Main) {
                    fixture.locales.value = YouTubeLocale("US", "en")
                    fixture.authUpdates.value = 2L
                }
                fixture.await { !fixture.model.isRefreshing.value && fixture.model.explorePage.value?.newReleaseAlbums?.singleOrNull()?.title == "en" }
                releaseOld.complete(Unit)
                withTimeout(10_000) { oldReturned.await() }
                // Wait for the whole old recommendation job, including its state-publication path.
                withTimeout(10_000) { oldRequest.await().join() }
                assertEquals("en", fixture.model.accountPlaylists.value?.single()?.title)
                assertEquals("en", fixture.model.explorePage.value?.newReleaseAlbums?.single()?.title)
                assertEquals("en", fixture.model.homePage.value?.sections?.single()?.title)
                assertFalse(fixture.model.loadFailed.value)
                assertFalse(fixture.model.homeLoadFailed.value)
            } finally { releaseOld.complete(Unit); fixture.close() }
        }
    }

    companion object {
        private fun song(title: String) = SongItem(title, title, emptyList(), thumbnail = "")
        private fun home(title: String, continuation: String? = null) = HomePage(null,
            listOf(HomePage.Section(title, null, null, null, listOf(song(title)))), continuation)
        private fun explore(title: String) = ExplorePage(listOf(AlbumItem("MPRE-$title", null,
            title = title, artists = emptyList(), thumbnail = "")), emptyList())
        private fun playlist(title: String) = PlaylistItem(title, title, null, null, null, null, null, null)
    }
}
