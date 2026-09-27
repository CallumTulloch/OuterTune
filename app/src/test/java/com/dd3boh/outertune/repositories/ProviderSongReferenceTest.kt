package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameAssessmentCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNamePolicy
import com.dd3boh.outertune.models.metadata.OriginalNameSelectionReason
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.MainSongReference
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import org.junit.Assert.*
import org.junit.Test

class ProviderSongReferenceTest {
    private val sourceId = "abcdefghijk"
    private val targetId = "lmnopqrstuv"
    private val otherId = "12345678901"
    private val albumId = "MPREalbum"
    private val original = ArtTrackOriginalName(song(sourceId), "Something In The Way", sourceId, albumId)
    private val edge = MainSongReference(sourceId, targetId)

    @Test fun `factory corroborates exact provider ids album and name without an artist requirement`() {
        val reference = providerSongReference(edge, original, track())!!
        val row = reference.toMetadataName(100)
        assertEquals(reference, ProviderSongReferenceCodec.decode(row))
        assertEquals("SONG", row.kind)
        assertEquals(targetId, row.targetId)
        assertEquals("und", row.language)
        assertEquals(PROVIDER_SONG_REFERENCE_SOURCE_PREFIX + sourceId, row.source)
        assertEquals(original.name, row.name)
        assertEquals(64, reference.sourceOriginalFingerprint.length)

        val accented = original.copy(name = "Café")
        assertNotNull(providerSongReference(edge, accented, track().copy(title = "  Cafe\u0301  ")))
        assertNull(providerSongReference(edge, original, track().copy(title = original.name.lowercase())))
    }

    @Test fun `factory rejects title-only guesses foreign albums wrong ids and self references`() {
        val invalidSources = listOf(
            original.copy(target = OriginalNameTarget(OriginalNameKind.ARTIST, sourceId)),
            original.copy(target = song(otherId)), original.copy(sourceVideoId = otherId),
            original.copy(sourceVideoId = "short", target = song("short")),
            original.copy(albumId = null), original.copy(albumId = ""),
            original.copy(albumId = "album with spaces"), original.copy(name = ""),
        )
        invalidSources.forEach { assertNull(providerSongReference(edge, it, track())) }
        val invalidTargets = listOf(
            track().copy(id = otherId), track().copy(id = sourceId), track().copy(album = null),
            track().copy(album = Album("Other", "MPREother")), track().copy(title = "Similar title"),
            track().copy(endpoint = WatchEndpoint(videoId = otherId)),
        )
        invalidTargets.forEach { assertNull(providerSongReference(edge, original, it)) }
        assertNull(providerSongReference(MainSongReference(sourceId, sourceId), original, track().copy(id = sourceId)))
        assertNull(providerSongReference(MainSongReference(otherId, targetId), original, track()))
    }

