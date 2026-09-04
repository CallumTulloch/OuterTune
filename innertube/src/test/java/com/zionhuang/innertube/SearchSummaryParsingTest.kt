package com.zionhuang.innertube

import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.MusicCardShelfRenderer
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.Runs
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSummaryParsingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `item section search results are included in all results`() {
        val content = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }.decodeFromString<SectionListRenderer.Content>(
            """
            {
              "itemSectionRenderer": {
                "contents": [
                  {
                    "musicResponsiveListItemRenderer": {
                      "flexColumns": [
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": { "runs": [{ "text": "Test Song" }] }
                          }
                        },
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": {
                              "runs": [
                                { "text": "Song" },
                                { "text": " • " },
                                { "text": "Test Artist" },
                                { "text": " • " },
                                { "text": "3:00" }
                              ]
                            }
                          }
                        }
                      ],
                      "playlistItemData": { "videoId": "video-id" },
                      "thumbnail": {
                        "musicThumbnailRenderer": {
                          "thumbnail": {
                            "thumbnails": [{ "url": "https://example.com/cover.jpg" }]
                          }
                        }
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val page = parseSearchSummary(listOf(content))

        assertEquals(1, page.summaries.size)
        assertEquals("", page.summaries.single().title)
        assertEquals(1, page.summaries.single().items.size)
        assertTrue(page.summaries.single().items.single() is SongItem)
        assertEquals("video-id", page.summaries.single().items.single().id)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `duration metadata in an artist column is not parsed as an artist`() {
        val content = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }.decodeFromString<SectionListRenderer.Content>(
            """
            {
              "itemSectionRenderer": {
                "contents": [
                  {
                    "musicResponsiveListItemRenderer": {
                      "flexColumns": [
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": { "runs": [{ "text": "In Bloom" }] }
                          }
                        },
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": {
                              "runs": [
                                {
                                  "text": "Nirvana",
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "UC-nirvana" }
                                  }
                                },
                                { "text": " • " },
                                { "text": "4:15" }
                              ]
                            }
                          }
                        }
                      ],
                      "fixedColumns": [
                        {
                          "musicResponsiveListItemFixedColumnRenderer": {
                            "text": { "runs": [{ "text": "4:15" }] }
                          }
                        }
                      ],
                      "playlistItemData": { "videoId": "in-bloom" },
                      "thumbnail": {
                        "musicThumbnailRenderer": {
                          "thumbnail": {
                            "thumbnails": [{ "url": "https://example.com/in-bloom.jpg" }]
                          }
                        }
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val renderer = content.itemSectionRenderer
            ?.contents
            ?.single()
            ?.musicResponsiveListItemRenderer!!
        val song = LibraryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem

        assertEquals(listOf("Nirvana"), song.artists.map { it.name })
        assertEquals(255, song.duration)
    }

    @Test
    fun `missing search artist is restored from queue metadata`() {
        val incomplete = SongItem(
            id = "in-bloom",
            title = "In Bloom",
            artists = emptyList(),
            duration = 256,
            thumbnail = "search-thumbnail",
        )
        val queueMetadata = incomplete.copy(
            artists = listOf(Artist(name = "Nirvana", id = "UC-nirvana")),
            duration = 255,
            thumbnail = "queue-thumbnail",
        )

        val repaired = listOf(incomplete).withResolvedArtists(listOf(queueMetadata)).single() as SongItem

        assertEquals(listOf("Nirvana"), repaired.artists.map(Artist::name))
        assertEquals(256, repaired.duration)
        assertEquals("search-thumbnail", repaired.thumbnail)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `target search card exposes typed metadata endpoint hints without localized labels`() {
        val renderer = json.decodeFromString<MusicCardShelfRenderer>(
            requireNotNull(javaClass.getResource("/search-summary-card-menu-metadata.json")).readText()
        )

        val song = SearchSummaryPage.fromMusicCardShelfRenderer(renderer) as SongItem

        assertEquals("TSZhKssbW2g", song.id)
        assertEquals(listOf("翟锦彦、8082Audio"), song.artists.map(Artist::name))
        assertEquals(null, song.album)
        assertEquals("MPREb_NUdafp1DlA5", song.metadataEndpointHints.album?.browseId)
        assertEquals(
            listOf("UChWKQRswWTLRXp98zmgHtdQ"),
            song.metadataEndpointHints.artistCandidates.map { it.browseId }
        )
        assertEquals("MPTCTSZhKssbW2g", song.metadataEndpointHints.credits?.browseId)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `unlinked combined artist run is not split in Japanese or English`() {
        val baseRenderer = json.decodeFromString<MusicCardShelfRenderer>(
            requireNotNull(javaClass.getResource("/search-summary-card-menu-metadata.json")).readText()
        )

        listOf("Primary、Publisher", "Primary & Publisher").forEach { displayName ->
            val renderer = baseRenderer.copy(
                subtitle = Runs(
                    runs = listOf(
                        Run(text = "Song", navigationEndpoint = null),
                        Run(text = " • ", navigationEndpoint = null),
                        Run(text = displayName, navigationEndpoint = null),
                        Run(text = " • ", navigationEndpoint = null),
                        Run(text = "1M views", navigationEndpoint = null),
                    )
                )
            )

            val song = SearchSummaryPage.fromMusicCardShelfRenderer(renderer) as SongItem

            assertEquals(listOf(displayName), song.artists.map(Artist::name))
            assertEquals(listOf(null), song.artists.map(Artist::id))
        }
    }
}
