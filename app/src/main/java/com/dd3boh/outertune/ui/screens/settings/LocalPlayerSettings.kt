/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutomaticScannerKey
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.outertune.constants.EnabledTabsKey
import com.dd3boh.outertune.constants.LastLocalScanKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.screens.settings.fragments.LocalScannerExtraFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.LocalScannerFrag
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.lmScannerCoroutine
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.LocalMediaLifecycle
import com.dd3boh.outertune.utils.scanners.LocalMediaLifecycleState
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner
import com.dd3boh.outertune.utils.scanners.ScannerAbortException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


internal fun restoreFolderTab(enabledTabs: String): String {
    if ('F' in enabledTabs) return enabledTabs

    val libraryIndex = enabledTabs.indexOf('M')
    return if (libraryIndex >= 0) {
        enabledTabs.substring(0, libraryIndex) + 'F' + enabledTabs.substring(libraryIndex)
    } else {
        enabledTabs + 'F'
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val snackbarHostState = LocalSnackbarHostState.current

    val (autoScan, onAutoScanChange) = rememberPreference(AutomaticScannerKey, defaultValue = true)
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)
    val localMediaLifecycleState by LocalMediaLifecycle.state.collectAsState()

    val startSavedMediaImport = {
        activity.lifecycleScope.launch(lmScannerCoroutine) {
            try {
                LocalMediaLifecycle.importSavedMedia(context, database, playerConnection)
            } catch (e: ScannerAbortException) {
                // An OFF transition intentionally cancels an in-flight import.
                if (context.dataStore.data.first()[LocalLibraryEnableKey] != false) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = "${context.getString(R.string.scanner_scan_fail)}: ${e.message}",
                            withDismissAction = true,
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    snackbarHostState.showSnackbar(
                        message = "${context.getString(R.string.scanner_scan_fail)}: ${e.message}",
                        withDismissAction = true,
                        duration = SnackbarDuration.Short,
                    )
                }
                reportException(e)
            }
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            startSavedMediaImport()
        } else {
            activity.lifecycleScope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.scanner_missing_storage_perm),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    var showLmDisableDialog by remember {
        mutableStateOf(false)
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SwitchPreference(
            title = { Text(stringResource(R.string.local_library_enable_title)) },
            description = stringResource(R.string.local_library_enable_delete_description),
            icon = { Icon(Icons.Rounded.SdCard, null) },
            isEnabled = localMediaLifecycleState != LocalMediaLifecycleState.REMOVING,
            checked = localLibEnable,
            onCheckedChange = { enabled ->
                if (localLibEnable) {
                    showLmDisableDialog = true
                } else if (enabled) {
                    activity.lifecycleScope.launch {
                        context.dataStore.edit { settings ->
                            settings[LocalLibraryEnableKey] = true
                            settings[LastLocalScanKey] = 0L
                            settings[EnabledTabsKey] = restoreFolderTab(
                                settings[EnabledTabsKey] ?: DEFAULT_ENABLED_TABS,
                            )
                        }

                        if (context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startSavedMediaImport()
                        } else {
                            mediaPermissionLauncher.launch(MEDIA_PERMISSION_LEVEL)
                        }
                    }
                }
            }
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            // automatic scanner
            SwitchPreference(
                title = { Text(stringResource(R.string.auto_scanner_title)) },
                description = stringResource(R.string.auto_scanner_description),
                icon = { Icon(Icons.Rounded.Autorenew, null) },
                checked = autoScan,
                onCheckedChange = onAutoScanChange
            )
            InfoLabel(
                text = stringResource(R.string.auto_scanner_tooltip),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(localLibEnable) {
            Column {

                PreferenceGroupTitle(
                    title = stringResource(R.string.grp_manual_scanner)
                )
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LocalScannerFrag()
                }
                Spacer(modifier = Modifier.height(16.dp))

                PreferenceGroupTitle(
                    title = stringResource(R.string.grp_extra_scanner_settings)
                )
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LocalScannerExtraFrag()
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showLmDisableDialog) {
        DefaultDialog(
            onDismiss = { showLmDisableDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.disable_lm_delete_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showLmDisableDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showLmDisableDialog = false
                        activity.lifecycleScope.launch {
                            context.dataStore.edit { settings ->
                                settings[LocalLibraryEnableKey] = false
                                settings[LastLocalScanKey] = 0L
                            }

                            try {
                                withContext(lmScannerCoroutine) {
                                    LocalMediaLifecycle.removeImportedMedia(database, playerConnection)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // Keep the switch truthful if the transactional purge failed.
                                context.dataStore.edit { settings ->
                                    settings[LocalLibraryEnableKey] = true
                                }
                                LocalMediaScanner.resumeScannerOperations()
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.local_media_disable_failed),
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Long,
                                )
                                reportException(e)
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.local_player_settings_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