    @Test fun `codec binds every row field and rejects malformed legacy or altered payloads`() {
        val row = referenceRow()
        val invalidRows = listOf(row.copy(kind = "ARTIST"), row.copy(language = "en"),
            row.copy(targetId = otherId), row.copy(name = "Another title"),
            row.copy(source = PROVIDER_SONG_REFERENCE_SOURCE_PREFIX + otherId),
            row.copy(source = ORIGINAL_NAME_SOURCE_PREFIX + sourceId), row.copy(originEvidenceJson = null))
        invalidRows.forEach { assertNull(ProviderSongReferenceCodec.decode(it)) }
        val json = row.originEvidenceJson!!
        val invalidPayloads = listOf("{}", "[]", "not-json",
            json.replace("\"version\":1", "\"version\":2"),
            json.replace("\"version\":1", "\"version\":\"1\""),
            json.replace("\"sourceVideoId\":\"$sourceId\"", "\"sourceVideoId\":\"bad\""),
            json.replace("\"targetVideoId\":\"$targetId\"", "\"targetVideoId\":\"$sourceId\""),
            json.replace("\"sourceAlbumId\":\"$albumId\"", "\"sourceAlbumId\":\"MPREother\""),
            json.replace("\"sourceOriginalFingerprint\":\"", "\"sourceOriginalFingerprint\":\"altered"))
        invalidPayloads.forEach { assertNull(ProviderSongReferenceCodec.decode(row.copy(originEvidenceJson = it))) }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderSongReferenceCodec.encode(providerSongReference(edge, original, track())!!.copy(targetVideoId = sourceId))
        }
    }

    @Test fun `association adapts a validated assessment only in memory and never inflates album inputs`() {
        val direct = assessed(listOf(original))
        val rows = direct + referenceRow()
        val sourceAssessment = OriginalNameAssessmentCodec.decode(direct.single().originEvidenceJson, original.target, original.name)!!
        assertEquals(listOf(sourceAssessment.copy(target = song(targetId))), associatedOriginalAssessments(rows))
        assertEquals(direct, latestOriginalRows(rows))
        assertEquals(originalAssessmentInputs(direct).fingerprint, originalAssessmentInputs(rows).fingerprint)
        assertNull(originalCandidate(referenceRow()))
        assertNull(OriginalNameAssessmentCodec.decode(referenceRow().originEvidenceJson, song(targetId), original.name))
        assertThrows(IllegalArgumentException::class.java) {
            OriginalNameAssessmentCodec.encode(associatedOriginalAssessments(rows).single())
        }
        assertEquals(OriginalNameSelectionReason.AUTOMATIC_ENGLISH_ORIGINAL, select(rows).reason)
        assertEquals("設定名", select(rows, preferOriginal = false).name)
    }

    @Test fun `missing unassessed obsolete and foreign source assessments cannot support an edge`() {
        val valid = assessed(listOf(original)).single()
        val json = valid.originEvidenceJson!!
        val variants = listOf(raw(original), valid.copy(originEvidenceJson = null),
            valid.copy(originEvidenceJson = ArtTrackOriginalNameCodec.encode(original, assessment(original))),
            valid.copy(originEvidenceJson = json.replace(OriginalAlbumLanguageResolver.METHOD_VERSION, "old-model")),
            valid.copy(originEvidenceJson = json.replace("\"resolverVersion\":2", "\"resolverVersion\":1")),
            valid.copy(originEvidenceJson = json.replace("\"sourceVideoId\":\"$sourceId\"", "\"sourceVideoId\":\"$otherId\"")))
        assertTrue(associatedOriginalAssessments(listOf(referenceRow())).isEmpty())
        variants.forEach { assertTrue(associatedOriginalAssessments(listOf(it, referenceRow())).isEmpty()) }
    }

    @Test fun `new source name or album context invalidates the earlier association`() {
        val before = assessed(listOf(original))
        val changedCandidates = listOf(original.copy(name = "New original"),
            original.copy(albumId = "MPREother"), original.copy(albumId = null))
        changedCandidates.forEach { changed ->
            val current = assessed(listOf(changed), at = 200)
            assertTrue(associatedOriginalAssessments(before + current + referenceRow()).isEmpty())
        }
    }

    @Test fun `identical original refresh keeps the association independently of observation time`() {
        val old = assessed(listOf(original))
        val current = old.map { it.copy(observedAt = 200) }
        assertEquals(associatedOriginalAssessments(old + referenceRow()),
            associatedOriginalAssessments(old + current + referenceRow()))
        assertEquals(ProviderSongReferenceCodec.decode(referenceRow(100)),
            ProviderSongReferenceCodec.decode(referenceRow(200)))
    }

    @Test fun `album context changes require current input assessment before an association is reused`() {
        val old = assessed(listOf(original))
        val additional = original.copy(target = song(otherId), sourceVideoId = otherId, name = "別の原題")
        assertTrue(associatedOriginalAssessments(old + raw(additional, 200) + referenceRow()).isEmpty())
        val reassessed = assessed(listOf(original, additional), language = OriginalNameLanguage.OTHER, at = 300)
        assertEquals(OriginalNameLanguage.OTHER, associatedOriginalAssessments(reassessed + referenceRow()).single().language)
    }

    @Test fun `newer withdrawn or invalid reference suppresses older valid observations`() {
        val direct = assessed(listOf(original))
        val old = referenceRow(100)
        for (payload in listOf(null, "{}", "not-json")) {
            val withdrawn = old.copy(observedAt = 200, originEvidenceJson = payload)
            assertTrue(associatedOriginalAssessments(direct + old + withdrawn).isEmpty())
            assertTrue(associatedOriginalAssessments(direct + withdrawn + old).isEmpty())
        }
        assertEquals(1, associatedOriginalAssessments(direct + old + old.copy(observedAt = 200)).size)
    }

    @Test fun `different supporting originals remain conflicts instead of whichever source arrives last`() {
        val other = original.copy(target = song(otherId), sourceVideoId = otherId, name = "A Different Name")
        val secondEdge = providerSongReference(MainSongReference(otherId, targetId), other,
            track().copy(title = other.name))!!.toMetadataName(200)
        val rows = assessed(listOf(original, other)) + referenceRow() + secondEdge
        assertEquals(2, associatedOriginalAssessments(rows).size)
        assertEquals(OriginalNameSelectionReason.CONFLICTING_EVIDENCE, select(rows).reason)
        assertEquals(OriginalNameSelectionReason.CONFLICTING_EVIDENCE, select(rows.reversed()).reason)
    }

    @Test fun `an ambiguous direct source cannot lend only its convenient spelling or album context`() {
        // Distinct names can coexist in one snapshot. The same name/source/target is a single
        // database key, so two versions differing only by album context cannot both be current.
        for (alternative in listOf(original.copy(name = "Conflicting original"),
            original.copy(name = "Context-free original", albumId = null))) {
            val rows = assessed(listOf(original, alternative)) + referenceRow()
            assertEquals(setOf(original, alternative), originalAssessmentInputs(rows).candidates)
            assertTrue(associatedOriginalAssessments(rows).isEmpty())
        }
    }

    @Test fun `unknown and non English source decisions never turn into English`() {
        for (language in listOf(OriginalNameLanguage.UNKNOWN, OriginalNameLanguage.OTHER)) {
            val rows = assessed(listOf(original), language) + referenceRow()
            assertEquals(language, associatedOriginalAssessments(rows).single().language)
            assertEquals("設定名", select(rows).name)
        }
    }

    @Test fun `associations cannot recursively act as originals for another target`() {
        val intermediate = original.copy(target = song(targetId), sourceVideoId = targetId)
        val nextEdge = providerSongReference(MainSongReference(targetId, otherId), intermediate,
            track().copy(id = otherId))!!.toMetadataName(200)
        val rows = assessed(listOf(original)) + referenceRow() + nextEdge
        assertEquals(listOf(song(targetId)), associatedOriginalAssessments(rows).map { it.target })
    }

    private fun referenceRow(at: Long = 100) = providerSongReference(edge, original, track())!!.toMetadataName(at)
    private fun song(id: String) = OriginalNameTarget(OriginalNameKind.SONG, id)
    private fun track() = SongItem(targetId, original.name, emptyList(), Album("Album", albumId), thumbnail = "")

    private fun raw(candidate: ArtTrackOriginalName, at: Long = 100) = MetadataNameEntity(
        candidate.target.kind.name, candidate.target.id, "und", candidate.name,
        ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, observedAt = at,
        originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate),
    )

    private fun assessment(candidate: ArtTrackOriginalName, language: OriginalNameLanguage = OriginalNameLanguage.ENGLISH,
        at: Long = 100) = OriginalNameAssessment(candidate.target, candidate.name, candidate.sourceVideoId,
        "https://www.youtube.com/watch?v=${candidate.sourceVideoId}", language, 0.99f,
        OriginalAlbumLanguageResolver.METHOD_VERSION + "/individual-${language.name.lowercase()}", "classifier-input", at)

    private fun assessed(candidates: List<ArtTrackOriginalName>, language: OriginalNameLanguage = OriginalNameLanguage.ENGLISH,
        at: Long = 100): List<MetadataNameEntity> {
        val rows = candidates.map { raw(it, at) }
        return candidates.mapIndexed { index, candidate -> rows[index].copy(originEvidenceJson =
            encodeOriginalAssessment(candidate, assessment(candidate, language, at), rows)) }
    }

    private fun select(rows: List<MetadataNameEntity>, preferOriginal: Boolean = true) = OriginalNamePolicy.select(
        song(targetId), "設定名", original.name, "Fallback", preferOriginal, emptyList(), associatedOriginalAssessments(rows),
    )
}
