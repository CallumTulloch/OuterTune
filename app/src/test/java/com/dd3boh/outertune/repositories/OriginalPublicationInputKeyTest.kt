package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.MainSongReference
import com.zionhuang.innertube.models.SongItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OriginalPublicationInputKeyTest {
    private val target = OriginalNameTarget(OriginalNameKind.SONG, "abcdefghijk")
    private val original = ArtTrackOriginalName(target, "Something In The Way", target.id, "MPREalbum")

    @Test fun `evaluation and input fingerprint writes do not request another source evaluation`() {
        val raw = row()
        val english = assessed(OriginalNameLanguage.ENGLISH, 200)
        val other = assessed(OriginalNameLanguage.OTHER, 300)
        assertNotEquals(english.originEvidenceJson, other.originEvidenceJson)
        assertEquals(key(raw), key(english))
        assertEquals(key(english), key(other))
    }

    @Test fun `changed original name album identity and source identity invalidate the key`() {
        val baseline = key(row())
        listOf(
            original.copy(name = "A Changed Original"),
            original.copy(albumId = "MPREanother"),
            original.copy(albumId = null),
            original.copy(target = OriginalNameTarget(OriginalNameKind.SONG, "lmnopqrstuv"), sourceVideoId = "lmnopqrstuv"),
            original.copy(target = OriginalNameTarget(OriginalNameKind.ARTIST, "UCartist")),
        ).forEach { changed -> assertNotEquals("Changed candidate: $changed", baseline, key(row(changed))) }
    }

    @Test fun `all row fields including priority and observation time remain inputs`() {
        val originalRow = row()
        val changes = listOf(
            originalRow.copy(kind = "ARTIST"),
            originalRow.copy(targetId = "lmnopqrstuv"),
            originalRow.copy(language = "en"),
            originalRow.copy(name = "Changed row name"),
            originalRow.copy(source = ORIGINAL_NAME_SOURCE_PREFIX + "lmnopqrstuv"),
            originalRow.copy(sourcePriority = originalRow.sourcePriority + 1),
            originalRow.copy(observedAt = originalRow.observedAt + 1),
        )
        changes.forEach { changed -> assertNotEquals("Changed row: $changed", key(originalRow), key(changed)) }
    }

    @Test fun `invalid originals and English or mismatched source rows preserve exact raw evidence`() {
        val malformed = row().copy(originEvidenceJson = "{broken")
        assertNotEquals(key(malformed), key(malformed.copy(originEvidenceJson = "{}")))
        assertNotEquals(key(row()), key(row().copy(originEvidenceJson = "{}")))
        assertNotEquals(key(row().copy(originEvidenceJson = "{}")), key(row().copy(originEvidenceJson = null)))
        val english = row().copy(language = "en")
        assertNotEquals(key(english), key(english.copy(originEvidenceJson = assessed(OriginalNameLanguage.ENGLISH, 200).originEvidenceJson)))
        val mismatched = row().copy(source = ORIGINAL_NAME_SOURCE_PREFIX + "lmnopqrstuv")
        assertNotEquals(key(mismatched), key(mismatched.copy(originEvidenceJson = assessed(OriginalNameLanguage.ENGLISH, 200).originEvidenceJson)))
    }

    @Test fun `reference proof updates and withdrawals remain distinct`() {
        val otherId = "lmnopqrstuv"
        val reference = providerSongReference(MainSongReference(target.id, otherId), original,
            SongItem(otherId, original.name, emptyList(), Album("Album", original.albumId!!), thumbnail = ""))!!
            .toMetadataName(100)
        val withdrawn = reference.copy(originEvidenceJson = null)
        val invalidated = reference.copy(originEvidenceJson = "{}")
        assertNotEquals(key(reference), key(withdrawn))
        assertNotEquals(key(reference), key(invalidated))
        assertNotEquals(key(withdrawn), key(invalidated))
        assertNotEquals(key(reference), key(reference.copy(originEvidenceJson = reference.originEvidenceJson + " ")))
    }

    @Test fun `manual and non input languages do not trigger original processing`() {
        val base = row()
        val excluded = listOf(base.copy(language = "ja"), base.copy(language = "fr"),
            base.copy(language = "en", source = "manual"), base.copy(source = "manual"))
        assertEquals(key(base), originalPublicationInputKey(listOf(base) + excluded))
        assertEquals(key(base), originalPublicationInputKey(listOf(base) + excluded.map {
            it.copy(name = "Another name", observedAt = 900, originEvidenceJson = "changed")
        }))
        val englishAlias = base.copy(language = "en", source = "detail", originEvidenceJson = null)
        assertNotEquals(key(base), originalPublicationInputKey(listOf(base, englishAlias)))
    }

    @Test fun `valid source normalization ignores JSON layout and rejected album links`() {
        val raw = row(original.copy(albumId = null))
        val root = Json.parseToJsonElement(raw.originEvidenceJson!!).jsonObject
        val payload = root.getValue("original").jsonObject
        val ignored = buildJsonObject {
            put("ignoredField", "does not define an original")
            put("original", JsonObject(payload + ("albumId" to JsonPrimitive("not an album id"))))
        }.toString()
        assertEquals(key(raw), key(raw.copy(originEvidenceJson = "  $ignored\n")))
        val reordered = JsonObject(mapOf("original" to JsonObject(payload.entries.reversed().associate { it.toPair() })))
        assertEquals(key(raw), key(raw.copy(originEvidenceJson = reordered.toString())))
    }

    @Test fun `row ordering is stable and sorted by the same identity fields`() {
        val raw = row()
        val english = raw.copy(language = "en", source = "detail", originEvidenceJson = null)
        val artist = row(original.copy(target = OriginalNameTarget(OriginalNameKind.ARTIST, "UCartist")))
        val values = listOf(raw, english, artist)
        assertEquals(originalPublicationInputKey(values), originalPublicationInputKey(values.reversed()))
        assertEquals(listOf(artist.kind to artist.language, english.kind to english.language, raw.kind to raw.language),
            originalPublicationInputKey(values).map { it.row.kind to it.row.language })
        // Duplicate identity rows are not usual DB input, but the old stable sort did not reorder them by time.
        val later = raw.copy(observedAt = 200)
        assertEquals(listOf(200L, 100L), originalPublicationInputKey(listOf(later, raw)).map { it.row.observedAt })
    }

    private fun key(value: MetadataNameEntity) = originalPublicationInputKey(listOf(value))

    private fun row(candidate: ArtTrackOriginalName = original) = MetadataNameEntity(
        candidate.target.kind.name, candidate.target.id, "und", candidate.name,
        ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, sourcePriority = 10, observedAt = 100,
        originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate),
    )

    private fun assessed(language: OriginalNameLanguage, evaluatedAt: Long): MetadataNameEntity {
        val assessment = OriginalNameAssessment(target, original.name, original.sourceVideoId,
            "https://www.youtube.com/watch?v=${original.sourceVideoId}", language, 0.99f,
            "fixture-$language", "fingerprint-$evaluatedAt", evaluatedAt)
        val encoded = Json.parseToJsonElement(ArtTrackOriginalNameCodec.encode(original, assessment)).jsonObject
        return row().copy(originEvidenceJson = buildJsonObject {
            encoded.forEach { (key, value) -> put(key, value) }
            put("assessmentInputSet", buildJsonObject {
                put("version", 1)
                put("model", "fixture-$evaluatedAt")
                put("fingerprint", "input-$evaluatedAt")
            })
        }.toString())
    }
}
