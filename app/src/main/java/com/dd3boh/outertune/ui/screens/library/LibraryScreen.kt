package com.dd3boh.outertune.ui.screens.library

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.CONTENT_TYPE_LIST
import com.dd3boh.outertune.constants.CONTENT_TYPE_PLAYLIST
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_FILTERS
import com.dd3boh.outertune.constants.EnabledFiltersKey
import com.dd3boh.outertune.constants.GridThumbnailHeight
import com.dd3boh.outertune.constants.LibraryAlbumContentFilterMaskKey
import com.dd3boh.outertune.constants.LibraryArtistContentFilterMaskKey
import com.dd3boh.outertune.constants.LibraryContentFilter
import com.dd3boh.outertune.constants.LibraryContentFilterUnselectedDefaultMigratedKey
import com.dd3boh.outertune.constants.LibraryFilterKey
import com.dd3boh.outertune.constants.LibraryPlaylistContentFilterMaskKey
import com.dd3boh.outertune.constants.LibrarySortDescendingKey
import com.dd3boh.outertune.constants.LibrarySortType
import com.dd3boh.outertune.constants.LibrarySortTypeKey
import com.dd3boh.outertune.constants.LibraryViewType
import com.dd3boh.outertune.constants.LibraryViewTypeKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.ShowLikedAndDownloadedPlaylist
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.ui.component.CHIP_ITEM_TRANSITION_DURATION_MILLIS
import com.dd3boh.outertune.ui.component.ChipsLazyRow
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.LazyVerticalGridScrollbar
import com.dd3boh.outertune.ui.component.LibraryAlbumGridItem
import com.dd3boh.outertune.ui.component.LibraryAlbumListItem
import com.dd3boh.outertune.ui.component.LibraryArtistGridItem
import com.dd3boh.outertune.ui.component.LibraryArtistListItem
import com.dd3boh.outertune.ui.component.LibraryPlaylistGridItem
import com.dd3boh.outertune.ui.component.LibraryPlaylistListItem
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.AutoPlaylistGridItem
import com.dd3boh.outertune.ui.component.items.AutoPlaylistListItem
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.screens.Screens.LibraryFilter
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.LibraryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val MINIMUM_PULL_REFRESH_INDICATOR_MILLIS = 300L

internal data class LibraryChip(
    val category: LibraryFilter,
    val contentFilter: LibraryContentFilter? = null,
)

private val libraryCategoriesWithContentFilter = setOf(
    LibraryFilter.ALBUMS,
    LibraryFilter.ARTISTS,
    LibraryFilter.PLAYLISTS,
)

internal fun libraryChips(
    activeCategory: LibraryFilter,
    enabledCategories: List<LibraryFilter>,
    showContentFilters: Boolean = true,
    includeFolderContent: Boolean = true,
): List<LibraryChip> = if (activeCategory == LibraryFilter.ALL) {
    enabledCategories
        .filterNot {
            it == LibraryFilter.ALL || (!includeFolderContent && it == LibraryFilter.FOLDERS)
        }
        .map(::LibraryChip)
} else {
    buildList {
        add(LibraryChip(activeCategory))
        if (showContentFilters && activeCategory in libraryCategoriesWithContentFilter) {
            add(LibraryChip(activeCategory, LibraryContentFilter.DOWNLOADED))
            add(LibraryChip(activeCategory, LibraryContentFilter.LIBRARY))
            if (includeFolderContent) {
                add(LibraryChip(activeCategory, LibraryContentFilter.FOLDER))
            }
        }
    }
}

internal fun libraryChipUniverse(
    enabledCategories: List<LibraryFilter>,
    includeFolderContent: Boolean = true,
): List<LibraryChip> = buildList {
    enabledCategories
        .filterNot {
            it == LibraryFilter.ALL || (!includeFolderContent && it == LibraryFilter.FOLDERS)
        }
        .forEach { category ->
            add(LibraryChip(category))
            if (category in libraryCategoriesWithContentFilter) {
                add(LibraryChip(category, LibraryContentFilter.DOWNLOADED))
                add(LibraryChip(category, LibraryContentFilter.LIBRARY))
                if (includeFolderContent) {
                    add(LibraryChip(category, LibraryContentFilter.FOLDER))
                }
            }
        }
}

