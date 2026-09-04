package com.dd3boh.outertune.ui.screens.library

import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachReversed
import androidx.datastore.preferences.core.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.CONTENT_TYPE_SONG
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.SongContentFilter
import com.dd3boh.outertune.constants.SongContentFilterMaskKey
import com.dd3boh.outertune.constants.SongFilter
import com.dd3boh.outertune.constants.SongFilterKey
import com.dd3boh.outertune.constants.SongLikedFilterKey
import com.dd3boh.outertune.constants.SongSortDescendingKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.constants.SongSortTypeKey
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.ChipsRow
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.FloatingFooter
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SelectHeader
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.menu.ActionDropdown
import com.dd3boh.outertune.ui.menu.DropdownItem
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.LibrarySongsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class SongFilterSelection(
    val contentFilterMask: Int,
    val likedOnly: Boolean,
)

internal fun SongFilter.toSongContentFilterOrNull(): SongContentFilter? = when (this) {
    SongFilter.LIBRARY -> SongContentFilter.LIBRARY
    SongFilter.DOWNLOADED -> SongContentFilter.DOWNLOADED
    SongFilter.FOLDER -> SongContentFilter.FOLDER
    SongFilter.LIKED, SongFilter.ALL -> null
}

internal fun nextSongFilterSelection(
    currentContentFilterMask: Int,
    likedOnly: Boolean,
    selectedFilter: SongFilter,
): SongFilterSelection = when (selectedFilter) {
    SongFilter.LIKED -> SongFilterSelection(
        contentFilterMask = currentContentFilterMask,
        likedOnly = !likedOnly,
    )

    SongFilter.ALL -> SongFilterSelection(
        contentFilterMask = 0,
        likedOnly = likedOnly,
    )

    else -> SongFilterSelection(
        contentFilterMask = currentContentFilterMask xor
                requireNotNull(selectedFilter.toSongContentFilterOrNull()).mask,
        likedOnly = likedOnly,
    )
}

internal fun migrateLegacySongFilterSelection(
    legacyFilter: SongFilter?,
    likedOnly: Boolean,
): SongFilterSelection = SongFilterSelection(
    contentFilterMask = legacyFilter?.toSongContentFilterOrNull()?.mask ?: 0,
    likedOnly = likedOnly || legacyFilter == SongFilter.LIKED,
)

