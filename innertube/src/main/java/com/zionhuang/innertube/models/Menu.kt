package com.zionhuang.innertube.models

import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_TRACK_CREDITS
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import kotlinx.serialization.Serializable

@Serializable
data class Menu(
    val menuRenderer: MenuRenderer,
) {
    @Serializable
    data class MenuRenderer(
        val items: List<Item>?,
        val topLevelButtons: List<TopLevelButton>?,
    ) {
        @Serializable
        data class Item(
            val menuNavigationItemRenderer: MenuNavigationItemRenderer?,
            val menuServiceItemRenderer: MenuServiceItemRenderer?,
            val toggleMenuServiceItemRenderer: ToggleMenuServiceRenderer?,
        ) {
            @Serializable
            data class MenuNavigationItemRenderer(
                val text: Runs,
                val icon: Icon,
                val navigationEndpoint: NavigationEndpoint,
            )

            @Serializable
            data class MenuServiceItemRenderer(
                val text: Runs,
                val icon: Icon,
                val serviceEndpoint: NavigationEndpoint,
            )

            @Serializable
            data class ToggleMenuServiceRenderer(
                val defaultIcon: Icon,
                val defaultServiceEndpoint: DefaultServiceEndpoint,
            )
        }

        @Serializable
        data class TopLevelButton(
            val buttonRenderer: ButtonRenderer?,
        ) {
            @Serializable
            data class ButtonRenderer(
                val icon: Icon,
                val navigationEndpoint: NavigationEndpoint,
            )
        }
    }
}

/** Returns every menu browse endpoint whose typed music page matches [pageType]. */
fun Menu.browseEndpointsForPageType(pageType: String): List<BrowseEndpoint> =
    buildList {
        menuRenderer.items.orEmpty().forEach { item ->
            item.menuNavigationItemRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
                ?.let(::add)
            item.menuServiceItemRenderer
                ?.serviceEndpoint
                ?.browseEndpoint
                ?.let(::add)
        }
        menuRenderer.topLevelButtons.orEmpty().forEach { button ->
            button.buttonRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
                ?.let(::add)
        }
    }.filter { endpoint ->
        endpoint.browseEndpointContextSupportedConfigs
            ?.browseEndpointContextMusicConfig
            ?.pageType == pageType
    }.distinctBy(BrowseEndpoint::browseId)

/**
 * Extracts typed discovery hints without looking at localized menu labels or icons.
 * Artist candidates deliberately remain unassociated with display names.
 */
fun Menu.songMetadataEndpointHints(): SongMetadataEndpointHints = SongMetadataEndpointHints(
    album = browseEndpointsForPageType(MUSIC_PAGE_TYPE_ALBUM).firstOrNull(),
    artistCandidates = listOf(
        MUSIC_PAGE_TYPE_ARTIST,
        MUSIC_PAGE_TYPE_LIBRARY_ARTIST,
        MUSIC_PAGE_TYPE_USER_CHANNEL,
    ).flatMap(::browseEndpointsForPageType).distinctBy(BrowseEndpoint::browseId),
    credits = browseEndpointsForPageType(MUSIC_PAGE_TYPE_TRACK_CREDITS).firstOrNull(),
)
