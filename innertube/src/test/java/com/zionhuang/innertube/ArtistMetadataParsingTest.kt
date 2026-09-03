package com.zionhuang.innertube

import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.NavigationEndpoint
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.artistElements
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.HomePage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistMetadataParsingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `artist page list keeps every linked artist`() {
        val content = json.decodeFromString<SectionListRenderer.Content>(
            """
            {
              "musicShelfRenderer": {
                "title": { "runs": [{ "text": "Songs" }] },
                "contents": [
                  {
                    "musicResponsiveListItemRenderer": {
                      "flexColumns": [
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": { "runs": [{ "text": "Die With A Smile" }] }
                          }
                        },
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": {
                              "runs": [
                                {
                                  "text": "Lady Gaga",
                                  "navigationEndpoint": {
                                    "browseEndpoint": {
                                      "browseId": "UC-lady-gaga",
                                      "browseEndpointContextSupportedConfigs": {
                                        "browseEndpointContextMusicConfig": {
                                          "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                        }
                                      }
                                    }
                                  }
                                },
                                { "text": " & " },
                                {
                                  "text": "Bruno Mars",
                                  "navigationEndpoint": {
                                    "browseEndpoint": {
                                      "browseId": "UC-bruno-mars",
                                      "browseEndpointContextSupportedConfigs": {
                                        "browseEndpointContextMusicConfig": {
                                          "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                        }
                                      }
                                    }
                                  }
                                }
                              ]
                            }
                          }
                        }
                      ],
                      "playlistItemData": { "videoId": "die-with-a-smile" },
                      "thumbnail": {
                        "musicThumbnailRenderer": {
                          "thumbnail": {
                            "thumbnails": [{ "url": "https://example.com/die-with-a-smile.jpg" }]
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

        val song = ArtistPage.fromSectionListRendererContent(content)
            ?.items?.single() as SongItem

        assertEquals(listOf("Lady Gaga", "Bruno Mars"), song.artists.map { it.name })
        assertEquals(listOf("UC-lady-gaga", "UC-bruno-mars"), song.artists.map { it.id })
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `two row song keeps every artist on artist and home pages`() {
        val content = json.decodeFromString<SectionListRenderer.Content>(
            """
            {
              "musicCarouselShelfRenderer": {
                "header": {
                  "musicCarouselShelfBasicHeaderRenderer": {
                    "title": { "runs": [{ "text": "Popular" }] }
                  }
                },
                "contents": [
                  {
                    "musicTwoRowItemRenderer": {
                      "title": { "runs": [{ "text": "Die With A Smile" }] },
                      "subtitle": {
                        "runs": [
                          {
                            "text": "Lady Gaga",
                            "navigationEndpoint": {
                              "browseEndpoint": { "browseId": "UC-lady-gaga" }
                            }
                          },
                          { "text": " & " },
                          {
                            "text": "Bruno Mars",
                            "navigationEndpoint": {
                              "browseEndpoint": { "browseId": "UC-bruno-mars" }
                            }
                          },
                          { "text": " • " },
                          { "text": "3.5B plays" }
                        ]
                      },
                      "thumbnailRenderer": {
                        "musicThumbnailRenderer": {
                          "thumbnail": {
                            "thumbnails": [{ "url": "https://example.com/die-with-a-smile.jpg" }]
                          }
                        }
                      },
                      "navigationEndpoint": {
                        "watchEndpoint": { "videoId": "die-with-a-smile" }
                      }
                    }
                  }
                ],
                "itemSize": "MUSIC_TWO_ROW_ITEM_THUMBNAIL_SIZE_MEDIUM"
              }
            }
            """.trimIndent()
        )

        val artistSong = ArtistPage.fromSectionListRendererContent(content)
            ?.items?.single() as SongItem
        val homeSong = HomePage.Section.fromMusicCarouselShelfRenderer(
            content.musicCarouselShelfRenderer!!
        )?.items?.single() as SongItem

        assertEquals(listOf("Lady Gaga", "Bruno Mars"), artistSong.artists.map { it.name })
        assertEquals(listOf("Lady Gaga", "Bruno Mars"), homeSong.artists.map { it.name })
    }

    @Test
    fun `japanese artist separators keep every artist and exclude duration`() {
        listOf("、", "・", " × ").forEach { separator ->
            val artists = listOf(
                Run(
                    text = "DAOKO",
                    navigationEndpoint = NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint(browseId = "UC-daoko")
                    ),
                ),
                Run(text = separator, navigationEndpoint = null),
                Run(
                    text = "米津玄師",
                    navigationEndpoint = NavigationEndpoint(
                        browseEndpoint = BrowseEndpoint(browseId = "UC-kenshi-yonezu")
                    ),
                ),
                Run(text = " • ", navigationEndpoint = null),
                Run(text = "4:49", navigationEndpoint = null),
            ).artistElements()

            assertEquals(listOf("DAOKO", "米津玄師"), artists.map { it.text })
        }
    }
}
