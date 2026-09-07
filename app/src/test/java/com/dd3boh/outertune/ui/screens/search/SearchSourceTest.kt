package com.dd3boh.outertune.ui.screens.search

import com.dd3boh.outertune.constants.SearchSource
import com.dd3boh.outertune.ui.screens.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourceTest {
    @Test
    fun `new shared search defaults to online`() {
        val state = SearchScopeState(isReady = true).openSearch(available = true)
        assertEquals(SearchSource.ONLINE, state.source)
        assertTrue(state.sessionOpen)
    }

    @Test
    fun `manual selection survives close and reopening without an origin route`() {
        val selected = SearchScopeState(isReady = true).openSearch(true)
            .selectSearchSource(SearchSource.LOCAL, true)!!
        repeat(4) {
            val reopened = selected.closeSearch(true).openSearch(true)
            assertEquals(SearchSource.LOCAL, reopened.source)
            assertEquals(SearchSource.LOCAL, reopened.preferredSource)
        }
    }

    @Test
    fun `tapping an already open search or selected source never toggles it off`() {
        val selected = SearchScopeState(isReady = true).selectSearchSource(SearchSource.LOCAL, true)!!
        assertEquals(selected, selected.openSearch(true))
        assertEquals(selected, selected.selectSearchSource(SearchSource.LOCAL, true))
    }

    @Test
    fun `connection recovery leaves current search local but preserves online preference`() {
        val online = SearchScopeState(isReady = true).openSearch(true)
        val offline = online.withConnection(false)
        assertEquals(SearchSource.LOCAL, offline.source)
        assertEquals(SearchSource.ONLINE, offline.preferredSource)
        assertTrue(offline.offlineFallback)
        val restored = offline.withConnection(true)
        assertEquals(SearchSource.LOCAL, restored.source)
        assertEquals(restored, restored.openSearch(true))
        assertEquals(SearchSource.ONLINE, restored.closeSearch(true).openSearch(true).source)
    }

    @Test
    fun `search opened offline rejects online selection and remembers manual local choice`() {
        val offline = SearchScopeState(isReady = true).openSearch(false)
        assertEquals(SearchSource.LOCAL, offline.source)
        assertNull(offline.selectSearchSource(SearchSource.ONLINE, false))
        val chosen = offline.selectSearchSource(SearchSource.LOCAL, false)!!
        assertFalse(chosen.offlineFallback)
        assertEquals(SearchSource.LOCAL, chosen.withConnection(true).closeSearch(true).openSearch(true).source)
    }

    @Test
    fun `connectivity changes while search is closed do not latch a fallback`() {
        val restored = SearchScopeState(isReady = true).withConnection(false).withConnection(true)
        assertFalse(restored.offlineFallback)
        assertEquals(SearchSource.ONLINE, restored.openSearch(true).source)
    }

    @Test
    fun `old result and shortcut entry routes are both search destinations`() {
        assertTrue(isSearchDestination("search"))
        assertTrue(isSearchDestination("search/{query}"))
        assertFalse(isSearchDestination(Screens.Songs.route))
        assertFalse(isSearchDestination("search_settings"))
    }

}
