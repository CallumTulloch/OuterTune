package com.dd3boh.outertune.ui.screens.library

import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.CONTENT_TYPE_FOLDER
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.CONTENT_TYPE_SONG
import com.dd3boh.outertune.constants.FlatSubfoldersKey
import com.dd3boh.outertune.constants.FolderSongSortDescendingKey
import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.constants.FolderSongSortTypeKey
import com.dd3boh.outertune.constants.LastLocalScanKey
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.models.DirectoryTree
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.ui.component.FloatingFooter
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SelectHeader
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.IconTextButton
import com.dd3boh.outertune.ui.component.button.ResizableIconButton
import com.dd3boh.outertune.ui.component.items.SongFolderItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.component.shimmer.ListItemPlaceHolder
import com.dd3boh.outertune.ui.component.shimmer.ShimmerHost
import com.dd3boh.outertune.ui.menu.FolderMenu
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.ui.utils.STORAGE_ROOT
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.ui.utils.canNavigateUp
import com.dd3boh.outertune.utils.fixFilePath
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.ArtistDisplayProjection
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.viewmodels.LibraryFoldersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LibraryFoldersViewModel = hiltViewModel(),
    isRoot: Boolean = false,
    libraryFilterContent: @Composable (() -> Unit)? = null
) {
    Log.v("FolderScreen", "F_RC-1")
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val snackbarHostState = LocalSnackbarHostState.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val (flatSubfolders, onFlatSubfoldersChange) = rememberPreference(FlatSubfoldersKey, defaultValue = true)
    val lastLocalScan by rememberPreference(LastLocalScanKey, 0L)
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)

    val (sortType, onSortTypeChange) = rememberEnumPreference(FolderSongSortTypeKey, FolderSongSortType.NAME)
    val (sortDescending, onSortDescendingChange) = rememberPreference(FolderSongSortDescendingKey, true)
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val lazyListState = rememberLazyListState()


    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val currDir: DirectoryTree by viewModel.localSongDirectoryTree.collectAsState()
    val subDirSongCount by viewModel.localSongDtSongCount.collectAsState()

    LaunchedEffect(lastLocalScan) {
        if (viewModel.uiInit && !currDir.isSkeleton && viewModel.lastLocalScan != lastLocalScan) {
            viewModel.lastLocalScan = lastLocalScan
            if (navController.canNavigateUp) {
                navController.backToMain()
            } else {
                coroutineScope.launch(Dispatchers.IO) {
                    viewModel.getLocalSongs()
                    viewModel.getSongCount()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.lastLocalScan == 0L) {
            viewModel.lastLocalScan = lastLocalScan
        }
        if (!currDir.isSkeleton) {
            viewModel.uiInit = true
        }

        if (!viewModel.uiInit) {
            coroutineScope.launch(Dispatchers.IO) {
                viewModel.getLocalSongs()
                viewModel.getSongCount()
                viewModel.uiInit = true
            }
        }
    }

    // search
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchFocusRequested by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val searchResult by viewModel.searchResult.collectAsState()
    LaunchedEffect(isSearching) {
        viewModel.searchInDir(if (isSearching) query.text else "")
    }
    val candidates = if (isSearching) {
        searchResult.songs.takeIf { searchResult.query == query.text } ?: emptyList()
    } else currDir.files
    val artistDisplayRevision by ArtistDisplayProjection.updates.collectAsState()
    val metadataNamesRevision by MetadataNames.updates.collectAsState()
    val visibleSongs = remember(candidates, sortType, sortDescending, artistDisplayRevision, metadataNamesRevision) {
        sortedFolderSongs(candidates, sortType, sortDescending)
    }
    val songCount = if (isSearching) visibleSongs.size else subDirSongCount

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
    val closeSearch = {
        isSearching = false
        searchFocusRequested = false
        query = TextFieldValue()
        viewModel.searchInDir("")
        onExitSelectionMode()
        focusManager.clearFocus(true)
        keyboard?.hide()
        Unit
    }
    LaunchedEffect(visibleSongs) {
        selection.retainAll(visibleSongs.map { it.id }.toSet())
    }

    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    } else if (isSearching) {
        BackHandler(onBack = closeSearch)
    }

    val visibleFolders = remember(currDir, flatSubfolders, sortDescending) {
        val folders = if (flatSubfolders) currDir.getFlattenedSubdirs() else currDir.subdirs
        folders.sortedBy { it.currentDir.lowercase() }.let { if (sortDescending) it.asReversed() else it }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        ScrollToTopManager(navController, lazyListState)
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.padding(bottom = if (inSelectMode) 64.dp else 0.dp)
        ) {
            item(
                key = "header",
                contentType = CONTENT_TYPE_HEADER
            ) {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.background)
                ) {
                    Column {
                        if (libraryFilterContent == null) {
                            var showStoragePerm by remember {
                                mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
                            }
                            if (localLibEnable && showStoragePerm) {
                                TextButton(
                                    onClick = {
                                        // allow user to hide error when clicked. This also makes the code a lot nicer too.
                                        showStoragePerm = false
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
                        } else {
                            libraryFilterContent()
                        }

                        FolderSearchBar(
                            isSearching = isSearching,
                            query = query,
                            onOpen = {
                                onExitSelectionMode()
                                isSearching = true
                                searchFocusRequested = true
                            },
                            focusRequested = searchFocusRequested,
                            onFocusHandled = { searchFocusRequested = false },
                            onClose = closeSearch,
                            onQueryChange = {
                                query = it
                                viewModel.searchInDir(it.text)
                            },
                            onSearch = { viewModel.searchInDir(it, immediate = true) },
                        ) {
                            IconTextButton(R.string.scanner_local_title, Icons.Rounded.SdCard) {
                                navController.navigate("settings/local")
                            }
                            ResizableIconButton(
                                icon = if (flatSubfolders) Icons.AutoMirrored.Rounded.List else Icons.Rounded.AccountTree,
                                onClick = { onFlatSubfoldersChange(!flatSubfolders) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp)
                    ) {
                        SortHeader(
                            sortType = sortType,
                            sortDescending = sortDescending,
                            onSortTypeChange = onSortTypeChange,
                            onSortDescendingChange = onSortDescendingChange,
                            sortTypeText = { sortType ->
                                when (sortType) {
                                    FolderSongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                    FolderSongSortType.MODIFIED_DATE -> R.string.sort_by_date_modified
                                    FolderSongSortType.RELEASE_DATE -> R.string.sort_by_date_released
                                    FolderSongSortType.NAME -> R.string.sort_by_name
                                    FolderSongSortType.ARTIST -> R.string.sort_by_artist
                                    FolderSongSortType.PLAY_COUNT -> R.string.sort_by_play_count
                                    FolderSongSortType.TRACK_NUMBER -> R.string.sort_by_track_number
                                }
                            }
                        )

                        Spacer(Modifier.weight(1f))

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pluralStringResource(R.plurals.n_song, songCount, songCount),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    menuState.show {
                                        FolderMenu(
                                            folder = currDir,
                                            coroutineScope = coroutineScope,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.Companion.ContextClick)
                                }
                            ) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = null
                                )
                            }
                        }
                    }
                }
            }
            if (!isSearching) {
                if (fixFilePath(currDir.getFullPath()) != STORAGE_ROOT)
                    item(
                        key = "previous",
                        contentType = CONTENT_TYPE_FOLDER
                    ) {
                        SongFolderItem(
                            folderTitle = "..",
                            subtitle = "Previous folder",
                            modifier = Modifier
                                .clickable {
                                    if (currDir.culmSongs.value > 0) {
                                        navController.navigateUp()
                                    }
                                }
                        )
                    }


                // all subdirectories listed here
                itemsIndexed(
                    items = visibleFolders,
                    key = { _, item -> item.uid },
                    contentType = { _, _ -> CONTENT_TYPE_FOLDER }
                ) { index, folder ->
                    if (!flatSubfolders || folder.getFullSquashedDir() != fixFilePath(currDir.getFullPath())) // rm dupe dir hax
                        SongFolderItem(
                            folder = folder,
                            folderTitle = if (folder.files.isEmpty()) folder.getSquashedDir() else null,
                            subtitle = null,
                            modifier = Modifier
                                .combinedClickable {
                                    navController.navigate(folderRoute(folder.getFullSquashedDir()))
                                }
                                .animateItem(),
                            menuState = menuState,
                            navController = navController
                        )
                }

                // separator
                if (currDir.subdirs.isNotEmpty() && visibleSongs.isNotEmpty()) {
                    item(
                        key = "folder_songs_divider",
                    ) {
                        HorizontalDivider(
                            thickness = DividerDefaults.Thickness,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            }

            if (currDir.isSkeleton) {
                item {
                    ShimmerHost {
                        repeat(8) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            }
            if (currDir.isSkeleton) return@LazyColumn

            // all songs get listed here
            val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
            itemsIndexed(
                items = visibleSongs,
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
                        folderSongQueue(
                            visibleSongs, song.id,
                            if (isSearching) "${context.getString(R.string.queue_searched_songs_ot)} ${query.text}"
                            else currDir.currentDir.substringAfterLast('/'),
                        )?.let { playerConnection.playQueue(it) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("folder-song-${song.id}")
                        .animateItem()
                )
            }
        }
        LazyColumnScrollbar(
            state = lazyListState,
        )

        if (!isRoot) {
            TopAppBar(title = {
                Column {
                    val title = currDir.currentDir.substringAfterLast('/')
                    val subtitle = currDir.getFullPath().substringBeforeLast('/')
                    Text(
                        text = if (currDir.currentDir == "storage") {
                            stringResource(R.string.local_player_settings_title)
                        } else {
                            title
                        },
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )

                    if (!subtitle.isBlank()) {
                        Text(
                            text = subtitle,
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }, navigationIcon = {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            closeSearch()
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!isSearching) {
                            navController.backToMain()
                        }
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null
                    )
                }
            }, windowInsets = TopBarInsets)
        }

        FloatingFooter(inSelectMode) {
            SelectHeader(
                navController = navController,
                selectedItems = selection.mapNotNull { songId ->
                    visibleSongs.find { it.id == songId }
                }.map { it.toMediaMetadata() },
                totalItemCount = visibleSongs.size,
                onSelectAll = {
                    selection.clear()
                    selection.addAll(visibleSongs.map { it.id })
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
