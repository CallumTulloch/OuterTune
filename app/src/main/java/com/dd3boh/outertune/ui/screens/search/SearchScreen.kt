package com.dd3boh.outertune.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dd3boh.outertune.*
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.*
import com.dd3boh.outertune.db.entities.SearchHistory
import com.dd3boh.outertune.extensions.tabMode
import com.dd3boh.outertune.ui.component.SearchBar
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.OnlineSearchViewModel
import com.dd3boh.outertune.viewmodels.SearchSessionViewModel

val LocalSharedSearchScope = staticCompositionLocalOf<SharedSearchScope> { error("No shared search scope") }
const val SEARCH_ENTRY_REQUEST = "searchEntryRequest"

internal fun isSearchDestination(route: String?): Boolean = route == "search" || route?.startsWith("search/") == true

/** Explicit choices can also remember LOCAL after an automatic offline fallback. */
@Composable
fun SearchScopeControls(state: SearchScopeState, onSelect: (SearchSource) -> Unit, modifier: Modifier = Modifier) {
    val chipColors = FilterChipDefaults.filterChipColors(
        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.source == SearchSource.ONLINE,
                enabled = state.isReady && state.networkAvailable,
                onClick = { onSelect(SearchSource.ONLINE) },
                label = { Text(stringResource(R.string.search_scope_online)) },
                leadingIcon = { Icon(Icons.Rounded.Language, null, Modifier.size(16.dp)) },
                colors = chipColors,
                modifier = Modifier.testTag("search-source-online"),
            )
            FilterChip(
                selected = state.source == SearchSource.LOCAL,
                enabled = state.isReady,
                onClick = { onSelect(SearchSource.LOCAL) },
                label = { Text(stringResource(R.string.search_scope_local)) },
                leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(16.dp)) },
                colors = chipColors,
                modifier = Modifier.testTag("search-source-local"),
            )
        }
        val notice = when {
            !state.networkAvailable -> R.string.search_scope_offline
            state.offlineFallback -> R.string.search_scope_connection_restored
            else -> null
        }
        if (notice != null) Text(
            stringResource(notice), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp).testTag("search-connection-notice"),
        )
    }
}

/** One navigation entry owns the input, local results, online results and their scroll positions. */
@Composable
fun SharedSearchScreen(navController: NavController, entry: NavBackStackEntry) {
    val sessionViewModel: SearchSessionViewModel = hiltViewModel()
    val input = sessionViewModel.inputState
    val scope = LocalSharedSearchScope.current
    val state by scope.state.collectAsState()
    val shownState = state.withConnection(LocalNetworkConnected.current)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val snackbarHostState = LocalSnackbarHostState.current
    val onlineViewModel: OnlineSearchViewModel = hiltViewModel()
    val savedResults = rememberSaveableStateHolder()
    val entryRequest by entry.savedStateHandle.getStateFlow(SEARCH_ENTRY_REQUEST, 0L).collectAsState()
    val lifecycleState by entry.lifecycle.currentStateFlow.collectAsState()

    LaunchedEffect(entry, entryRequest) {
        val currentRequest = entry.savedStateHandle.get<Long>(SEARCH_ENTRY_REQUEST) ?: 0L
        if (input.handledEntryRequest != currentRequest) {
            input.handledEntryRequest = currentRequest
            input.reset()
        }
        scope.open()
    }
    DisposableEffect(entry) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && navController.currentBackStackEntry != entry) {
                input.focusRequested = false
            }
        }
        entry.lifecycle.addObserver(observer)
        onDispose { entry.lifecycle.removeObserver(observer) }
    }

    val submitSearch: (String) -> Unit = submit@{ text ->
            if (text.isBlank() || !scope.state.value.isReady) return@submit
            input.submit(text)
            if (scope.currentSource() == SearchSource.ONLINE) {
                if (youtubeNavigator(context, navController, coroutineScope, playerConnection,
                        snackbarHostState, text.toUri())) return@submit
                if (context.dataStore[PauseSearchHistoryKey] != true) {
                    database.query { insert(SearchHistory(query = text)) }
                }
            }
        }

    SearchSession(
        input = input,
        state = shownState,
        focusReady = lifecycleState == Lifecycle.State.RESUMED,
        onExit = { scope.close(); navController.navigateUp() },
        onSelectSource = { source ->
            val previous = scope.currentSource()
            if (scope.selectSource(source) && previous != source) {
                input.submit(input.query.text)
                if (source == SearchSource.ONLINE) {
                    onlineViewModel.submitQuery(input.query.text, refresh = true)
                }
            }
        },
        onSubmit = submitSearch,
    ) { dismissKeyboard ->
        // Keep per-source list positions even while the other source or suggestions are displayed.
        savedResults.SaveableStateProvider(shownState.source) {
            if (shownState.isReady) when (shownState.source) {
                SearchSource.LOCAL -> LocalSearchScreen(input.query.text, navController, onDismiss = dismissKeyboard)
                SearchSource.ONLINE -> savedResults.SaveableStateProvider(if (input.showingSuggestions) "suggestions" else "results") {
                    if (input.showingSuggestions) {
                    OnlineSearchScreen(
                        query = input.query.text,
                        onQueryChange = { input.edit(it); input.focusRequested = true },
                        navController = navController,
                        onSearch = submitSearch,
                        onDismiss = dismissKeyboard,
                    )
                } else {
                    OnlineSearchResult(navController, viewModel = onlineViewModel,
                        embedded = true, query = input.submittedQuery)
                    }
                }
            }
        }
    }
}

