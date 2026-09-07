package com.dd3boh.outertune.ui

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.constants.SearchSource
import com.dd3boh.outertune.fixtures.SearchUiFixtureActivity
import com.dd3boh.outertune.ui.screens.search.SearchInputState
import com.dd3boh.outertune.ui.screens.search.SearchScopeState
import com.dd3boh.outertune.ui.screens.search.SearchSession
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production search input, source controls and focus handling inside a real Compose NavHost.
 * Result rows/categories are deterministic fixtures: this tests session/navigation lifetime,
 * not the online transport, database query or production detail screen contents.
 */
class SearchSessionNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun localTypingAndImeSubmissionKeepTheEntryAndMountedResults() = withFixture(SearchSource.LOCAL) { activity, probe ->
        click(activity, "fixture-open-search")
        awaitSearch(activity, probe)
        enterText(activity, "first local query")
        awaitText(activity, "fixture-query", "LOCAL:first local query")
        val entryId = onMain { probe.nav.currentBackStackEntry!!.id }
        val initialMounts = onMain { probe.resultMounts }

        submitIme(activity)
        awaitCondition {
            onMain {
                probe.input?.submittedQuery == "first local query" && probe.input?.focusRequested == false &&
                    searchField(activity).config.getOrNull(SemanticsProperties.Focused) != true
            }
        }
        assertEquals(entryId, onMain { probe.nav.currentBackStackEntry!!.id })
        assertEquals(initialMounts, onMain { probe.resultMounts })
        assertEquals("first local query", searchText(activity))
        assertFalse(onMain { searchField(activity).config.getOrNull(SemanticsProperties.Focused) == true })
        awaitText(activity, "fixture-query", "LOCAL:first local query")

        enterText(activity, "second local query")
        // Local output follows editing, without requiring another submission.
        awaitText(activity, "fixture-query", "LOCAL:second local query")
        submitIme(activity)
        awaitCondition { onMain { probe.submissions.size == 2 && probe.input?.focusRequested == false } }
        assertEquals(entryId, onMain { probe.nav.currentBackStackEntry!!.id })
        assertEquals(initialMounts, onMain { probe.resultMounts })
        assertEquals(listOf("first local query", "second local query"), onMain { probe.submissions.toList() })

