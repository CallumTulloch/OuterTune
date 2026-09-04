package com.zionhuang.innertube

import com.zionhuang.innertube.models.response.TrackCreditsResponse
import com.zionhuang.innertube.pages.TrackCredits
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackCreditsParsingTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `target credits retain primary display and localized sections as uninterpreted runs`() {
        val response = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }.decodeFromString<TrackCreditsResponse>(
            requireNotNull(javaClass.getResource("/track-credits-response.json")).readText()
        )

        val credits = TrackCredits.fromResponse(response)!!

        assertEquals("TSZhKssbW2g", credits.videoId)
        assertEquals(
            listOf("我也去当个天命人玩玩（《黑神话：悟空》最终预告）（It Will Fit Me Just As Well） (特别版)"),
            credits.titleRuns.map { it.text },
        )
        assertEquals(
            listOf("翟锦彦"),
            credits.primaryArtistDisplayRuns.map { it.text }
        )
        assertEquals(
            listOf("演奏", "作詞・作曲", "プロデューサー", "音楽のメタデータの提供者"),
            credits.sections.map { section -> section.titleRuns.joinToString("") { it.text } }
        )
        assertEquals(
            listOf("翟锦彦", "\n", "8082Audio"),
            credits.sections.first().valueRuns.map { it.text }
        )
    }
}
