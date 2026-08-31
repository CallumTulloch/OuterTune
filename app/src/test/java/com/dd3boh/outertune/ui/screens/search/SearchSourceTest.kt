package com.dd3boh.outertune.ui.screens.search

import com.dd3boh.outertune.constants.SearchSource
import com.dd3boh.outertune.ui.screens.Screens
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSourceTest {
    @Test
    fun `songs tab always starts with local search`() {
        assertEquals(
            SearchSource.LOCAL,
            initialSearchSource(Screens.Songs.route, SearchSource.ONLINE)
        )
    }

    @Test
    fun `home tab always starts with online search`() {
        assertEquals(
            SearchSource.ONLINE,
            initialSearchSource(Screens.Home.route, SearchSource.LOCAL)
        )
    }

    @Test
    fun `other screens keep the selected search source`() {
        assertEquals(
            SearchSource.LOCAL,
            initialSearchSource(Screens.Albums.route, SearchSource.LOCAL)
        )
    }
}