/** Keyboard focus never controls whether this screen or its results exist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSession(
    input: SearchInputState,
    state: SearchScopeState,
    onExit: () -> Unit,
    onSelectSource: (SearchSource) -> Unit,
    onSubmit: (String) -> Unit,
    focusReady: Boolean = true,
    content: @Composable (dismissKeyboard: () -> Unit) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val dismissKeyboard: () -> Unit = {
        input.focusRequested = false
        focusManager.clearFocus(true)
        keyboard?.hide()
    }
    val latestDismiss by rememberUpdatedState(dismissKeyboard)
    LaunchedEffect(input.focusRequested, windowFocused, focusReady) {
        if (input.focusRequested && windowFocused && focusReady) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }
    // The IME consumes the first system Back. Once it is closed, Back exits this entry.
    BackHandler { dismissKeyboard(); onExit() }
    val insets = LocalPlayerAwareWindowInsets.current
    SearchBar(
        query = input.query, onQueryChange = input::edit,
        onSearch = { onSubmit(it); dismissKeyboard() },
        active = true, handleBack = false,
        onActiveChange = { if (it) { input.focusRequested = true; input.showingSuggestions = true } },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        focusRequester = focusRequester,
        placeholder = { Text(stringResource(if (state.source == SearchSource.LOCAL)
            R.string.search_library else R.string.search_yt_music)) },
        leadingIcon = {
            IconButton(onClick = { dismissKeyboard(); onExit() }, modifier = Modifier.testTag("search-back")) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        trailingIcon = {
            if (input.query.text.isNotEmpty()) IconButton(onClick = { input.edit(TextFieldValue()) },
                modifier = Modifier.testTag("search-clear")) {
                Icon(Icons.Rounded.Close, contentDescription = null)
            }
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) {
        Column(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                latestDismiss()
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
            }
        }) {
            SearchScopeControls(state, onSelect = { dismissKeyboard(); onSelectSource(it) })
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalPlayerAwareWindowInsets provides
                    insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) {
                    content(dismissKeyboard)
                }
            }
        }
    }
}

/** Root-screen entry button. Search content itself lives in the navigation graph. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBarContainer(navController: NavController, scrollBehavior: TopAppBarScrollBehavior) {
    val context = LocalContext.current
    val scope = LocalSharedSearchScope.current
    val enabledTabs by rememberPreference(EnabledTabsKey, DEFAULT_ENABLED_TABS)
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, true)
    val navigationItems = remember(enabledTabs, localLibEnable) { Screens.getScreens(enabledTabs, localLibEnable) }
    val entry by navController.currentBackStackEntryAsState()
    val visible = navigationItems.fastAny { it.route == entry?.destination?.route }
    LaunchedEffect(visible) { if (visible) scope.close() }
    val openSearch: () -> Unit = {
        scope.close()
        navController.navigate("search") { launchSingleTop = true }
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        SearchBar(
            query = TextFieldValue(), onQueryChange = {}, onSearch = {}, active = false,
            onActiveChange = { if (it) openSearch() }, scrollBehavior = scrollBehavior,
            placeholder = { Text(stringResource(R.string.search)) },
            leadingIcon = {
                IconButton(onClick = openSearch) { Icon(Icons.Rounded.Search, contentDescription = null) }
            },
            trailingIcon = {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable { navController.navigate("settings") },
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Settings, contentDescription = null) }
            },
            windowInsets = if (context.tabMode()) WindowInsets() else
                WindowInsets.safeDrawing.union(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Start)),
        ) {}
    }
}
