package com.dd3boh.outertune.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.InputFieldHeight
import com.dd3boh.outertune.ui.component.SearchBarInputField
import com.dd3boh.outertune.ui.component.button.IconButton

@Composable
internal fun FolderSearchBar(
    isSearching: Boolean,
    query: TextFieldValue,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    browseActions: @Composable RowScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissKeyboard = {
        focusManager.clearFocus(true)
        keyboard?.hide()
    }
    LaunchedEffect(isSearching, focusRequested) {
        if (isSearching && focusRequested) {
            focusRequester.requestFocus()
            onFocusHandled()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().height(InputFieldHeight)
            .padding(horizontal = 16.dp).testTag("folder-search-bar"),
    ) {
        IconButton(
            onClick = {
                if (isSearching) { dismissKeyboard(); onClose() } else onOpen()
            },
            modifier = Modifier.testTag("folder-search-toggle"),
        ) {
            Icon(
                if (isSearching) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Search,
                contentDescription = stringResource(if (isSearching) R.string.folder_search_close else R.string.search),
            )
        }
        if (isSearching) {
            SearchBarInputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onSearch(it); dismissKeyboard() },
                active = true,
                onActiveChange = {},
                focusRequester = focusRequester,
                placeholder = { Text(stringResource(R.string.search)) },
                trailingIcon = {
                    if (query.text.isNotEmpty()) IconButton(
                        onClick = { onQueryChange(TextFieldValue()) },
                        modifier = Modifier.testTag("folder-search-clear"),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.folder_search_clear))
                    }
                },
                modifier = Modifier.weight(1f),
            )
        } else browseActions()
    }
}