internal fun toggleLibraryContentFilter(mask: Int, filter: LibraryContentFilter): Int =
    mask xor filter.mask

internal fun migrateLibraryContentFilterMask(mask: Int?): Int? =
    if (mask == LibraryContentFilter.allMask) 0 else mask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    var viewType by rememberEnumPreference(LibraryViewTypeKey, LibraryViewType.GRID)
    val enabledFilters by rememberPreference(EnabledFiltersKey, defaultValue = DEFAULT_ENABLED_FILTERS)
    var filter by rememberEnumPreference(LibraryFilterKey, LibraryFilter.ALL)
    var albumContentFilterMask by rememberPreference(
        LibraryAlbumContentFilterMaskKey,
        0,
    )
    var artistContentFilterMask by rememberPreference(
        LibraryArtistContentFilterMaskKey,
        0,
    )
    var playlistContentFilterMask by rememberPreference(
        LibraryPlaylistContentFilterMaskKey,
        0,
    )
    val libraryContentFilterDefaultsMigrated by rememberPreference(
        LibraryContentFilterUnselectedDefaultMigratedKey,
        false,
    )
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)

    LaunchedEffect(localLibEnable, filter) {
        if (!localLibEnable && filter == LibraryFilter.FOLDERS) {
            filter = LibraryFilter.ALL
        }
    }

    val albumSelectedFilterMask = if (libraryContentFilterDefaultsMigrated) {
        albumContentFilterMask
    } else {
        migrateLibraryContentFilterMask(albumContentFilterMask) ?: 0
    }
    val artistSelectedFilterMask = if (libraryContentFilterDefaultsMigrated) {
        artistContentFilterMask
    } else {
        migrateLibraryContentFilterMask(artistContentFilterMask) ?: 0
    }
    val playlistSelectedFilterMask = if (libraryContentFilterDefaultsMigrated) {
        playlistContentFilterMask
    } else {
        migrateLibraryContentFilterMask(playlistContentFilterMask) ?: 0
    }

    val albumContentFilters = LibraryContentFilter.effectiveFromMask(albumSelectedFilterMask)
    val artistContentFilters = LibraryContentFilter.effectiveFromMask(artistSelectedFilterMask)
    val playlistContentFilters = LibraryContentFilter.effectiveFromMask(playlistSelectedFilterMask)

    LaunchedEffect(libraryContentFilterDefaultsMigrated) {
        if (!libraryContentFilterDefaultsMigrated) {
            context.dataStore.edit { preferences ->
                listOf(
                    LibraryAlbumContentFilterMaskKey,
                    LibraryArtistContentFilterMaskKey,
                    LibraryPlaylistContentFilterMaskKey,
                ).forEach { key ->
                    migrateLibraryContentFilterMask(preferences[key])?.let { migratedMask ->
                        preferences[key] = migratedMask
                    }
                }
                preferences[LibraryContentFilterUnselectedDefaultMigratedKey] = true
            }
        }
    }

    val (sortType, onSortTypeChange) = rememberEnumPreference(LibrarySortTypeKey, LibrarySortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(LibrarySortDescendingKey, true)
    val (showLikedAndDownloadedPlaylist) = rememberPreference(ShowLikedAndDownloadedPlaylist, true)

    val unfilteredAllItems by viewModel.allItems.collectAsState()
    val allItems = remember(unfilteredAllItems, localLibEnable) {
        if (localLibEnable) {
            unfilteredAllItems
        } else {
            unfilteredAllItems.filterNot { item ->
                when (item) {
                    is Album -> item.album.isLocal
                    is Artist -> item.artist.isLocal
                    else -> false
                }
            }
        }
    }

    val isSyncingRemotePlaylists by viewModel.isSyncingRemotePlaylists.collectAsState()
    val isSyncingRemoteAlbums by viewModel.isSyncingRemoteAlbums.collectAsState()
    val isSyncingRemoteArtists by viewModel.isSyncingRemoteArtists.collectAsState()
    val isSyncingRemoteSongs by viewModel.isSyncingRemoteSongs.collectAsState()
    val isSyncingRemoteLikedSongs by viewModel.isSyncingRemoteLikedSongs.collectAsState()
    val isManualLibraryRefresh by viewModel.isRefreshingLibrary.collectAsState()
    val isLibraryRefreshRunning = isManualLibraryRefresh ||
            isSyncingRemotePlaylists ||
            isSyncingRemoteAlbums ||
            isSyncingRemoteArtists ||
            isSyncingRemoteSongs ||
            isSyncingRemoteLikedSongs
    var isPullRefreshFeedbackVisible by remember { mutableStateOf(false) }
    val isRefreshingLibrary = isLibraryRefreshRunning || isPullRefreshFeedbackVisible
    val pullRefreshState = rememberPullToRefreshState()

    val likedPlaylist = PlaylistEntity(id = "liked", name = stringResource(id = R.string.liked_songs))
    val downloadedPlaylist = PlaylistEntity(id = "downloaded", name = stringResource(id = R.string.downloaded_songs))

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop = backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    var categoryTransitionTarget by remember { mutableStateOf<LibraryFilter?>(null) }
    val categoryTransitionClock = remember { Animatable(0f) }
    val visibleCategory = categoryTransitionTarget ?: filter

    var contentFiltersVisibleFor by remember { mutableStateOf<LibraryFilter?>(null) }
    LaunchedEffect(filter) {
        contentFiltersVisibleFor = filter.takeIf { it in libraryCategoriesWithContentFilter }
    }

    val chipValues = libraryChipUniverse(
        enabledCategories = Screens.getFilters(enabledFilters),
        includeFolderContent = localLibEnable,
    )
    val chips = chipValues.map { chip ->
        chip to when (chip.contentFilter) {
            LibraryContentFilter.DOWNLOADED -> stringResource(R.string.filter_downloaded)
            LibraryContentFilter.LIBRARY -> stringResource(R.string.library)
            LibraryContentFilter.FOLDER -> stringResource(R.string.folders)
            null -> when (chip.category) {
                LibraryFilter.ALBUMS -> stringResource(R.string.albums)
                LibraryFilter.ARTISTS -> stringResource(R.string.artists)
                LibraryFilter.PLAYLISTS -> stringResource(R.string.playlists)
                LibraryFilter.SONGS -> stringResource(R.string.songs)
                LibraryFilter.FOLDERS -> stringResource(R.string.folders)
                LibraryFilter.ALL -> stringResource(R.string.home)
            }
        }
    }

    val filterContentBody = @Composable {
        var showStoragePerm by remember {
            mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
        }

        Column {
            if (localLibEnable && showStoragePerm
            ) {
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
            Row {
                ChipsLazyRow(
                    chips = chips,
                    currentValue = LibraryChip(visibleCategory),
                    onValueUpdate = { chip ->
                        when (chip.contentFilter) {
                            null -> {
                                when {
                                    categoryTransitionTarget != null -> Unit
                                    filter != LibraryFilter.ALL -> filter = LibraryFilter.ALL
                                    else -> {
                                        val target = chip.category
                                        categoryTransitionTarget = target
                                        filter = target
                                        coroutineScope.launch {
                                            categoryTransitionClock.snapTo(0f)
                                            categoryTransitionClock.animateTo(
                                                targetValue = 1f,
                                                animationSpec = tween(
                                                    durationMillis = CHIP_ITEM_TRANSITION_DURATION_MILLIS,
                                                ),
                                            )
                                            if (categoryTransitionTarget == target) {
                                                categoryTransitionTarget = null
                                            }
                                        }
                                    }
                                }
                            }

                            else -> {
                                val contentFilter = chip.contentFilter
                                when (chip.category) {
                                    LibraryFilter.ALBUMS -> albumContentFilterMask =
                                        toggleLibraryContentFilter(albumSelectedFilterMask, contentFilter)
                                    LibraryFilter.ARTISTS -> artistContentFilterMask =
                                        toggleLibraryContentFilter(artistSelectedFilterMask, contentFilter)
                                    LibraryFilter.PLAYLISTS -> playlistContentFilterMask =
                                        toggleLibraryContentFilter(playlistSelectedFilterMask, contentFilter)
                                    else -> Unit
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    selected = { chip ->
                        when (chip.contentFilter) {
                            null -> chip.category == visibleCategory
                            else -> when (chip.category) {
                                LibraryFilter.ALBUMS ->
                                    albumSelectedFilterMask and chip.contentFilter.mask != 0
                                LibraryFilter.ARTISTS ->
                                    artistSelectedFilterMask and chip.contentFilter.mask != 0
                                LibraryFilter.PLAYLISTS ->
                                    playlistSelectedFilterMask and chip.contentFilter.mask != 0
                                else -> false
                            }
                        }
                    },
                    visible = { chip ->
                        when (chip.contentFilter) {
                            null -> visibleCategory == LibraryFilter.ALL || chip.category == visibleCategory
                            else -> categoryTransitionTarget == null &&
                                    contentFiltersVisibleFor == filter &&
                                    chip.category == filter
                        }
                    },
                    itemKey = { chip ->
                        "${chip.category.name}:${chip.contentFilter?.name ?: "CATEGORY"}"
                    },
                    isLoading = { chip ->
                        val isCategorySyncing = when (chip.category) {
                            LibraryFilter.PLAYLISTS -> isSyncingRemotePlaylists
                            LibraryFilter.ALBUMS -> isSyncingRemoteAlbums
                            LibraryFilter.ARTISTS -> isSyncingRemoteArtists
                            LibraryFilter.SONGS -> isSyncingRemoteSongs || isSyncingRemoteLikedSongs
                            else -> false
                        }
                        if (filter == LibraryFilter.ALL) {
                            chip.contentFilter == null && isCategorySyncing
                        } else {
                            chip.contentFilter == LibraryContentFilter.LIBRARY && isCategorySyncing
                        }
                    },
                    separatorAfterIndex = chipValues.indexOf(LibraryChip(filter)).takeIf {
                        categoryTransitionTarget == null &&
                                filter in libraryCategoriesWithContentFilter
                    },
                )

                if (filter != LibraryFilter.SONGS && filter != LibraryFilter.FOLDERS) {
                    IconButton(
                        onClick = {
                            viewType = viewType.toggle()
                        },
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Icon(
                            imageVector =
                                when (viewType) {
                                    LibraryViewType.LIST -> Icons.AutoMirrored.Rounded.List
                                    LibraryViewType.GRID -> Icons.Rounded.GridView
                                },
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }

    // The chip row is hosted by a different list after a category is selected. Move the same
    // composition between those hosts so its in-flight visibility animations keep their state.
    val currentFilterContentBody = rememberUpdatedState(filterContentBody)
    val filterContent = remember {
        movableContentOf {
            currentFilterContentBody.value()
        }
    }

    val headerContent = @Composable {
        SortHeader(
            sortType = sortType,
            sortDescending = sortDescending,
            onSortTypeChange = onSortTypeChange,
            onSortDescendingChange = onSortDescendingChange,
            sortTypeText = { sortType ->
                when (sortType) {
                    LibrarySortType.CREATE_DATE -> R.string.sort_by_create_date
                    LibrarySortType.NAME -> R.string.sort_by_name
                }
            },
            modifier = Modifier.padding(start = 16.dp)
        )
    }

    if (filter != LibraryFilter.ALL) {
        BackHandler {
            filter = LibraryFilter.ALL
        }
    }

    // scroll to top
    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            when (viewType) {
                LibraryViewType.LIST -> lazyListState.animateScrollToItem(0)
                LibraryViewType.GRID -> lazyGridState.animateScrollToItem(0)
            }
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (filter == LibraryFilter.ALL) {
                    Modifier.pullToRefresh(
                        state = pullRefreshState,
                        isRefreshing = isRefreshingLibrary,
                        onRefresh = {
                            if (!isRefreshingLibrary) {
                                isPullRefreshFeedbackVisible = true
                                viewModel.syncAll(true)
                                coroutineScope.launch {
                                    delay(MINIMUM_PULL_REFRESH_INDICATOR_MILLIS)
                                    isPullRefreshFeedbackVisible = false
                                }
                            }
                        }
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        when (filter) {
            LibraryFilter.ALBUMS ->
                LibraryAlbumsScreen(
                    navController,
                    libraryFilterContent = filterContent,
                    libraryContentFilters = albumContentFilters,
                )

            LibraryFilter.ARTISTS ->
                LibraryArtistsScreen(
                    navController,
                    libraryFilterContent = filterContent,
                    libraryContentFilters = artistContentFilters,
                )

            LibraryFilter.PLAYLISTS ->
                LibraryPlaylistsScreen(
                    navController,
                    libraryFilterContent = filterContent,
                    libraryContentFilters = playlistContentFilters,
                )

            LibraryFilter.SONGS ->
                LibrarySongsScreen(
                    navController,
                    libraryFilterContent = filterContent
                )

            LibraryFilter.FOLDERS ->
                LibraryFoldersScreen(
                    navController,
                    scrollBehavior,
                    filterContent = filterContent
                )

            LibraryFilter.ALL -> {
                ScrollToTopManager(navController, lazyListState)
                when (viewType) {
                    LibraryViewType.LIST -> {
                        LazyColumn(
                            state = lazyListState,
                            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                        ) {
                            item(
                                key = "filter",
                                contentType = CONTENT_TYPE_HEADER
                            ) {
                                filterContent()
                            }

                            item(
                                key = "header",
                                contentType = CONTENT_TYPE_HEADER
                            ) {
                                headerContent()
                            }

                            if (showLikedAndDownloadedPlaylist) {
                                item(
                                    key = likedPlaylist.id,
                                    contentType = { CONTENT_TYPE_PLAYLIST }
                                ) {
                                    AutoPlaylistListItem(
                                        playlist = likedPlaylist,
                                        thumbnail = Icons.Rounded.Favorite,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                navController.navigate("auto_playlist/${likedPlaylist.id}")
                                            }
                                            .animateItem()
                                    )
                                }

                                item(
                                    key = downloadedPlaylist.id,
                                    contentType = { CONTENT_TYPE_PLAYLIST }
                                ) {
                                    AutoPlaylistListItem(
                                        playlist = downloadedPlaylist,
                                        thumbnail = Icons.Rounded.CloudDownload,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                navController.navigate("auto_playlist/${downloadedPlaylist.id}")
                                            }
                                            .animateItem()
                                    )
                                }
                            }

                            allItems.let { allItems ->
                                if (allItems.isEmpty() && !showLikedAndDownloadedPlaylist) {
                                    item {
                                        EmptyPlaceholder(
                                            icon = Icons.AutoMirrored.Rounded.List,
                                            text = stringResource(R.string.library_empty),
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }

                                items(
                                    items = allItems.distinctBy { it.hashCode() },
                                    key = { it.hashCode() },
                                    contentType = { CONTENT_TYPE_LIST }
                                ) { item ->
                                    when (item) {
                                        is Album -> {
                                            LibraryAlbumListItem(
                                                navController = navController,
                                                menuState = menuState,
                                                album = item,
                                                isActive = item.id == mediaMetadata?.album?.id,
                                                isPlaying = isPlaying,
                                                modifier = Modifier.animateItem()
                                            )
                                        }

                                        is Artist -> {
                                            LibraryArtistListItem(
                                                navController = navController,
                                                menuState = menuState,
                                                coroutineScope = coroutineScope,
                                                modifier = Modifier.animateItem(),
                                                artist = item
                                            )
                                        }

                                        is Playlist -> {
                                            LibraryPlaylistListItem(
                                                navController = navController,
                                                menuState = menuState,
                                                coroutineScope = coroutineScope,
                                                playlist = item,
                                                modifier = Modifier.animateItem()
                                            )
                                        }

                                        else -> {}
                                    }
                                }
                            }
                        }
                        LazyColumnScrollbar(
                            state = lazyListState,
                        )
                    }

                    LibraryViewType.GRID -> {
                        LazyVerticalGrid(
                            state = lazyGridState,
                            columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                        ) {
                            item(
                                key = "filter",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = CONTENT_TYPE_HEADER
                            ) {
                                filterContent()
                            }

                            item(
                                key = "header",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = CONTENT_TYPE_HEADER
                            ) {
                                headerContent()
                            }

                            if (showLikedAndDownloadedPlaylist) {
                                item(
                                    key = likedPlaylist.id,
                                    contentType = { CONTENT_TYPE_PLAYLIST }
                                ) {
                                    AutoPlaylistGridItem(
                                        playlist = likedPlaylist,
                                        thumbnail = Icons.Rounded.Favorite,
                                        fillMaxWidth = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                navController.navigate("auto_playlist/${likedPlaylist.id}")
                                            }
                                            .animateItem()
                                    )
                                }

                                item(
                                    key = downloadedPlaylist.id,
                                    contentType = { CONTENT_TYPE_PLAYLIST }
                                ) {
                                    AutoPlaylistGridItem(
                                        playlist = downloadedPlaylist,
                                        thumbnail = Icons.Rounded.CloudDownload,
                                        fillMaxWidth = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                navController.navigate("auto_playlist/${downloadedPlaylist.id}")
                                            }
                                            .animateItem()
                                    )
                                }
                            }

                            allItems.let { allItems ->
                                if (allItems.isEmpty() && !showLikedAndDownloadedPlaylist) {
                                    item {
                                        EmptyPlaceholder(
                                            icon = Icons.AutoMirrored.Rounded.List,
                                            text = stringResource(R.string.library_empty),
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }

                                items(
                                    items = allItems.distinctBy { it.hashCode() },
                                    key = { it.hashCode() },
                                    contentType = { CONTENT_TYPE_LIST }
                                ) { item ->
                                    when (item) {
                                        is Album -> {
                                            LibraryAlbumGridItem(
                                                navController = navController,
                                                menuState = menuState,
                                                coroutineScope = coroutineScope,
                                                album = item,
                                                isActive = item.id == mediaMetadata?.album?.id,
                                                isPlaying = isPlaying,
                                                modifier = Modifier.animateItem()
                                            )
                                        }

                                        is Artist -> {
                                            LibraryArtistGridItem(
                                                navController = navController,
                                                menuState = menuState,
                                                coroutineScope = coroutineScope,
                                                modifier = Modifier.animateItem(),
                                                artist = item
                                            )
                                        }

                                        is Playlist -> {
                                            LibraryPlaylistGridItem(
                                                navController = navController,
                                                menuState = menuState,
                                                coroutineScope = coroutineScope,
                                                playlist = item,
                                                modifier = Modifier.animateItem()
                                            )
                                        }

                                        else -> {}
                                    }
                                }
                            }
                        }
                        LazyVerticalGridScrollbar(
                            state = lazyGridState,
                        )
                    }
                }
            }
        }

        if (filter == LibraryFilter.ALL) {
            Indicator(
                isRefreshing = isRefreshingLibrary,
                state = pullRefreshState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
            )
        }
    }
}
