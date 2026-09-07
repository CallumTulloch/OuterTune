package com.dd3boh.outertune.ui

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.R
import com.dd3boh.outertune.fixtures.SearchUiFixtureActivity
import com.dd3boh.outertune.constants.PreferredSearchSourceKey
import com.dd3boh.outertune.constants.SearchSource
import com.dd3boh.outertune.constants.SearchSourceKey
import com.dd3boh.outertune.ui.screens.search.SearchScopeControls
import com.dd3boh.outertune.ui.screens.search.SharedSearchScope
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Compose actions and Preferences DataStore, isolated from the user's settings and network. */
class SharedSearchScopeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun freshOrLegacySettingsStartOnlineAndManualSelectionSurvivesNewStoreAndOwner() {
        for (legacy in listOf(null, SearchSource.LOCAL)) withSettings { settings ->
            if (legacy != null) runBlocking {
                settings.dataStore.edit { it[SearchSourceKey] = legacy.name }
            }
            val connected = MutableStateFlow(true)
            withScopeFixture(settings, connected) { activity, scope ->
                awaitCondition { scope.state.value.isReady }
                click(activity, "scope-open-home")
                awaitSource(activity, scope, SearchSource.ONLINE)
                awaitCondition { settings.read()[PreferredSearchSourceKey] == SearchSource.ONLINE.name }

                click(activity, "search-source-local")
                awaitSource(activity, scope, SearchSource.LOCAL)
                awaitCondition { settings.read()[PreferredSearchSourceKey] == SearchSource.LOCAL.name }
                // A second activation of an already open search is not a new session.
                click(activity, "scope-open-songs")
                awaitSource(activity, scope, SearchSource.LOCAL)
            }
            // The obsolete preference cannot regain control on a later launch.
            runBlocking { settings.dataStore.edit { it[SearchSourceKey] = SearchSource.ONLINE.name } }
            settings.reopen()
            withScopeFixture(settings, connected) { activity, scope ->
                awaitCondition { scope.state.value.isReady }
                assertEquals(SearchSource.LOCAL, scope.state.value.preferredSource)
                for (entry in listOf("home", "songs", "library", "folders")) {
                    click(activity, "scope-open-$entry")
                    awaitSource(activity, scope, SearchSource.LOCAL)
                    click(activity, "scope-close")
                    awaitCondition { !scope.state.value.sessionOpen }
                }
                assertEquals(SearchSource.LOCAL.name, settings.read()[PreferredSearchSourceKey])
                click(activity, "scope-open-library")
                repeat(2) {
                    click(activity, "search-source-online")
                    awaitSource(activity, scope, SearchSource.ONLINE)
                }
                awaitCondition { settings.read()[PreferredSearchSourceKey] == SearchSource.ONLINE.name }
            }
        }
    }

    @Test
    fun losingConnectivityOrStartingOfflineKeepsCurrentSearchLocalUntilTheNextSession() {
        for (initiallyConnected in listOf(true, false)) withSettings { settings ->
            val connected = MutableStateFlow(initiallyConnected)
            withScopeFixture(settings, connected) { activity, scope ->
                awaitCondition { scope.state.value.isReady }
                click(activity, "scope-open-songs")
                if (initiallyConnected) {
                    awaitSource(activity, scope, SearchSource.ONLINE)
                    connected.value = false
                }
                awaitSource(activity, scope, SearchSource.LOCAL)
                awaitCondition { !scope.state.value.networkAvailable && scope.state.value.offlineFallback }
                awaitNotice(activity, R.string.search_scope_offline)
                awaitCondition { onMain {
                    taggedNode(activity, "search-source-online").config.contains(SemanticsProperties.Disabled)
                } }
                onMain { assertFalse("Offline online selection must be rejected", scope.selectSource(SearchSource.ONLINE)) }
                assertEquals(SearchSource.LOCAL, scope.state.value.source)

                connected.value = true
                awaitCondition { scope.state.value.networkAvailable }
                awaitNotice(activity, R.string.search_scope_connection_restored)
                // An already active search and a re-tap retain its results after reconnection.
                click(activity, "scope-open-library")
                awaitSource(activity, scope, SearchSource.LOCAL)
                assertTrue(scope.state.value.offlineFallback)
                awaitCondition { settings.read()[PreferredSearchSourceKey] == SearchSource.ONLINE.name }

                click(activity, "scope-close")
                awaitCondition { !scope.state.value.sessionOpen }
                click(activity, "scope-open-home")
                awaitSource(activity, scope, SearchSource.ONLINE)
                assertFalse(scope.state.value.offlineFallback)
                awaitNotice(activity, null)
            }
        }
    }

    @Test
    fun manuallyReselectingFallbackLocalIsIdempotentAndOverridesTheNextSessionPreference() = withSettings { settings ->
        val connected = MutableStateFlow(true)
        withScopeFixture(settings, connected) { activity, scope ->
            awaitCondition { scope.state.value.isReady }
            click(activity, "scope-open-home")
            awaitSource(activity, scope, SearchSource.ONLINE)
            connected.value = false
            awaitSource(activity, scope, SearchSource.LOCAL)
            connected.value = true
            awaitCondition { scope.state.value.networkAvailable }
            awaitSource(activity, scope, SearchSource.LOCAL)

            repeat(2) {
                click(activity, "search-source-local")
                awaitSource(activity, scope, SearchSource.LOCAL)
            }
            awaitCondition {
                !scope.state.value.offlineFallback &&
                    scope.state.value.preferredSource == SearchSource.LOCAL &&
                    settings.read()[PreferredSearchSourceKey] == SearchSource.LOCAL.name
            }
            awaitNotice(activity, null)
            click(activity, "scope-close")
            click(activity, "scope-open-folders")
            awaitSource(activity, scope, SearchSource.LOCAL)
        }
        settings.reopen()
        withScopeFixture(settings, connected) { activity, scope ->
            awaitCondition { scope.state.value.isReady }
            click(activity, "scope-open-songs")
            awaitSource(activity, scope, SearchSource.LOCAL)
            assertFalse(scope.state.value.offlineFallback)
            assertEquals(SearchSource.LOCAL.name, settings.read()[PreferredSearchSourceKey])
        }
    }

    @Test
    fun openingSearchBeforeSettingsFinishLoadingRetainsAnOfflineFallbackAfterReconnection() = withSettings { settings ->
        val connected = MutableStateFlow(true)
        val initializationStarted = CompletableDeferred<Unit>()
        val allowInitialization = CompletableDeferred<Unit>()
        val backingStore = settings.dataStore
        // Only the storage boundary is delayed. Actual preferences and production state logic
        // still run normally, making the initial-load ordering deterministic.
        val delayedStore = object : DataStore<Preferences> {
            override val data = backingStore.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                initializationStarted.complete(Unit)
                allowInitialization.await()
                return backingStore.updateData(transform)
            }
        }
        try {
            withScopeFixture(settings, connected, preferences = delayedStore) { activity, scope ->
                awaitCondition { initializationStarted.isCompleted }
                assertFalse(scope.state.value.isReady)
                click(activity, "scope-open-home")
                connected.value = false
                awaitCondition { !scope.state.value.networkAvailable && scope.state.value.offlineFallback }
                connected.value = true
                awaitCondition { scope.state.value.networkAvailable }
                allowInitialization.complete(Unit)

                awaitSource(activity, scope, SearchSource.LOCAL)
                assertTrue(scope.state.value.sessionOpen)
                assertTrue(scope.state.value.offlineFallback)
                awaitCondition { settings.read()[PreferredSearchSourceKey] == SearchSource.ONLINE.name }
                click(activity, "scope-close")
                click(activity, "scope-open-folders")
                awaitSource(activity, scope, SearchSource.ONLINE)
            }
        } finally {
            allowInitialization.complete(Unit)
        }
    }

    private fun withScopeFixture(
        settings: StoredSettings,
        connectivity: MutableStateFlow<Boolean>,
        preferences: DataStore<Preferences> = settings.dataStore,
        block: (SearchUiFixtureActivity, SharedSearchScope) -> Unit,
    ) {
        val owner = SupervisorJob()
        val scope = onMain {
            SharedSearchScope(preferences, connectivity, CoroutineScope(Dispatchers.Main.immediate + owner))
        }
        try {
            withFixture { activity ->
                show(activity) {
                    val state by scope.state.collectAsState()
                    MaterialTheme {
                        Column {
                            // The same production actions are wired to each entry. No route can
                            // override source choice because the shared API accepts no origin.
                            listOf("home", "songs", "library", "folders").forEach { entry ->
                                Button(onClick = scope::open, modifier = Modifier.testTag("scope-open-$entry")) {
                                    Text("Open from $entry")
                                }
                            }
                            Button(onClick = scope::close, modifier = Modifier.testTag("scope-close")) {
                                Text("Close search")
                            }
                            SearchScopeControls(state, onSelect = { scope.selectSource(it) })
                            Text(state.source.name, modifier = Modifier.testTag("scope-source"))
                        }
                    }
                }
                block(activity, scope)
            }
        } finally {
            runBlocking { owner.cancelAndJoin() }
        }
    }

    private fun awaitSource(activity: SearchUiFixtureActivity, scope: SharedSearchScope, expected: SearchSource) {
        val tag = if (expected == SearchSource.ONLINE) "search-source-online" else "search-source-local"
        awaitCondition {
            scope.state.value.isReady && scope.state.value.source == expected && onMain {
                val currentNodes = nodes(activity)
                val text = currentNodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "scope-source" }
                    ?.config?.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
                val selected = currentNodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
                    ?.config?.getOrNull(SemanticsProperties.Selected)
                text == expected.name && selected == true
            }
        }
    }

    private fun awaitNotice(activity: SearchUiFixtureActivity, resource: Int?) = awaitCondition {
        onMain {
            val node = nodes(activity).firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == "search-connection-notice"
            }
            if (resource == null) node == null
            else node?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == activity.getString(resource)
        }
    }

    private inner class StoredSettings {
        private val file = File(instrumentation.targetContext.cacheDir,
            "search-scope-${UUID.randomUUID()}.preferences_pb")
        private var job: Job = SupervisorJob()
        var dataStore: DataStore<Preferences> = createStore()
            private set

        private fun createStore() = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )

        fun read(): Preferences = runBlocking { dataStore.data.first() }

        /** A new store/owner reads the same disk file, without retaining its former in-memory state. */
        fun reopen() {
            runBlocking { job.cancelAndJoin() }
            job = SupervisorJob()
            dataStore = createStore()
        }

        fun close() {
            runBlocking { job.cancelAndJoin() }
            if (file.exists()) assertTrue("Failed to remove isolated test settings", file.delete())
        }
    }

    private fun withSettings(block: (StoredSettings) -> Unit) {
        val settings = StoredSettings()
        try { block(settings) } finally { settings.close() }
    }

    private fun withFixture(block: (SearchUiFixtureActivity) -> Unit) {
        val intent = Intent(instrumentation.targetContext, SearchUiFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as SearchUiFixtureActivity
        try { block(activity) } finally {
            onMain {
                activity.fixtureView.disposeComposition()
                activity.finish()
            }
        }
    }

    private fun show(activity: SearchUiFixtureActivity, content: @Composable () -> Unit) {
        onMain { activity.fixtureView.setContent(content) }
    }

    private fun nodes(activity: SearchUiFixtureActivity): List<SemanticsNode> =
        if (activity.fixtureView.childCount == 0) emptyList()
        else (activity.fixtureView.getChildAt(0) as ViewRootForTest)
            .semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)

    private fun taggedNode(activity: SearchUiFixtureActivity, tag: String): SemanticsNode =
        nodes(activity).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun click(activity: SearchUiFixtureActivity, tag: String) {
        awaitCondition { onMain { nodes(activity).any { it.config.getOrNull(SemanticsProperties.TestTag) == tag } } }
        onMain {
            assertTrue("No click action on $tag",
                taggedNode(activity, tag).config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Search scope did not reach the expected state" }
            SystemClock.sleep(16)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
