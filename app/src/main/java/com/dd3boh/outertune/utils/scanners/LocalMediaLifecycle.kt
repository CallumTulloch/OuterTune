/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.ExcludedScanPathsKey
import com.dd3boh.outertune.constants.LastLocalScanKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.SCANNER_OWNER_LM
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerImplKey
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.constants.ScannerSensitivityKey
import com.dd3boh.outertune.constants.ScannerStrictExtKey
import com.dd3boh.outertune.constants.ScannerStrictFilePathsKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.daos.retainOnlineQueueSongs
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.playback.QueueBoard
import com.dd3boh.outertune.ui.utils.clearDtCache
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LocalMediaLifecycleState {
    IDLE,
    REMOVING,
    IMPORTING,
}

/**
 * Coordinates the destructive OFF transition and the re-importing ON transition.
 *
 * The lifecycle mutex prevents those transitions from overlapping. Scanner work has its own
 * complete-operation lock in [LocalMediaScanner], which is also used by manual and startup scans.
 */
object LocalMediaLifecycle {
    private val lifecycleMutex = Mutex()
    private val _state = MutableStateFlow(LocalMediaLifecycleState.IDLE)

    val state = _state.asStateFlow()

    suspend fun removeImportedMedia(
        database: MusicDatabase,
        playerConnection: PlayerConnection?,
    ) {
        // This must happen before waiting for a currently importing lifecycle operation.
        LocalMediaScanner.requestScannerShutdown()

        lifecycleMutex.withLock {
            _state.value = LocalMediaLifecycleState.REMOVING
            var queueNeedsReload = false
            var queueBoardToAwait: QueueBoard? = null
            try {
                LocalMediaScanner.cancelScannerAndAwaitIdle()
                val hadLocalSongs = database.hasLocalSongs()
                queueNeedsReload = hadLocalSongs

                if (hadLocalSongs) {
                    withContext(Dispatchers.Main) {
                        queueBoardToAwait = playerConnection?.service?.queueBoard?.value
                        queueBoardToAwait?.shutdown()
                        val player = playerConnection?.player
                        val activeQueueContainsLocalSongs = player != null &&
                            (0 until player.mediaItemCount).any { index ->
                                player.getMediaItemAt(index).metadata?.isLocal == true
                            }
                        if (activeQueueContainsLocalSongs) {
                            player.pause()
                            player.clearMediaItems()
                        }
                    }
                    // A delayed QueueBoard save can insert its in-memory SongEntity objects. Wait
                    // for every such write before deleting local rows.
                    queueBoardToAwait?.awaitShutdown()
                }

                val affectedQueues = if (hadLocalSongs) {
                    database.readQueue()
                        .filter { queue -> queue.queue.any { it.isLocal } }
                        .map { queue -> queue.id to retainOnlineQueueSongs(queue) }
                } else {
                    emptyList()
                }

                database.awaitTransaction {
                    nukeLocalData()
                    affectedQueues.forEach { (queueId, repairedQueue) ->
                        if (repairedQueue == null) {
                            deleteQueue(queueId)
                        } else {
                            saveQueue(repairedQueue)
                        }
                    }
                }
            } finally {
                try {
                    clearDtCache()
                } catch (e: Throwable) {
                    reportException(e)
                }
                try {
                    if (queueNeedsReload) {
                        // Rebuild only from the surviving queue rows. A mixed active queue was
                        // stopped above; online-only playback is left uninterrupted.
                        withContext(Dispatchers.Main) {
                            playerConnection?.service?.initQueue()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Queue reloading is post-commit UI cleanup. It must not make the caller roll
                    // the enable switch back after the local rows were successfully removed.
                    reportException(e)
                } finally {
                    _state.value = LocalMediaLifecycleState.IDLE
                }
            }
        }
    }

    suspend fun importSavedMedia(
        context: Context,
        database: MusicDatabase,
        playerConnection: PlayerConnection?,
    ) {
        lifecycleMutex.withLock {
            _state.value = LocalMediaLifecycleState.IMPORTING
            try {
                val preferences = context.dataStore.data.first()
                if (preferences[LocalLibraryEnableKey] == false) {
                    throw ScannerAbortException("Local media was disabled before the scan started")
                }

                LocalMediaScanner.resumeScannerOperations()

                val scannerImpl = preferences[ScannerImplKey].toEnum(ScannerImpl.TAGLIB)
                val scannerSensitivity =
                    preferences[ScannerSensitivityKey].toEnum(ScannerMatchCriteria.LEVEL_2)
                val scanPaths = preferences[ScanPathsKey].orEmpty()
                val excludedScanPaths = preferences[ExcludedScanPathsKey].orEmpty()
                val strictExtensions = preferences[ScannerStrictExtKey] ?: false
                val strictFilePaths = preferences[ScannerStrictFilePathsKey] ?: false

                withContext(Dispatchers.Main) {
                    playerConnection?.player?.pause()
                }

                try {
                    LocalMediaScanner.withScannerOperation(SCANNER_OWNER_LM) {
                        // Re-check after acquiring the scanner lock. OFF may have been selected while
                        // this operation was waiting behind another scan.
                        val localMediaEnabled = context.dataStore.data.first()[LocalLibraryEnableKey] ?: true
                        if (!localMediaEnabled) {
                            throw ScannerAbortException("Local media was disabled before the scan started")
                        }

                        val scanner = LocalMediaScanner.getScanner(
                            context,
                            scannerImpl,
                            SCANNER_OWNER_LM,
                        )
                        if (scannerImpl == ScannerImpl.MEDIASTORE) {
                            scanner.fullMediaStoreSync(
                                database,
                                uriListFromString(scanPaths),
                                uriListFromString(excludedScanPaths),
                                scannerSensitivity,
                                strictExtensions,
                                strictFilePaths,
                                false,
                            )
                        } else {
                            val uris = scanner.scanLocal(scanPaths, excludedScanPaths)
                            scanner.quickSync(
                                database,
                                uris,
                                scannerSensitivity,
                                strictExtensions,
                                strictFilePaths,
                            )
                        }
                    }
                } finally {
                    try {
                        clearDtCache()
                    } catch (e: Throwable) {
                        reportException(e)
                    }
                }

                context.dataStore.edit { settings ->
                    settings[LastLocalScanKey] = System.currentTimeMillis()
                }
                try {
                    withContext(Dispatchers.Main) {
                        playerConnection?.service?.initQueue()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Import is already committed. Queue reloading must not report the scan as
                    // failed or trigger another destructive transition.
                    reportException(e)
                }
            } finally {
                _state.value = LocalMediaLifecycleState.IDLE
            }
        }
    }
}
