package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.GridThumbnailHeight
import com.dd3boh.outertune.constants.LibraryTileSize
import com.dd3boh.outertune.constants.LibraryTileSizeKey
import com.dd3boh.outertune.utils.rememberEnumPreference

@Composable
fun rememberLibraryGridCells(): GridCells {
    val tileSize by rememberEnumPreference(LibraryTileSizeKey, LibraryTileSize.LARGE)
    return remember(tileSize) {
        val thumbnailSize = when (tileSize) {
            LibraryTileSize.LARGE -> GridThumbnailHeight
            LibraryTileSize.SMALL -> 64.dp
        }
        // GridItem keeps 12dp of padding on each side of the thumbnail.
        GridCells.Adaptive(minSize = thumbnailSize + 24.dp)
    }
}
