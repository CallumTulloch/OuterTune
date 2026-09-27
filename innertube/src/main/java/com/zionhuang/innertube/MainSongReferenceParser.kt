package com.zionhuang.innertube

import com.zionhuang.innertube.models.MainSongReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val mainReferenceVideoId = Regex("[A-Za-z0-9_-]{11}")

/** Only the source-bound structured description may establish this one-hop provider link. */
internal fun parseMainSongReference(response: JsonElement, expectedVideoId: String): MainSongReference? =
    parseMainSongReferenceResult(response, expectedVideoId).getOrNull()

/**
 * Success(null) means a returned, source-bound structured description has no music card. Missing
 * source/description fields, malformed cards and ambiguous attribution are incomplete observations,
 * never evidence that an existing provider link was withdrawn.
 */
internal fun parseMainSongReferenceResult(response: JsonElement, expectedVideoId: String): Result<MainSongReference?> = runCatching {
    require(mainReferenceVideoId.matches(expectedVideoId)) { "Invalid requested Main video ID" }
    val source = response.referenceField("currentVideoEndpoint").referenceField("watchEndpoint")
        .referenceField("videoId").referenceText()
    require(source == expectedVideoId) { "Main attribution belongs to a missing or different source" }

    val panels = response.referenceField("engagementPanels") as? JsonArray
        ?: error("Missing Main description panels")
    val descriptions = panels.mapNotNull { panel ->
        require(panel is JsonObject) { "Malformed Main description panel" }
        val content = panel.referenceField("engagementPanelSectionListRenderer").referenceField("content")
        (content as? JsonObject)?.takeIf { it.containsKey("structuredDescriptionContentRenderer") }
            ?.get("structuredDescriptionContentRenderer")
    }
    // An absent/lazy description panel cannot prove that a formerly observed card is gone.
    require(descriptions.isNotEmpty()) { "Missing complete Main structured description" }
    var completeDescription = false
    var knownDescriptionItems = true
    val knownRenderers = setOf("videoDescriptionHeaderRenderer", "expandableVideoDescriptionBodyRenderer",
        "horizontalCardListRenderer", "videoDescriptionInfocardsSectionRenderer", "videoDescriptionTranscriptSectionRenderer")
    val musicCards = descriptions.flatMap { description ->
        val items = description.referenceField("items") as? JsonArray
            ?: error("Incomplete Main structured description")
        require((description as? JsonObject)?.keys?.none { it.contains("continuation", ignoreCase = true) } == true)
        val hasHeader = items.any { item ->
            val runs = item.referenceField("videoDescriptionHeaderRenderer").referenceField("title")
                .referenceField("runs") as? JsonArray
            runs?.any { it.referenceField("text").referenceText()?.isNotBlank() == true } == true
        }
        val hasBody = items.any { item ->
            val body = item.referenceField("expandableVideoDescriptionBodyRenderer")
            listOf("attributedDescriptionBodyText", "descriptionPlaceholder").any { field ->
                body.referenceField(field).referenceField("content").referenceText()?.isNotBlank() == true
            }
        }
        completeDescription = completeDescription || (hasHeader && hasBody)
        items.flatMap { item ->
            require(item is JsonObject) { "Malformed Main structured description item" }
            knownDescriptionItems = knownDescriptionItems && item.size == 1 && item.keys.single() in knownRenderers &&
                item.values.single() is JsonObject
            require(!item.containsKey("continuationItemRenderer")) { "Incomplete Main structured description items" }
            if (!item.containsKey("horizontalCardListRenderer")) emptyList()
            else {
                val cards = item["horizontalCardListRenderer"].referenceField("cards") as? JsonArray
                    ?: error("Incomplete Main description cards")
                cards.mapNotNull { card ->
                    require(card is JsonObject) { "Malformed Main description card" }
                    if (!card.containsKey("videoAttributeViewModel")) null
                    else card["videoAttributeViewModel"] as? JsonObject
                        ?: error("Malformed Main music attribution")
                }
            }
        }
    }
    if (musicCards.isEmpty()) {
        require(completeDescription && knownDescriptionItems) { "Incomplete Main no-card observation" }
        return@runCatching null
    }
    require(musicCards.size == 1) { "Ambiguous Main music attribution" }
    val target = musicCards.single().referenceField("onTap").referenceField("innertubeCommand")
        .referenceField("watchEndpoint").referenceField("videoId").referenceText()
        ?: error("Missing Main music attribution endpoint")
    require(mainReferenceVideoId.matches(target) && target != source) { "Invalid Main music attribution target" }
    MainSongReference(expectedVideoId, target)
}

private fun JsonElement?.referenceField(name: String): JsonElement? = (this as? JsonObject)?.get(name)
private fun JsonElement?.referenceText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