internal fun songMatchesFilters(
    song: SongEntity,
    contentFilters: Set<SongContentFilter>,
    likedOnly: Boolean,
): Boolean {
    if (likedOnly && !song.liked) return false
    if (contentFilters.isEmpty()) return true

    return contentFilters.any { contentFilter ->
        when (contentFilter) {
            SongContentFilter.LIBRARY -> !song.isLocal && song.inLibrary != null
            SongContentFilter.DOWNLOADED -> !song.isLocal && song.dateDownload != null
            SongContentFilter.FOLDER -> song.isLocal && song.inLibrary != null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibrarySongsScreen(
    navController: NavController,
    viewModel: LibrarySongsViewModel = hiltViewModel(),
    libraryFilterContent: @Composable (() -> Unit)? = null
) {
    Log.v("LibrarySongsScreen", "S_RC-1")
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val snackbarHostState = LocalSnackbarHostState.current

    val initialFilterSelection = remember(context) {
        val storedContentMask = context.dataStore[SongContentFilterMaskKey]
        val storedLikedOnly = context.dataStore[SongLikedFilterKey]
        if (storedContentMask != null) {
            SongFilterSelection(
                contentFilterMask = storedContentMask,
                likedOnly = storedLikedOnly ?: false,
            )
        } else {
            val legacySourceFilter = context.dataStore[SongFilterKey]?.let { storedFilter ->
                SongFilter.entries.firstOrNull { it.name == storedFilter }
            }
            migrateLegacySongFilterSelection(
                legacyFilter = legacySourceFilter,
                likedOnly = storedLikedOnly ?: false,
            )
        }
    }
    var contentFilterMask by rememberPreference(
        SongContentFilterMaskKey,
        initialFilterSelection.contentFilterMask,
    )
    var likedOnly by rememberPreference(
        SongLikedFilterKey,
        initialFilterSelection.likedOnly,
    )
    val selectedContentFilters = SongContentFilter.fromMask(contentFilterMask)
    val appliedContentFilters = if (libraryFilterContent == null) {
        selectedContentFilters
    } else {
        setOf(SongContentFilter.LIBRARY)
    }
    val likedFilterApplied = libraryFilterContent == null && likedOnly
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)
    val (sortType, onSortTypeChange) = rememberEnumPreference(SongSortTypeKey, SongSortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val unfilteredSongs by viewModel.allSongs.collectAsState()
    val songs = remember(
        unfilteredSongs,
        appliedContentFilters,
        likedFilterApplied,
        sortType,
        sortDescending,
    ) {
        unfilteredSongs
            ?.filter { song ->
                songMatchesFilters(
                    song = song.song,
                    contentFilters = appliedContentFilters,
                    likedOnly = likedFilterApplied,
                )
            }
            ?.let { filteredSongs ->
                if (!likedFilterApplied || sortType != SongSortType.CREATE_DATE) {
                    filteredSongs
                } else if (sortDescending) {
                    filteredSongs.sortedByDescending { it.song.likedDate }
                } else {
                    filteredSongs.sortedBy { it.song.likedDate }
                }
            }
    }
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val isSyncingRemoteLikedSongs by viewModel.isSyncingRemoteLikedSongs.collectAsState()
    val isSyncingRemoteSongs by viewModel.isSyncingRemoteSongs.collectAsState()
    val isManualLibraryRefresh by viewModel.isRefreshingLibrary.collectAsState()
    val isLibraryRefreshRunning =
        isManualLibraryRefresh || isSyncingRemoteLikedSongs || isSyncingRemoteSongs
    var isPullRefreshFeedbackVisible by remember { mutableStateOf(false) }
    val isRefreshingLibrary = isLibraryRefreshRunning || isPullRefreshFeedbackVisible
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val lazyListState = rememberLazyListState()

    // multiselect
    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }
    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    val syncSelection = {
        selectedContent: Set<SongContentFilter>,
        selectedLikedOnly: Boolean,
        bypassCd: Boolean ->
        val syncLibrary = selectedContent.isEmpty() || SongContentFilter.LIBRARY in selectedContent
        val syncLiked = selectedContent.isEmpty() || selectedLikedOnly
        when {
            syncLibrary && syncLiked -> viewModel.syncAllSongs(bypassCd)
            syncLibrary -> viewModel.syncLibrarySongs(bypassCd)
            syncLiked -> viewModel.syncLikedSongs(bypassCd)
            SongContentFilter.DOWNLOADED in selectedContent && bypassCd -> viewModel.refreshDownloads()
            else -> Unit
        }
    }

    LaunchedEffect(songs) {
        selection.fastForEachReversed { songId ->
            if (songs?.find { it.id == songId } == null) {
                selection.remove(songId)
            }
        }
    }

    LaunchedEffect(Unit) {
        context.dataStore.edit { preferences ->
            if (preferences[SongContentFilterMaskKey] == null) {
                val legacySourceFilter = preferences[SongFilterKey]?.let { storedFilter ->
                    SongFilter.entries.firstOrNull { it.name == storedFilter }
                }
                val migratedSelection = migrateLegacySongFilterSelection(
                    legacyFilter = legacySourceFilter,
                    likedOnly = preferences[SongLikedFilterKey] ?: false,
                )
                preferences[SongContentFilterMaskKey] = migratedSelection.contentFilterMask
                if (migratedSelection.likedOnly) {
                    preferences[SongLikedFilterKey] = true
                }
            }
        }
        syncSelection(appliedContentFilters, likedFilterApplied, false)
    }

    val onFilterSelected = { selectedFilter: SongFilter ->
        val updatedSelection = nextSongFilterSelection(
            currentContentFilterMask = contentFilterMask,
            likedOnly = likedOnly,
            selectedFilter = selectedFilter,
        )
        contentFilterMask = updatedSelection.contentFilterMask
        likedOnly = updatedSelection.likedOnly

        if (selectedFilter != SongFilter.LIKED || updatedSelection.likedOnly) {
            syncSelection(
                SongContentFilter.fromMask(updatedSelection.contentFilterMask),
                updatedSelection.likedOnly,
                false,
            )
        }
    }

    val filterContent = @Composable {
        ChipsRow(
            chips = listOf(
                SongFilter.LIKED to stringResource(R.string.filter_liked),
                SongFilter.LIBRARY to stringResource(R.string.library),
                SongFilter.DOWNLOADED to stringResource(R.string.filter_downloaded),
                SongFilter.FOLDER to stringResource(R.string.folders),
            ),
            currentValue = SongFilter.ALL,
            onValueUpdate = onFilterSelected,
            selected = { chipFilter ->
                if (chipFilter == SongFilter.LIKED) {
                    likedOnly
                } else {
                    chipFilter.toSongContentFilterOrNull()?.let { contentFilter ->
                        contentFilterMask and contentFilter.mask != 0
                    } ?: false
                }
            },
            separatorAfterIndex = 0,
            isLoading = { filter ->
                (filter == SongFilter.LIKED && likedOnly && isSyncingRemoteLikedSongs) ||
                        (filter == SongFilter.LIBRARY && isSyncingRemoteSongs)
            }
        )
    }

    val headerContent = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            SortHeader(
                sortType = sortType,
                sortDescending = sortDescending,
                onSortTypeChange = onSortTypeChange,
                onSortDescendingChange = onSortDescendingChange,
                sortTypeText = { sortType ->
                    when (sortType) {
                        SongSortType.CREATE_DATE ->
                            if (likedFilterApplied) {
                                R.string.sort_by_like_date
                            } else {
                                R.string.sort_by_create_date
                            }
                        SongSortType.MODIFIED_DATE -> R.string.sort_by_date_modified
                        SongSortType.RELEASE_DATE -> R.string.sort_by_date_released
                        SongSortType.NAME -> R.string.sort_by_name
                        SongSortType.ARTIST -> R.string.sort_by_artist
                        SongSortType.PLAY_COUNT -> R.string.sort_by_play_count
                    }
                }
            )

            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                songs?.let { songs ->
                    Text(
                        text = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(4.dp))
                    ActionDropdown(
                        actions = listOf(
                            DropdownItem(
                                title = stringResource(R.string.library_filter),
                                leadingIcon = { Icon(Icons.Rounded.FilterAlt, null) },
                                action = {},
                                secondaryDropdown =
                                    listOfNotNull(
                                        if (libraryFilterContent == null) {
                                            DropdownItem(
                                                title = stringResource(R.string.filter_liked),
                                                leadingIcon = null,
                                                action = { onFilterSelected(SongFilter.LIKED) }
                                            )
                                        } else {
                                            null
                                        },
                                        DropdownItem(
                                            title = stringResource(R.string.library),
                                            leadingIcon = null,
                                            action = { onFilterSelected(SongFilter.LIBRARY) }
                                        ),
                                        DropdownItem(
                                            title = stringResource(R.string.filter_downloaded),
                                            leadingIcon = null,
                                            action = { onFilterSelected(SongFilter.DOWNLOADED) }
                                        ),
                                        DropdownItem(
                                            title = stringResource(R.string.folders),
                                            leadingIcon = null,
                                            action = { onFilterSelected(SongFilter.FOLDER) }
                                        ),
                                    )
                            ),
                            DropdownItem(
                                title = stringResource(R.string.queue_all_songs),
                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                                action = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = context.getString(R.string.queue_all_songs),
                                            items = songs.map { it.toMediaMetadata() },
                                            startShuffled = false,
                                        )
                                    )
                                }
                            ),
                            DropdownItem(
                                title = stringResource(R.string.shuffle),
                                leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
                                action = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = context.getString(R.string.queue_all_songs),
                                            items = songs.map { it.toMediaMetadata() },
                                            startShuffled = true,
                                        )
                                    )
                                }
                            ),
                        ),
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                state = pullRefreshState,
                isRefreshing = isRefreshingLibrary,
                onRefresh = {
                    if (!isRefreshingLibrary) {
                        // Keep user-triggered feedback visible even when the refresh finishes
                        // before Compose can render the coordinator's refreshing state.
                        isPullRefreshFeedbackVisible = true
                        syncSelection(appliedContentFilters, likedFilterApplied, true)
                        coroutineScope.launch {
                            delay(MINIMUM_PULL_REFRESH_INDICATOR_MILLIS)
                            isPullRefreshFeedbackVisible = false
                        }
                    }
                }
            ),
    ) {
        ScrollToTopManager(navController, lazyListState)
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.padding(bottom = if (inSelectMode) 64.dp else 0.dp)
        ) {
            item(
                key = "filter",
                contentType = CONTENT_TYPE_HEADER
            ) {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.background)
                ) {
                    var showStoragePerm by remember {
                        mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
                    }
                    if (localLibEnable && showStoragePerm) {
                        TextButton(
                            onClick = {
                                showStoragePerm =
                                    false // allow user to hide error when clicked. This also makes the code a lot nicer too...
                                (context as MainActivity).permissionLauncher.launch(MEDIA_PERMISSION_LEVEL)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.error)
                        ) {
                            Text(
                                text = stringResource(R.string.missing_media_permission_warning),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    libraryFilterContent?.let { it() } ?: filterContent()
                }
            }

            item(
                key = "header",
                contentType = CONTENT_TYPE_HEADER
            ) {
                headerContent()
            }

            songs?.let { songs ->
                if (songs.isEmpty()) {
                    item {
                        EmptyPlaceholder(
                            icon = Icons.Rounded.MusicNote,
                            text = stringResource(R.string.library_song_empty),
                            modifier = Modifier.animateItem()
                        )
                    }
                }
                val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
                itemsIndexed(
                    items = songs,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> CONTENT_TYPE_SONG }
                ) { index, song ->
                    SongListItem(
                        song = song,
                        navController = navController,
                        snackbarHostState = snackbarHostState,

                        isActive = song.song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        inSelectMode = inSelectMode,
                        isSelected = selection.contains(song.id),
                        onSelectedChange = {
                            inSelectMode = true
                            if (it) {
                                selection.add(song.id)
                            } else {
                                selection.remove(song.id)
                            }
                        },
                        swipeEnabled = swipeEnabled,

                        thumbnailSize = thumbnailSize,
                        onPlay = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = context.getString(R.string.queue_all_songs),
                                    items = songs.map { it.toMediaMetadata() },
                                    startIndex = index
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    )
                }
            }
        }
        LazyColumnScrollbar(
            state = lazyListState,
        )

        Indicator(
            isRefreshing = isRefreshingLibrary,
            state = pullRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
        )
        FloatingFooter(visible = inSelectMode && songs != null) {
            val s: List<Song> = (songs as Iterable<Song>).toList()
            SelectHeader(
                navController = navController,
                selectedItems = selection.mapNotNull { songId ->
                    s.find { it.id == songId }
                }.map { it.toMediaMetadata() },
                totalItemCount = s.size,
                onSelectAll = {
                    selection.clear()
                    selection.addAll(s.map { it.id })
                },
                onDeselectAll = { selection.clear() },
                menuState = menuState,
                onDismiss = onExitSelectionMode
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }
}
