/*
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DropdownItem(
    val title: String,
    val leadingIcon: @Composable (() -> Unit)?,
    val action: () -> Unit,
    val secondaryDropdown: List<DropdownItem>? = null,
    val checked: Boolean? = null,
)

@Composable
fun ActionDropdown(
    actions: List<DropdownItem>,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    extraContent: @Composable (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clickable(
                onClick = {
                    menuExpanded = true
                }
            )
    ) {

        if (extraContent == null) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = null,
                modifier = modifier
                    .padding(4.dp),
            )
        } else {
            extraContent()
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.widthIn(min = 172.dp)
        ) {
            actions.forEach { item ->
                Column {
                    if (item.secondaryDropdown != null) {
                        ActionDropdown(
                            actions = item.secondaryDropdown,
                            onDismiss = { menuExpanded = false; onDismiss() },
                            modifier = Modifier
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Rounded.ChevronLeft, null, Modifier.testTag("dropdown-expand-icon"))
                                Text(
                                    text = item.title,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        DropdownMenuItem(
                            leadingIcon = item.leadingIcon,
                            trailingIcon = item.checked?.let { checked ->
                                { Checkbox(checked = checked, onCheckedChange = null) }
                            },
                            modifier = Modifier.semantics {
                                item.checked?.let {
                                    toggleableState = ToggleableState(it)
                                    role = Role.Checkbox
                                }
                            },
                            text = {
                                Text(
                                    text = item.title,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            },
                            onClick = {
                                item.action()
                                menuExpanded = false
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}
