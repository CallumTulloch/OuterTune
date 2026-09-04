package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.utils.STORAGE_ROOT
import com.dd3boh.outertune.ui.utils.decodeFolderPathArgument
import com.dd3boh.outertune.ui.utils.encodeFolderPathArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNavigationTest {
    @Test
    fun `encoded folder path is one safe segment and round trips`() {
        val path = "/storage/emulated/0/Music; Live/日本語 #1?/%20/"
        val argument = encodeFolderPathArgument(path)

        assertTrue(argument.startsWith("v2_"))
        assertFalse(argument.contains('/'))
        assertFalse(argument.contains(';'))
        assertFalse(argument.contains('#'))
        assertFalse(argument.contains('?'))
        assertEquals(path, decodeFolderPathArgument(argument))
    }

    @Test
    fun `legacy semicolon folder path remains supported`() {
        assertEquals(
            "/storage/emulated/0/Music/",
            decodeFolderPathArgument(";storage;emulated;0;Music;"),
        )
    }

    @Test
    fun `missing or invalid encoded path falls back to storage root`() {
        assertEquals(STORAGE_ROOT, decodeFolderPathArgument(null))
        assertEquals(STORAGE_ROOT, decodeFolderPathArgument("v2_"))
        assertEquals(STORAGE_ROOT, decodeFolderPathArgument("v2_not-hex"))
        assertEquals(
            STORAGE_ROOT,
            decodeFolderPathArgument(encodeFolderPathArgument("/outside/storage/")),
        )
    }

    @Test
    fun `local song route opens its containing folder`() {
        val route = localSongFolderRoute(
            isLocal = true,
            localPath = "/storage/emulated/0/Music/Nirvana/In Bloom.flac",
        )

        val prefix = "${Screens.Folders.route}/"
        assertTrue(route?.startsWith(prefix) == true)
        assertEquals(
            "/storage/emulated/0/Music/Nirvana/",
            decodeFolderPathArgument(route?.removePrefix(prefix)),
        )
    }

    @Test
    fun `online download and unavailable local song do not get folder route`() {
        assertNull(
            localSongFolderRoute(
                isLocal = false,
                localPath = "/storage/emulated/0/Android/data/downloaded-song.m4a",
            )
        )
        assertNull(localSongFolderRoute(isLocal = true, localPath = null))
        assertNull(localSongFolderRoute(isLocal = true, localPath = "content://media/song/1"))
    }
}
