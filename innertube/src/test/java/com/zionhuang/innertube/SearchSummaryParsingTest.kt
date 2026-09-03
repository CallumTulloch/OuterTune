package com.zionhuang.innertube

import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.LibraryPage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSummaryParsingTest {
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
}
