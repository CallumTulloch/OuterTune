package com.dd3boh.outertune.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.menu.ActionDropdown
import com.dd3boh.outertune.ui.menu.DropdownItem

@Composable
internal fun LibraryLikedFilterMenu(likedOnly: Boolean, onLikedOnlyChange: (Boolean) -> Unit) {
    ActionDropdown(
        modifier = Modifier.testTag("library-liked-menu"),
        actions = listOf(
            DropdownItem(
                title = stringResource(R.string.library_filter),
                leadingIcon = null,
                action = {},
                secondaryDropdown = listOf(
                    DropdownItem(
                        title = stringResource(R.string.filter_liked_only),
                        leadingIcon = null,
                        action = { onLikedOnlyChange(!likedOnly) },
                        checked = likedOnly,
                    ),
                ),
            ),
        ),
    )
}
