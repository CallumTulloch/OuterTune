package com.dd3boh.outertune.ui.screens.library

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R

/** Only navigation chips use English in Japanese UI; menus keep their localized labels. */
@Composable
internal fun navigationFilterLabel(@StringRes labelId: Int): String {
    // A values-ja override alone also wins when Japanese is a secondary fallback (e.g. fr,ja).
    // Use the active UI language, independently of the content-language preference.
    val displayId = if (LocalConfiguration.current.locales[0]?.language == "ja") {
        when (labelId) {
            R.string.albums -> R.string.navigation_filter_albums
            R.string.artists -> R.string.navigation_filter_artists
            R.string.playlists -> R.string.navigation_filter_playlists
            R.string.filter_liked -> R.string.navigation_filter_liked
            R.string.filter_downloaded -> R.string.navigation_filter_downloaded
            R.string.library -> R.string.navigation_filter_library
            R.string.folders -> R.string.navigation_filter_folders
            else -> labelId
        }
    } else {
        labelId
    }
    return stringResource(displayId)
}
