package com.zionhuang.innertube

import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.SongItem
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
}
