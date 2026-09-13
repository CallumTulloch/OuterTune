package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.repositories.ArtistCreditRepository
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.pages.ArtistPage
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real ViewModel/Room observation with delayed, non-cancellable fake page replies. */
class ArtistPageLinkTest {
    private val targetA = "UC" + "a".repeat(22)
    private val targetB = "UC" + "b".repeat(22)
    private fun page(id: String, title: String = id) = ArtistPage(
        ArtistItem(id = id, title = title, thumbnail = "https://image.invalid/$title",
            shuffleEndpoint = null, radioEndpoint = null), emptyList(), null,
    )

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "link-page-test-${UUID.randomUUID()}"
        val isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(prefsName, mode)
        }
        val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(room)
        val local = ArtistEntity("LA-manual-page-test", "元のローカル表記", isLocal = true)
        val creditJob = SupervisorJob()
        var contextKey = "ja:account-one"
        val credits = ArtistCreditRepository(database, isolatedContext, ArtistCreditRepository.Runtime(
            scope = CoroutineScope(creditJob + Dispatchers.IO), contextToken = { contextKey }, authRevision = { 0L },
            fetch = { error("Artist page test must never fetch song credits") },
        ))
        lateinit var model: ArtistViewModel

        suspend fun start(fetch: suspend (String) -> Result<ArtistPage>) {
            database.insert(local)
            withContext(Dispatchers.Main) {
                model = ArtistViewModel(database, SavedStateHandle(mapOf("artistId" to local.id)), credits,
                    ArtistViewModel.Runtime { id, _ -> fetch(id) })
            }
        }
        fun link(id: String, revision: String) = database.setLocalArtistLink(
            LocalArtistLink(local.id, id, "選択済み", "https://image.invalid/snapshot", revision),
        )
        suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(20)
        }
        suspend fun close() {
            if (::model.isInitialized) withContext(Dispatchers.Main) { model.viewModelScope.cancel() }
            creditJob.cancelAndJoin()
            room.close()
            context.deleteSharedPreferences(prefsName)
        }
    }

    @Test
    fun changedOrRemovedLinkCannotBeOverwrittenByAnOlderArtistPage() = runBlocking {
        val fixture = Fixture()
        val aStarted = CompletableDeferred<Unit>()
        val aReply = CompletableDeferred<Unit>()
        val aReturned = CompletableDeferred<Unit>()
        try {
            fixture.start { id ->
                if (id == targetA) withContext(NonCancellable) { aStarted.complete(Unit); aReply.await() }
                Result.success(page(id)).also { if (id == targetA) aReturned.complete(Unit) }
            }
            fixture.link(targetA, "first")
            withTimeout(10_000) { aStarted.await() }
            fixture.link(targetB, "second")
            fixture.await { fixture.model.artistPage?.artist?.id == targetB }
            aReply.complete(Unit)
            withTimeout(10_000) { aReturned.await() }
            withContext(Dispatchers.Main) { yield() }
            fixture.await { !fixture.model.isLoading.value }
            withContext(Dispatchers.Main) { assertEquals(targetB, fixture.model.artistPage?.artist?.id) }
            assertTrue(fixture.database.removeLocalArtistLink(fixture.local.id, "second"))
            fixture.await { fixture.model.onlineArtistId.value == null && fixture.model.artistPage == null }
            assertEquals(fixture.local, fixture.database.artistById(fixture.local.id))
        } finally { aReply.complete(Unit); fixture.close() }
    }

    @Test
    fun contextChangeDiscardsOldPageEvenWhenTheLinkedArtistIsTheSame() = runBlocking {
        val fixture = Fixture()
        val oldStarted = CompletableDeferred<Unit>()
        val oldReply = CompletableDeferred<Unit>()
        val oldReturned = CompletableDeferred<Unit>()
        try {
            fixture.start { id ->
                val token = fixture.contextKey
                if (token.startsWith("ja")) withContext(NonCancellable) { oldStarted.complete(Unit); oldReply.await() }
                Result.success(page(id, token)).also { if (token.startsWith("ja")) oldReturned.complete(Unit) }
            }
            fixture.link(targetA, "same-link")
            withTimeout(10_000) { oldStarted.await() }
            withContext(Dispatchers.Main) {
                fixture.contextKey = "en:account-two"
                fixture.model.refreshArtistContext()
            }
            fixture.await { fixture.model.artistPage?.artist?.title == "en:account-two" }
            oldReply.complete(Unit)
            withTimeout(10_000) { oldReturned.await() }
            withContext(Dispatchers.Main) { yield() }
            fixture.await { !fixture.model.isLoading.value }
            withContext(Dispatchers.Main) { assertEquals("en:account-two", fixture.model.artistPage?.artist?.title) }
            assertEquals("same-link", fixture.database.localArtistLinkById(fixture.local.id)?.revision)
            assertEquals(fixture.local, fixture.database.artistById(fixture.local.id))
        } finally { oldReply.complete(Unit); fixture.close() }
    }
}
