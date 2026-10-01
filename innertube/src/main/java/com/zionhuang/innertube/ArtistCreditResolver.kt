package com.zionhuang.innertube

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.merge
import com.zionhuang.innertube.models.isChannelByline
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)
internal fun JsonElement?.elements(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
internal fun JsonElement?.value(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.runText(): String = field("runs").elements().joinToString("") { it.field("text").value().orEmpty() }

internal object ArtistCreditResolver {
    // These are standalone separators between independently supplied candidate names, not a split regex.
    private val separators = listOf("、", " & ", ", ", "・", " × ", " and ", " feat. ", " featuring ")
    private const val MAX_SEARCH_STEPS = 10_000

    /** Retry markers describe this attempt's transport failures, not permanent identity evidence. */
    fun beginAttempt(credit: ArtistCredit): ArtistCredit =
        credit.copy(evidence = credit.evidence.filterNot { it.startsWith("retry:") })

    fun pageArtist(response: JsonElement, requestedId: String): Artist? {
        val canonical = response.field("microformat").field("microformatDataRenderer").field("urlCanonical").value()
        val tabId = response.field("contents").field("singleColumnBrowseResultsRenderer").field("tabs")
            .elements().firstOrNull().field("tabRenderer").field("endpoint").field("browseEndpoint").field("browseId").value()
        if (tabId != requestedId && canonical?.substringBefore('?')?.substringAfterLast('/') != requestedId) return null
        val header = response.field("header")
        val name = listOf("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer", "musicHeaderRenderer")
            .firstNotNullOfOrNull { header.field(it).field("title").runText().takeIf(String::isNotBlank) }
            ?: return null
        return Artist(name, requestedId)
    }

    fun performers(response: JsonElement, videoId: String): List<Artist> {
        val dialog = response.field("onResponseReceivedActions").elements().mapNotNull {
            it.field("openPopupAction").field("popup").field("dismissableDialogRenderer")
        }.singleOrNull() ?: return emptyList()
        val returnedIds = dialog.field("metadata").field("musicMultiRowListItemRenderer")
            .field("title").field("runs").elements().mapNotNull {
                it.field("navigationEndpoint").field("watchEndpoint").field("videoId").value()
            }
        if (returnedIds.distinct() != listOf(videoId)) return emptyList()
        return dialog.field("sections").elements().flatMap { section ->
            val renderer = section.field("dismissableDialogContentSectionRenderer")
            val role = renderer.field("title").runText()
            if (role !in setOf("演奏", "Performed by", "Performers", "演出者", "表演者")) emptyList()
            else renderer.field("subtitle").field("runs").elements().mapNotNull { run ->
                val name = run.field("text").value()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // A multiline text block has no independently supplied name boundaries.
                if ('\n' in name || '\r' in name) return@mapNotNull null
                Artist(name, null)
            }
        }
    }

    fun fromPerformers(existing: ArtistCredit, candidates: List<Artist>): ArtistCredit {
        if (existing.status == ArtistCreditStatus.COMPLETE || existing.status == ArtistCreditStatus.CONFLICT)
            return existing
        if (candidates.isEmpty() || candidates.any { it.name.isBlank() } ||
            candidates.map { it.name }.distinct().size != candidates.size) return existing
        val raw = existing.rawText
        if (raw.isEmpty() || raw.length > 2000 || candidates.size > 100) return existing
        val solutions = mutableListOf<List<Artist>>()
        var steps = 0
        var exhausted = false
        fun search(position: Int, selected: List<Artist>) {
            if (exhausted || solutions.size > 1) return
            if (++steps > MAX_SEARCH_STEPS) { exhausted = true; return }
            if (position == raw.length) { solutions.add(selected); return }
            candidates.filter { it !in selected && raw.startsWith(it.name, position) }.forEach { artist ->
                val end = position + artist.name.length
                if (end == raw.length) search(end, selected + artist)
                else separators.filter { raw.startsWith(it, end) }.forEach { separator ->
                    search(end + separator.length, selected + artist)
                }
            }
        }
        search(0, emptyList())
        // Finding one interpretation is not proof of uniqueness if the remaining search
        // could not finish. Retain every previously adopted name and the original literal.
        if (exhausted) return existing.copy(evidence = (existing.evidence + "credits:search-limit").distinct())
        if (solutions.size > 1) return existing.copy(evidence = existing.evidence + "credits:ambiguous-name-boundaries")
        if (solutions.size == 1) return existing.merge(ArtistCredit(raw, solutions.single(),
            ArtistCreditStatus.COMPLETE, "track-credits:performers", existing.language,
            listOf("credits:performers:unique-full-coverage")))

        // Partial adoption requires whole candidate names at independently recognizable boundaries.
        data class Occurrence(val start: Int, val end: Int, val artist: Artist)
        val occurrences = candidates.flatMap { artist ->
            buildList {
                var start = raw.indexOf(artist.name)
                while (start >= 0) {
                    val end = start + artist.name.length
                    val left = start == 0 || separators.any { raw.substring(0, start).endsWith(it) }
                    val right = end == raw.length || separators.any { raw.startsWith(it, end) }
                    if (left && right) add(Occurrence(start, end, artist))
                    start = raw.indexOf(artist.name, start + 1)
                }
            }
        }
        val unique = occurrences.filter { occurrence ->
            occurrences.count { it.artist.name == occurrence.artist.name } == 1 && occurrences.none {
                it !== occurrence && it.start < occurrence.end && occurrence.start < it.end
            }
        }.sortedBy { it.start }.map { it.artist }
        if (unique.isEmpty()) return existing
        return existing.merge(ArtistCredit(raw, unique, ArtistCreditStatus.PARTIAL,
            "track-credits:performers", existing.language, listOf("credits:performers:unique-partial-coverage")))
    }

    fun withPageNames(existing: ArtistCredit, pages: List<Artist>): ArtistCredit {
        if (existing.status == ArtistCreditStatus.CONFLICT || existing.isChannelByline()) return existing
        val matches = pages.filter { it.id != null && !it.isChannel }.groupBy { it.name }
        if (existing.status == ArtistCreditStatus.RAW) {
            val match = matches[existing.rawText]?.distinctBy { it.id }?.singleOrNull() ?: return existing
            return existing.merge(ArtistCredit(existing.rawText, listOf(match), ArtistCreditStatus.COMPLETE,
                "artist-menu:verified-page", existing.language, listOf("artist-page:${match.id}:whole-name-match")))
        }
        val updated = existing.artists.map { artist ->
            val match = if (artist.isChannel || existing.artists.count { it.name == artist.name } != 1) null
                else matches[artist.name]?.distinctBy { it.id }?.singleOrNull()
            if (match == null) artist else artist.copy(id = match.id)
        }
        return existing.merge(existing.copy(artists = updated,
            evidence = existing.evidence + updated.filter { it.id != null }.map { "artist-page:${it.id}:exact-name-match" }))
    }
}
