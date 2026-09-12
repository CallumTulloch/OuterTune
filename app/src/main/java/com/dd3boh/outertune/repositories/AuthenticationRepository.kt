package com.dd3boh.outertune.repositories

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.UseLoginForBrowse
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AccountInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun normalizedAuthenticationValue(value: String?): String? = value?.trim()?.takeUnless {
    it.isEmpty() || it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true)
}

internal fun normalizedDataSyncId(value: String?): String? = normalizedAuthenticationValue(value)?.let {
    normalizedAuthenticationValue(when {
        !it.contains("||") -> it
        it.endsWith("||") -> it.substringBefore("||")
        else -> it.substringAfter("||")
    })
}

/** Preserve the login page's existing delegated-account selection; legacy imports use the helper above. */
internal fun normalizedLoginDataSyncId(value: String?): String? =
    normalizedAuthenticationValue(value)?.substringBefore("||")?.let(::normalizedAuthenticationValue)

internal data class AuthenticationPreferences(
    val cookie: String?,
    val visitorData: String?,
    val dataSyncId: String?,
    val useLoginForBrowse: Boolean,
) {
    override fun toString() = "AuthenticationPreferences(useLoginForBrowse=$useLoginForBrowse)"
}

internal fun Preferences.authenticationPreferences() = AuthenticationPreferences(
    normalizedAuthenticationValue(this[InnerTubeCookieKey]),
    normalizedAuthenticationValue(this[VisitorDataKey]),
    normalizedDataSyncId(this[DataSyncIdKey]),
    this[UseLoginForBrowse] != false,
)

/** Publish saved authentication as one generation; delayed anonymous lookups cannot undo a login. */
@Singleton
class AuthenticationRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val runtime: Runtime,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.dataStore, Runtime())

    internal class Runtime(
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        val fetchVisitorData: suspend () -> String? = { YouTube.visitorData().getOrNull() },
        val apply: (AuthenticationPreferences) -> Unit = {
            YouTube.setAuthentication(it.cookie, it.visitorData, it.dataSyncId, it.useLoginForBrowse)
        },
        val revision: () -> Long = { YouTube.authRevision },
    )

    internal data class Session(val preferences: AuthenticationPreferences, val revision: Long) {
        override fun toString() = "Session(revision=$revision)"
    }

    private val mutex = Mutex()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        // Repositories and the first screen must see the complete saved account immediately.
        runBlocking(Dispatchers.IO) {
            mutex.withLock { runtime.apply(dataStore.data.first().authenticationPreferences()) }
        }
        runtime.scope.launch {
            dataStore.data.map(Preferences::authenticationPreferences).distinctUntilChanged().collectLatest { observed ->
                val session = mutex.withLock {
                    if (dataStore.data.first().authenticationPreferences() != observed) null
                    else {
                        runtime.apply(observed)
                        Session(observed, runtime.revision())
                    }
                } ?: return@collectLatest
                if (observed.visitorData != null) return@collectLatest
                val visitor = try {
                    normalizedAuthenticationValue(runtime.fetchVisitorData())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed lookup must not stop observing later logins or saved credential changes.
                    return@collectLatest
                } ?: return@collectLatest
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    var accepted = false
                    val saved = dataStore.edit { current ->
                        if (current.authenticationPreferences() == session.preferences &&
                            runtime.revision() == session.revision) {
                            current[VisitorDataKey] = visitor
                            accepted = true
                        }
                    }
                    if (accepted) runtime.apply(saved.authenticationPreferences())
                }
            }
        }
    }

    internal suspend fun saveLogin(cookie: String?, visitorData: String?, dataSyncId: String?): Session =
        saveAuthentication(cookie, visitorData, normalizedLoginDataSyncId(dataSyncId))

    internal suspend fun saveEditedAccount(cookie: String?, visitorData: String?, dataSyncId: String?, info: AccountInfo): Session =
        saveAuthentication(cookie, visitorData, dataSyncId, info)

    private suspend fun saveAuthentication(cookie: String?, visitorData: String?, dataSyncId: String?, info: AccountInfo? = null): Session = mutex.withLock {
        val saved = dataStore.edit { preferences ->
            val previous = preferences.authenticationPreferences()
            preferences.putOrRemove(InnerTubeCookieKey, normalizedAuthenticationValue(cookie))
            preferences.putOrRemove(VisitorDataKey, normalizedAuthenticationValue(visitorData))
            preferences.putOrRemove(DataSyncIdKey, normalizedDataSyncId(dataSyncId))
            if (info != null) {
                preferences[AccountNameKey] = info.name
                preferences[AccountEmailKey] = info.email.orEmpty()
                preferences[AccountChannelHandleKey] = info.channelHandle.orEmpty()
            } else if (previous != preferences.authenticationPreferences()) {
                preferences.remove(AccountNameKey)
                preferences.remove(AccountEmailKey)
                preferences.remove(AccountChannelHandleKey)
            }
        }.authenticationPreferences()
        runtime.apply(saved)
        Session(saved, runtime.revision())
    }

    internal suspend fun saveAccountInfo(session: Session, info: AccountInfo) = mutex.withLock {
        dataStore.edit { preferences ->
            if (runtime.revision() == session.revision && preferences.authenticationPreferences() == session.preferences) {
                preferences[AccountNameKey] = info.name
                preferences[AccountEmailKey] = info.email.orEmpty()
                preferences[AccountChannelHandleKey] = info.channelHandle.orEmpty()
            }
        }
    }

    suspend fun clear() = mutex.withLock {
        val saved = dataStore.edit { preferences ->
            preferences.remove(InnerTubeCookieKey)
            preferences.remove(VisitorDataKey)
            preferences.remove(DataSyncIdKey)
            preferences.remove(AccountNameKey)
            preferences.remove(AccountEmailKey)
            preferences.remove(AccountChannelHandleKey)
        }
        runtime.apply(saved.authenticationPreferences())
    }
}

private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
    if (value == null) remove(key) else this[key] = value
}
