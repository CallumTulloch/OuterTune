package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.utils.STORAGE_ROOT
import com.dd3boh.outertune.ui.utils.encodeFolderPathArgument
import com.dd3boh.outertune.utils.fixFilePath

/**
 * Builds a folder route whose path is safe to use as one navigation segment.
 *
 * The decoder still accepts the previous slash-to-semicolon format, but that format cannot
 * distinguish a path separator from a literal semicolon in a folder name.
 */
internal fun folderRoute(path: String): String =
    "${Screens.Folders.route}/${encodeFolderPathArgument(path)}"

internal fun localSongFolderRoute(isLocal: Boolean, localPath: String?): String? {
    if (!isLocal || localPath == null) return null

    val separatorIndex = localPath.lastIndexOf('/')
    if (separatorIndex <= 0) return null

    val parentPath = fixFilePath(localPath.substring(0, separatorIndex))
    if (!parentPath.startsWith(STORAGE_ROOT)) return null

    return folderRoute(parentPath)
}
