package com.dd3boh.outertune.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.UseLoginForBrowse
import com.dd3boh.outertune.constants.VisitorDataKey
import com.zionhuang.innertube.models.AccountInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AuthenticationRepositoryTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        val commits = mutableListOf<Preferences>()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { commits += it; data.value = it }
        }
    }

    private class AppliedAuthentication {
        var revision = 0L
        var current: AuthenticationPreferences? = null
        val history = mutableListOf<AuthenticationPreferences>()
        fun apply(value: AuthenticationPreferences) {
            if (current != value) revision++
            current = value
            history += value
        }
    }

    private fun savedAccount() = preferencesOf(
        InnerTubeCookieKey to "SAPISID=test-account",
        VisitorDataKey to "test-visitor",
        DataSyncIdKey to "test-parent||test-channel",
        UseLoginForBrowse to false,
    )

    @Test
    fun startupAndLoginPublishOneCompleteAuthenticationSnapshot() = runBlocking {
        val store = MemoryStore(savedAccount())
        val applied = AppliedAuthentication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = AuthenticationRepository(store, AuthenticationRepository.Runtime(scope,
            fetchVisitorData = { error("Saved visitor must not be fetched") },
            apply = applied::apply, revision = { applied.revision }))
        try {
            repository.start()
            assertEquals(AuthenticationPreferences("SAPISID=test-account", "test-visitor", "test-channel", false), applied.current)
            val session = repository.saveLogin("SAPISID=new-account", "new-visitor", "new-channel||")
            assertEquals(AuthenticationPreferences("SAPISID=new-account", "new-visitor", "new-channel", false), applied.current)
            assertEquals(session.preferences, store.data.value.authenticationPreferences())
            assertEquals(1, store.commits.size)
            assertTrue(applied.history.all { it.cookie == "SAPISID=test-account" && it.visitorData == "test-visitor" ||
                it.cookie == "SAPISID=new-account" && it.visitorData == "new-visitor" })
        } finally { scope.cancel() }
    }

    @Test
    fun delayedAnonymousVisitorCannotOverwriteFreshLogin() = runBlocking {
        val store = MemoryStore(emptyPreferences())
        val applied = AppliedAuthentication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val oldReply = CompletableDeferred<String>()
        val finished = CompletableDeferred<Unit>()
        val repository = AuthenticationRepository(store, AuthenticationRepository.Runtime(scope,
            fetchVisitorData = {
                // Model a transport callback which completes despite cancellation.
                withContext(NonCancellable) {
                    started.complete(Unit)
                    oldReply.await().also { finished.complete(Unit) }
                }
            }, apply = applied::apply, revision = { applied.revision }))
        try {
            repository.start()
            withTimeout(3000) { started.await() }
            val session = repository.saveLogin("SAPISID=new-account", "new-visitor", "new-channel")
            oldReply.complete("obsolete-anonymous-visitor")
            withTimeout(3000) { finished.await() }
            yield()
            assertEquals(session.preferences, store.data.value.authenticationPreferences())
            assertEquals(session.preferences, applied.current)
            assertFalse(applied.history.any { it.visitorData == "obsolete-anonymous-visitor" })
        } finally {
            oldReply.complete("obsolete-anonymous-visitor")
            scope.cancel()
        }
    }

    @Test
    fun logoutPublishesClearedCredentialsBeforeReturningAndKeepsBrowsePreference() = runBlocking {
        val store = MemoryStore(savedAccount())
        val applied = AppliedAuthentication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = AuthenticationRepository(store, AuthenticationRepository.Runtime(scope,
            fetchVisitorData = { null }, apply = applied::apply, revision = { applied.revision }))
        try {
            repository.start()
            repository.clear()
            assertEquals(AuthenticationPreferences(null, null, null, false), applied.current)
            assertEquals(applied.current, store.data.value.authenticationPreferences())
            assertNull(store.data.value[InnerTubeCookieKey])
            assertNull(store.data.value[VisitorDataKey])
            assertNull(store.data.value[DataSyncIdKey])
        } finally { scope.cancel() }
    }

    @Test
    fun sameAccountReloginCannotAdoptPreviousGenerationProfile() = runBlocking {
        val store = MemoryStore(emptyPreferences())
        val applied = AppliedAuthentication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = AuthenticationRepository(store, AuthenticationRepository.Runtime(scope,
            fetchVisitorData = { null }, apply = applied::apply, revision = { applied.revision }))
        try {
            repository.start()
            val old = repository.saveLogin("SAPISID=same-account", "same-visitor", "same-channel")
            repository.clear()
            val fresh = repository.saveLogin("SAPISID=same-account", "same-visitor", "same-channel")
            assertEquals(old.preferences, fresh.preferences)
            assertTrue(fresh.revision > old.revision)
            repository.saveAccountInfo(old, AccountInfo("Obsolete profile", null, null))
            assertNull(store.data.first()[AccountNameKey])
            repository.saveAccountInfo(fresh, AccountInfo("Current profile", null, null))
            assertEquals("Current profile", store.data.first()[AccountNameKey])
        } finally { scope.cancel() }
    }

    @Test
    fun failedVisitorLookupKeepsWatchingLaterSavedAuthentication() = runBlocking {
        val store = MemoryStore(emptyPreferences())
        val applied = AppliedAuthentication()
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> uncaught += error })
        val repository = AuthenticationRepository(store, AuthenticationRepository.Runtime(scope,
            fetchVisitorData = { throw java.io.IOException("fixture offline") },
            apply = applied::apply, revision = { applied.revision }))
        try {
            repository.start()
            store.data.value = savedAccount()
            yield()
            assertTrue(uncaught.isEmpty())
            assertEquals(savedAccount().authenticationPreferences(), applied.current)
        } finally { scope.cancel() }
    }

    @Test
    fun rejectedVisitorDoesNotReapplyOldPreferencesAfterAnExternalRuntimeGenerationChange() = runBlocking {
        val store = MemoryStore(emptyPreferences())
        val applied = AppliedAuthentication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<String>()
        val repository = AuthenticationRepository(store, AuthenticationRepository.Runtime(scope,
            fetchVisitorData = { started.complete(Unit); reply.await() },
            apply = applied::apply, revision = { applied.revision }))
        try {
            repository.start()
            withTimeout(3000) { started.await() }
            applied.revision++ // The preferences deliberately remain unchanged.
            val applicationsBeforeReply = applied.history.size
            reply.complete("obsolete-visitor")
            yield()
            assertNull(store.data.value[VisitorDataKey])
            assertEquals(applicationsBeforeReply, applied.history.size)
        } finally { reply.complete("obsolete-visitor"); scope.cancel() }
    }

    @Test
    fun advancedAccountEditSavesCredentialsAndExplicitProfileTogetherWithoutTransientMixedAccounts() = runBlocking {
        val store = MemoryStore(savedAccount())
        val applied = AppliedAuthentication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = AuthenticationRepository(store, AuthenticationRepository.Runtime(scope,
            fetchVisitorData = { null }, apply = applied::apply, revision = { applied.revision }))
        try {
            repository.start()
            val info = AccountInfo("Edited profile", "edited@example.test", "@edited")
            val session = repository.saveEditedAccount("SAPISID=edited", "edited-visitor", "parent||edited-channel", info)
            assertEquals(1, store.commits.size)
            assertEquals(AuthenticationPreferences("SAPISID=edited", "edited-visitor", "edited-channel", false), session.preferences)
            assertEquals(info.name, store.data.value[AccountNameKey])
            assertEquals(info.email, store.data.value[AccountEmailKey])
            assertEquals(info.channelHandle, store.data.value[AccountChannelHandleKey])
            assertTrue(applied.history.all { it == savedAccount().authenticationPreferences() || it == session.preferences })

            repository.saveLogin("SAPISID=edited", "edited-visitor", "edited-channel")
            assertEquals(info.name, store.data.value[AccountNameKey])
            repository.saveLogin("SAPISID=another", "another-visitor", "delegated||user")
            assertNull(store.data.value[AccountNameKey])
            assertEquals("delegated", store.data.value[DataSyncIdKey])
        } finally { scope.cancel() }
    }

    @Test
    fun loginAndStoredPreferencesKeepTheirEstablishedDataSyncComponentSelection() {
        assertEquals("channel", normalizedDataSyncId("parent||channel"))
        assertEquals("parent", normalizedLoginDataSyncId("parent||channel"))
        assertEquals("parent", normalizedDataSyncId("parent||"))
        assertEquals("parent", normalizedLoginDataSyncId("parent||"))
        assertEquals("single", normalizedDataSyncId(" single "))
        assertEquals("single", normalizedLoginDataSyncId(" single "))
        listOf(null, "", "  ", "null", "undefined", "||").forEach {
            assertNull(normalizedDataSyncId(it))
            assertNull(normalizedLoginDataSyncId(it))
        }
        assertEquals("test-channel", savedAccount().authenticationPreferences().dataSyncId)
    }

    @Test
    fun diagnosticStringsDoNotContainAuthenticationValues() {
        val preferences = AuthenticationPreferences("private-cookie", "private-visitor", "private-account", true)
        val session = AuthenticationRepository.Session(preferences, 8)
        listOf(preferences.toString(), session.toString()).forEach { text ->
            assertFalse(text.contains("private-"))
        }
    }
}