        tap(activity, "search-back")
        awaitRoute(probe, "origin")
    }

    @Test
    fun detailBackRestoresQuerySourceCategoryAndScrollWithoutRequestingFocus() = withFixture(SearchSource.LOCAL) { activity, probe ->
        click(activity, "fixture-open-search")
        awaitSearch(activity, probe)
        enterText(activity, "saved artist")
        click(activity, "fixture-category-artists")
        awaitText(activity, "fixture-category", "artists")
        awaitCondition { onMain { probe.input?.focusRequested == false } }
        val entryId = onMain { probe.nav.currentBackStackEntry!!.id }

        onMain {
            assertTrue(taggedNode(activity, "fixture-results").config
                .getOrNull(SemanticsActions.ScrollToIndex)?.action?.invoke(20) == true)
        }
        awaitCondition { onMain { probe.listState?.firstVisibleItemIndex == 20 } }
        val savedOffset = onMain { probe.listState!!.firstVisibleItemScrollOffset }
        click(activity, "fixture-open-artist")
        awaitRoute(probe, "artist")
        // Wait for NavHost to actually dispose the search content after its transition;
        // returning then exercises saved state, rather than only a retained composition.
        awaitCondition { onMain { probe.resultDisposals > 0 } }
        click(activity, "fixture-detail-back")
        awaitSearch(activity, probe)

        assertEquals(entryId, onMain { probe.nav.currentBackStackEntry!!.id })
        assertEquals("saved artist", searchText(activity))
        awaitText(activity, "fixture-query", "LOCAL:saved artist")
        awaitText(activity, "fixture-category", "artists")
        awaitCondition { onMain {
            probe.listState?.firstVisibleItemIndex == 20 &&
                probe.listState?.firstVisibleItemScrollOffset == savedOffset
        } }
        assertFalse(onMain { probe.input!!.focusRequested })
        assertFalse(onMain { searchField(activity).config.getOrNull(SemanticsProperties.Focused) == true })
        assertTrue(onMain { taggedNode(activity, "search-source-local").config
            .getOrNull(SemanticsProperties.Selected) == true })

        tap(activity, "search-back")
        awaitRoute(probe, "origin")
    }

    @Test
    fun successiveOnlineSubmissionsAndSourceSwitchesUseLatestInputInOneEntry() = withFixture(SearchSource.ONLINE) { activity, probe ->
        click(activity, "fixture-open-search")
        awaitSearch(activity, probe)
        val entryId = onMain { probe.nav.currentBackStackEntry!!.id }

        for (query in listOf("Nirvana", "Quruli")) {
            enterText(activity, query)
            submitIme(activity)
            awaitText(activity, "fixture-query", "ONLINE:$query")
            assertEquals(entryId, onMain { probe.nav.currentBackStackEntry!!.id })
        }
        assertEquals(listOf("Nirvana", "Quruli"), onMain { probe.submissions.toList() })

        click(activity, "search-source-local")
        awaitText(activity, "fixture-query", "LOCAL:Quruli")
        assertEquals("Quruli", searchText(activity))
        enterText(activity, "new local input")
        awaitText(activity, "fixture-query", "LOCAL:new local input")
        click(activity, "search-source-online")
        awaitText(activity, "fixture-query", "ONLINE:new local input")
        assertEquals(entryId, onMain { probe.nav.currentBackStackEntry!!.id })
        assertEquals("new local input", searchText(activity))
        assertFalse(onMain { probe.input!!.focusRequested })

        // A single exit returns to the origin: queries and source choices added no destinations.
        tap(activity, "search-back")
        awaitRoute(probe, "origin")
    }

    @Test
    fun physicalEnterSubmitsOnceAndClearsTheFocusedSearchField() = withFixture(SearchSource.ONLINE) { activity, probe ->
        click(activity, "fixture-open-search")
        awaitSearch(activity, probe)
        awaitCondition { onMain {
            probe.nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED
        } }
        enterText(activity, "physical Enter query")
        onMain {
            probe.input!!.focusRequested = true
            assertTrue("Search field could not acquire focus", searchField(activity).config
                .getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true)
        }
        awaitCondition { onMain {
            activity.hasWindowFocus() &&
                searchField(activity).config.getOrNull(SemanticsProperties.Focused) == true
        } }
        assertTrue(onMain { probe.input!!.showingSuggestions })
        assertTrue(onMain { probe.submissions.isEmpty() })
        val entryId = onMain { probe.nav.currentBackStackEntry!!.id }

        // Dispatch the physical key pair through the focused view, not OnImeAction.
        // BasicTextField's key consumption must not swallow the search callback.
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action,
                KeyEvent.KEYCODE_ENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD)
            onMain {
                assertTrue("Search field did not handle Enter action $action",
                    activity.window.decorView.dispatchKeyEvent(event))
            }
        }
        awaitCondition { onMain {
            probe.input?.submittedQuery == "physical Enter query" &&
                probe.input?.focusRequested == false &&
                probe.input?.showingSuggestions == false &&
                searchField(activity).config.getOrNull(SemanticsProperties.Focused) != true
        } }
        assertEquals(listOf("physical Enter query"), onMain { probe.submissions.toList() })
        assertEquals(entryId, onMain { probe.nav.currentBackStackEntry!!.id })
        awaitText(activity, "fixture-query", "ONLINE:physical Enter query")

        tap(activity, "search-back")
        awaitRoute(probe, "origin")
    }

    private class SessionProbe {
        lateinit var nav: NavHostController
        var input: SearchInputState? = null
        var listState: LazyListState? = null
        var resultMounts = 0
        var resultDisposals = 0
        val submissions = mutableListOf<String>()
    }

    @Composable
    private fun SessionFixture(initialSource: SearchSource, probe: SessionProbe) {
        val nav = rememberNavController()
        SideEffect { probe.nav = nav }
        MaterialTheme {
            CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0)) {
                NavHost(nav, startDestination = "origin", modifier = Modifier.fillMaxSize()) {
                    composable("origin") {
                        Button(onClick = { nav.navigate("search") }, modifier = Modifier.testTag("fixture-open-search")) {
                            Text("Open search")
                        }
                    }
                    composable("search") {
                        val input = rememberSaveable(saver = SearchInputState.Saver) { SearchInputState() }
                        var sourceName by rememberSaveable { mutableStateOf(initialSource.name) }
                        val source = SearchSource.valueOf(sourceName)
                        SideEffect { probe.input = input }
                        SearchSession(
                            input = input,
                            state = SearchScopeState(isReady = true, source = source,
                                preferredSource = source, sessionOpen = true),
                            onExit = { nav.popBackStack() },
                            onSelectSource = { selected ->
                                sourceName = selected.name
                                input.submit(input.query.text)
                            },
                            onSubmit = { query ->
                                input.submit(query)
                                probe.submissions.add(query)
                            },
                        ) { dismissKeyboard ->
                            var category by rememberSaveable { mutableStateOf("all") }
                            val listState = rememberLazyListState()
                            SideEffect { probe.listState = listState }
                            DisposableEffect(Unit) {
                                probe.resultMounts++
                                onDispose { probe.resultDisposals++ }
                            }
                            Column(Modifier.fillMaxSize()) {
                                Text("${source.name}:${if (source == SearchSource.LOCAL) input.query.text else input.submittedQuery}",
                                    modifier = Modifier.testTag("fixture-query"))
                                Text(category, modifier = Modifier.testTag("fixture-category"))
                                Button(onClick = { dismissKeyboard(); category = "artists" },
                                    modifier = Modifier.testTag("fixture-category-artists")) { Text("Artists fixture") }
                                Button(onClick = { dismissKeyboard(); nav.navigate("artist") },
                                    modifier = Modifier.testTag("fixture-open-artist")) { Text("Open artist fixture") }
                                LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("fixture-results")) {
                                    items(60) { index -> Text("Fixture row $index", modifier = Modifier.height(48.dp)) }
                                }
                            }
                        }
                    }
                    composable("artist") {
                        Button(onClick = { nav.popBackStack() }, modifier = Modifier.testTag("fixture-detail-back")) {
                            Text("Return from artist fixture")
                        }
                    }
                }
            }
        }
    }

    private fun withFixture(initialSource: SearchSource, block: (SearchUiFixtureActivity, SessionProbe) -> Unit) {
        val intent = Intent(instrumentation.targetContext, SearchUiFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as SearchUiFixtureActivity
        val probe = SessionProbe()
        try {
            onMain { activity.fixtureView.setContent { SessionFixture(initialSource, probe) } }
            block(activity, probe)
        } finally {
            onMain { activity.fixtureView.disposeComposition(); activity.finish() }
        }
    }

    private fun awaitSearch(activity: SearchUiFixtureActivity, probe: SessionProbe) {
        awaitRoute(probe, "search")
        awaitCondition { onMain { nodes(activity).any {
            it.config.getOrNull(SemanticsProperties.TestTag) == "fixture-results"
        } } }
    }

    private fun awaitRoute(probe: SessionProbe, route: String) = awaitCondition {
        onMain { probe.nav.currentBackStackEntry?.destination?.route == route }
    }

    private fun enterText(activity: SearchUiFixtureActivity, text: String) {
        onMain {
            assertTrue("Search field rejected text", searchField(activity).config
                .getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text)) == true)
        }
        awaitCondition { searchText(activity) == text }
    }

    private fun submitIme(activity: SearchUiFixtureActivity) = onMain {
        assertTrue("Search IME action was unavailable", searchField(activity).config
            .getOrNull(SemanticsActions.OnImeAction)?.action?.invoke() == true)
    }

    private fun searchText(activity: SearchUiFixtureActivity): String = onMain {
        searchField(activity).config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    }

    private fun searchField(activity: SearchUiFixtureActivity): SemanticsNode = nodes(activity).single {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Search") == true &&
            it.config.getOrNull(SemanticsActions.SetText) != null
    }

    private fun awaitText(activity: SearchUiFixtureActivity, tag: String, expected: String) = awaitCondition {
        onMain { nodes(activity).any {
            it.config.getOrNull(SemanticsProperties.TestTag) == tag &&
                it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { value -> value.text } == expected
        } }
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
            assertTrue("No click action on $tag", taggedNode(activity, tag).config
                .getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        }
    }

    private fun tap(activity: SearchUiFixtureActivity, tag: String) {
        // The app's custom IconButton has an outer combinedClickable with an empty
        // semantics click. A real tap reaches the inner Material IconButton handler.
        // Dispatch through the fixture view hierarchy to exercise Compose hit testing.
        // System input/IME window targeting is outside this isolated host's contract.
        awaitCondition { onMain {
            activity.hasWindowFocus() && nodes(activity).any {
                it.config.getOrNull(SemanticsProperties.TestTag) == tag &&
                    it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0
            }
        } }
        val (x, y) = onMain {
            val center = taggedNode(activity, tag).boundsInWindow.center
            val decor = activity.window.decorView
            val windowLocation = IntArray(2).also(decor::getLocationInWindow)
            Pair(center.x - windowLocation[0], center.y - windowLocation[1])
        }
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                onMain {
                    assertTrue("Fixture did not handle pointer action $action on $tag",
                        activity.window.decorView.dispatchTouchEvent(event))
                }
            } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(64)
        send(MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Search session did not reach the expected state" }
            SystemClock.sleep(16)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
