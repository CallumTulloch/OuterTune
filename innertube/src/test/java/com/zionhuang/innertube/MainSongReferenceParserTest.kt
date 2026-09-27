package com.zionhuang.innertube

import com.zionhuang.innertube.models.MainSongReference
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSongReferenceParserTest {
    private val source = "ljUtuoFt-8c"
    private val target = "hTWKbfoikeg"

    @Test fun `accepts the source-bound explicit music card without using localized text`() {
        val raw = response(cards = """{"videoAttributeViewModel": {
            "title": "スメルズ・ライク・ティーン・スピリット", "subtitle": "Nirvana",
            "onTap": {"innertubeCommand": {"watchEndpoint": {"videoId": "$target"}}}
        }}""")
        assertEquals(MainSongReference(source, target), parse(raw))
    }

    @Test fun `rejects wrong missing and malformed source identities`() {
        assertNull(parse(response(endpoint = """{"watchEndpoint":{"videoId":"$target"}}""")))
        assertNull(parse(response(endpoint = "null")))
        assertNull(parse(response(endpoint = """{"watchEndpoint":{"videoId":12345678901}}""")))
        assertNull(parseMainSongReference(Json.parseToJsonElement(response()), "short"))
        assertNull(parse("{}"))
        assertNull(parse("[]"))
    }

    @Test fun `rejects multiple music cards even with equal targets or an invalid second card`() {
        assertNull(parse(response(cards = "${card()},${card()}")))
        assertNull(parse(response(cards = "${card()},${card("abcdefghijk")}")))
        assertNull(parse(response(cards = "${card()},{\"videoAttributeViewModel\":{}}")))
        assertNull(parse(response(cards = "${card()},{\"videoAttributeViewModel\":null}")))
    }

    @Test fun `counts music cards across different structured description panels`() {
        val panel = panel(card())
        assertNull(parse("""{"currentVideoEndpoint":{"watchEndpoint":{"videoId":"$source"}},
            "engagementPanels":[$panel,$panel]}"""))
    }

    @Test fun `rejects missing endpoint self links and invalid target ids`() {
        assertNull(parse(response(cards = """{"videoAttributeViewModel":{"title":"$target"}}""")))
        assertNull(parse(response(cards = card(source))))
        assertNull(parse(response(cards = card("short"))))
        assertNull(parse(response(cards = card("abcdefghij!"))))
        assertNull(parse(response(cards = """{"videoAttributeViewModel":{
            "onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":12345678901}}}
        }}""")))
    }

    @Test fun `ignores recommendation cards and arbitrary nested endpoint matches`() {
        val recommendation = """"contents":{"twoColumnWatchNextResults":{"secondaryResults":{
            "results":[${card()}]}}},"""
        assertNull(parse("""{$recommendation "currentVideoEndpoint":{"watchEndpoint":{"videoId":"$source"}}}"""))
        assertNull(parse(response(cards = """{"videoAttributeViewModel":{"other":{"onTap":{
            "innertubeCommand":{"watchEndpoint":{"videoId":"$target"}}}}}}""")))
        assertNull(parse(response(cards = """{"videoAttributeViewModel":{"onTap":{"innertubeCommand":{
            "commandMetadata":{"webCommandMetadata":{"url":"/watch?v=$target"}}
        }}}}""")))
        // An unrelated recommendation is not another attributed song in the structured description.
        val actual = response().replaceFirst("{", "{$recommendation")
        assertEquals(MainSongReference(source, target), parse(actual))
    }

    @Test fun `ignores non music cards and never accepts cards outside the exact description path`() {
        assertEquals(MainSongReference(source, target), parse(response(cards = "{},${card()}")))
        assertNull(parse(response().replace("structuredDescriptionContentRenderer", "unrelatedRenderer")))
        assertNull(parse(response().replace("horizontalCardListRenderer", "musicCarouselShelfRenderer")))
        assertNull(parse(response().replace("watchEndpoint", "watchPlaylistEndpoint")))
    }

    @Test fun `only a source bound complete description can confirm a missing card`() {
        for (cards in listOf("", "{}", """{"otherCardViewModel":{"title":"No music attribution"}}""")) {
            val complete = completeDescription(response(cards))
            val result = parseMainSongReferenceResult(Json.parseToJsonElement(complete), source)
            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow())
        }
        val valid = parseMainSongReferenceResult(Json.parseToJsonElement(response()), source)
        assertEquals(MainSongReference(source, target), valid.getOrThrow())
    }

    @Test fun `partial empty wrong source and ambiguous responses fail instead of withdrawing an existing card`() {
        val incomplete = listOf("{}", "[]",
            """{"currentVideoEndpoint":{"watchEndpoint":{"videoId":"$source"}},"engagementPanels":[]}""",
            response(endpoint = """{"watchEndpoint":{"videoId":"$target"}}"""),
            response().replace("structuredDescriptionContentRenderer", "unrelatedRenderer"),
            response().replace("\"cards\":", "\"missingCards\":"),
            response().replace("\"items\":", "\"missingItems\":"),
            response(cards = ""),
            completeDescription(response(cards = "")).replace("horizontalCardListRenderer", "unknownCardRenderer"),
            response(cards = "${card()},${card()}"),
            response(cards = """{"videoAttributeViewModel":null}"""),
            response(cards = """{"videoAttributeViewModel":{}}"""))
        incomplete.forEach { raw ->
            assertTrue("Cannot treat incomplete response as an explicit withdrawal: $raw",
                parseMainSongReferenceResult(Json.parseToJsonElement(raw), source).isFailure)
        }
    }

    private fun completeDescription(raw: String) = raw.replace("\"items\":[", """"items":[
        {"videoDescriptionHeaderRenderer":{"title":{"runs":[{"text":"Observed source title"}]}}},
        {"expandableVideoDescriptionBodyRenderer":{"descriptionPlaceholder":{"content":"No description"}}},
    """)

    private fun parse(raw: String) = parseMainSongReference(Json.parseToJsonElement(raw), source)

    private fun card(id: String = target) = """{"videoAttributeViewModel":{
        "onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"$id"}}}
    }}"""

    private fun panel(cards: String) = """{"engagementPanelSectionListRenderer":{"content":{
        "structuredDescriptionContentRenderer":{"items":[{"horizontalCardListRenderer":{"cards":[$cards]}}]}
    }}}"""

    private fun response(
        cards: String = card(),
        endpoint: String = """{"watchEndpoint":{"videoId":"$source"}}""",
    ) = """{"currentVideoEndpoint":$endpoint,"engagementPanels":[${panel(cards)}]}"""
}
