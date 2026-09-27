package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameAssessmentCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import org.junit.Assert.*
import org.junit.Test

class PlaylistSongReferenceTest {
    private val sourceId = "ox_BG6sLPq8"
    private val targetId = "J6EDW5WFb2M"
    private val otherId = "_oWUgfpGi0M"
    private val albumId = "MPREalbum"
    private val playlistId = "OLAK5uy_playlist"
    private val original = ArtTrackOriginalName(song(sourceId), "Breed", sourceId, albumId)
    private val edge = PlaylistSongReference(playlistId, "5A39A74538F3ADE6", sourceId, targetId)

    @Test fun `factory scopes explicit stable entry to canonical playlist direct original and target album`() {
        val reference = requireNotNull(reference())
        val row = reference.toMetadataName(100)
        assertEquals(reference, PlaylistSongReferenceCodec.decode(row))
        assertEquals(playlistSongReferenceSource(sourceId, playlistId), row.source)
        assertEquals(targetId, row.targetId)
        assertEquals("und", row.language)
        assertEquals(original.name, row.name)
        assertEquals(albumId, reference.sourceAlbumId)
        assertEquals(albumId, reference.targetAlbumId)
        assertNull(originalCandidate(row))
        assertEquals(64, reference.sourceOriginalFingerprint.length)
        val accent = original.copy(name = "Café")
        assertNotNull(playlistSongReference(edge, accent, track().copy(title = " Cafe\u0301 "), playlistId))
    }

    @Test fun `same name and order cannot substitute foreign missing or conflicting identity`() {
        listOf(edge.copy(playlistId = "OLAK5uy_other"), edge.copy(playlistId = "bad ID"),
            edge.copy(playlistSetVideoId = ""), edge.copy(playlistSetVideoId = "bad ID"),
            edge.copy(sourceVideoId = otherId), edge.copy(targetVideoId = otherId),
            edge.copy(targetVideoId = sourceId)).forEach { invalid ->
            assertNull(playlistSongReference(invalid, original, track(), playlistId))
        }
        listOf(track().copy(album = null), track().copy(album = Album("Same text", "MPREother")),
            track().copy(id = sourceId), track().copy(title = "Similar Breed"),
            track().copy(endpoint = WatchEndpoint(videoId = otherId))).forEach { target ->
            assertNull(playlistSongReference(edge, original, target, playlistId))
        }
        listOf(original.copy(albumId = null), original.copy(target = song(otherId)),
            original.copy(target = OriginalNameTarget(OriginalNameKind.ARTIST, sourceId)),
            original.copy(name = "two\nlines")).forEach { source ->
            assertNull(playlistSongReference(edge, source, track(), playlistId))
        }
    }

    @Test fun `codec binds playlist and entry identity in addition to row and source fingerprint`() {
        val row = referenceRow()
        val json = requireNotNull(row.originEvidenceJson)
        val altered = listOf("{}", "[]", "broken", json.replace(playlistId, "OLAK5uy_other"),
            json.replace(edge.playlistSetVideoId, "ANOTHER_ENTRY"),
            json.replace("\"version\":3", "\"version\":\"3\""),
            json.replace("\"version\":3", "\"version\":1"),
            json.replace("\"sourceMusicName\":\"Breed\"", "\"sourceMusicName\":\"Unrelated\""),
            json.replace("\"targetAlbumId\":\"$albumId\"", "\"targetAlbumId\":\"MPREother\""),
            json.replace("\"sourceOriginalFingerprint\":\"", "\"sourceOriginalFingerprint\":\"changed"),
            json.replace("\"referenceFingerprint\":\"", "\"referenceFingerprint\":\"changed"))
        altered.forEach { assertNull(PlaylistSongReferenceCodec.decode(row.copy(originEvidenceJson = it))) }
        listOf(row.copy(targetId = otherId), row.copy(kind = "ALBUM"), row.copy(language = "en"),
            row.copy(name = "Something else"), row.copy(source = playlistSongReferenceSource(sourceId, "other")),
            row.copy(originEvidenceJson = null)).forEach { assertNull(PlaylistSongReferenceCodec.decode(it)) }
        assertThrows(IllegalArgumentException::class.java) { reference()!!.toMetadataName(0) }
    }

    @Test fun `legacy exact-name playlist evidence remains readable without a new network request`() {
        val legacy = """{"playlistSongReference":{"version":2,"playlistId":"OLAK5uy_playlist","playlistSetVideoId":"5A39A74538F3ADE6","sourceVideoId":"ox_BG6sLPq8","targetVideoId":"J6EDW5WFb2M","originalName":"Breed","sourceAlbumId":"MPREalbum","targetAlbumId":"MPREalbum","sourceOriginalFingerprint":"c2c3ebe24d77ee573af7cfb8df3c79f631922fc9f0131756c2d7b4e518508ace","referenceFingerprint":"5b7e4e3814b52fec6a120c318e259ed8b3c34bff86ce81f07a8358794ce37eff"}}"""
        val row = referenceRow().copy(originEvidenceJson = legacy)
        assertEquals(reference(), PlaylistSongReferenceCodec.decode(row))
        assertEquals(1, playlistAssociatedOriginalAssessments(assessed(listOf(original)) + row).size)
    }

    @Test fun `explicit playlist identity and independently matching Music names preserve Main edition suffix`() {
        // Actual anonymous provider observations for Beatles 1, 2026-09-27: the complete
        // canonical playlist joins these IDs with the same entry while Music omits the suffix.
        val source = ArtTrackOriginalName(song("6gluNoLVKiQ"), "Eleanor Rigby (Remastered 2015)",
            "6gluNoLVKiQ", "MPREb_mnYrD806fc4")
        val observedSource = SongItem(source.sourceVideoId, "Eleanor Rigby", emptyList(),
            Album("1", source.albumId!!), thumbnail = "")
        val target = observedSource.copy(id = "HuS5NuXRb5Y")
        val playlist = "OLAK5uy_mc399CKoHidFSZHLCydnI43dS3O9hEojA"
        val entry = PlaylistSongReference(playlist, "3CF5D4F99A0F04E8", source.sourceVideoId, target.id)
        assertNull(playlistSongReference(entry, source, target, playlist))
        val reference = requireNotNull(playlistSongReference(entry, source, target, playlist,
            sourceMusic = observedSource))
        assertEquals(source.name, reference.originalName)
        assertEquals(observedSource.title, reference.sourceMusicName)
        assertEquals(source.name, reference.toMetadataName(100).name)
        assertEquals(reference, PlaylistSongReferenceCodec.decode(reference.toMetadataName(100)))

        val direct = assessed(listOf(source))
        val combined = direct + reference.toMetadataName(100)
        val associated = playlistAssociatedOriginalAssessments(combined).single()
        assertEquals(source.name, associated.originalName)
        assertEquals(song(target.id), associated.target)
        assertEquals(OriginalNameLanguage.ENGLISH, associated.language)
        assertEquals(direct, latestOriginalRows(combined))
        assertEquals(originalAssessmentInputs(direct).fingerprint, originalAssessmentInputs(combined).fingerprint)
        assertEquals(originalAssessmentInputs(direct).candidates, originalAssessmentInputs(combined).candidates)
        listOf(observedSource.copy(id = otherId), observedSource.copy(album = null),
            observedSource.copy(album = Album("1", "MPREother")),
            observedSource.copy(title = "Eleanor Rigby (Different Version)"),
            observedSource.copy(endpoint = WatchEndpoint(videoId = otherId))).forEach { wrongSource ->
            assertNull(playlistSongReference(entry, source, target, playlist, sourceMusic = wrongSource))
        }
        assertNull(playlistSongReference(entry.copy(playlistSetVideoId = ""), source, target, playlist,
            sourceMusic = observedSource))
        assertNull(playlistSongReference(entry, source, target.copy(title = "Unrelated"), playlist,
            sourceMusic = observedSource))
        assertTrue(playlistAssociatedOriginalAssessments(direct + reference.toMetadataName(100) +
            reference.toMetadataName(200).copy(originEvidenceJson = "{}")).isEmpty())
    }

    @Test fun `explicit canonical playlist can link another edition without replacing source language context`() {
        val targetAlbum = "MPREeditionB"
        val secondPlaylist = "OLAK5uy_editionB"
        val secondTarget = track().copy(id = otherId, album = Album("Edition B", targetAlbum))
        val secondEdge = PlaylistSongReference(secondPlaylist, "entryEditionB", sourceId, otherId)
        // A different target album requires an explicitly verified canonical album scope.
        assertNull(playlistSongReference(secondEdge, original, secondTarget, secondPlaylist))
        assertNull(playlistSongReference(secondEdge, original, secondTarget, playlistId, targetAlbum))
        assertNull(playlistSongReference(secondEdge, original, secondTarget, secondPlaylist, null))
        assertNull(playlistSongReference(secondEdge, original, secondTarget, secondPlaylist, "MPREwrong"))
        val second = requireNotNull(playlistSongReference(secondEdge, original, secondTarget,
            secondPlaylist, expectedAlbumId = targetAlbum))
        assertEquals(albumId, second.sourceAlbumId)
        assertEquals(targetAlbum, second.targetAlbumId)
        assertEquals(second, PlaylistSongReferenceCodec.decode(second.toMetadataName(200)))
        assertEquals(reference()!!.sourceOriginalFingerprint, second.sourceOriginalFingerprint)

        val direct = assessed(listOf(original))
        val combined = direct + referenceRow() + second.toMetadataName(200)
        assertEquals(setOf(song(targetId), song(otherId)), playlistAssociatedOriginalAssessments(combined).map { it.target }.toSet())
        assertTrue(playlistAssociatedOriginalAssessments(combined).all { it.language == OriginalNameLanguage.ENGLISH })
        assertEquals(direct, latestOriginalRows(combined))
        assertEquals(originalAssessmentInputs(direct).fingerprint, originalAssessmentInputs(combined).fingerprint)

        val encoded = requireNotNull(second.toMetadataName(200).originEvidenceJson)
        assertNull(PlaylistSongReferenceCodec.decode(second.toMetadataName(200).copy(originEvidenceJson =
            encoded.replace("\"targetAlbumId\":\"$targetAlbum\"", "\"targetAlbumId\":\"$albumId\""))))
    }

    @Test fun `association uses source decision only in memory without increasing classifier input count`() {
        val direct = assessed(listOf(original))
        val rows = direct + referenceRow()
        val source = OriginalNameAssessmentCodec.decode(direct.single().originEvidenceJson, original.target, original.name)!!
        assertEquals(listOf(source.copy(target = song(targetId))), playlistAssociatedOriginalAssessments(rows))
        assertEquals(direct, latestOriginalRows(rows))
        assertEquals(originalAssessmentInputs(direct).fingerprint, originalAssessmentInputs(rows).fingerprint)
        assertThrows(IllegalArgumentException::class.java) {
            OriginalNameAssessmentCodec.encode(playlistAssociatedOriginalAssessments(rows).single())
        }
        assertTrue(playlistAssociatedOriginalAssessments(listOf(raw(original), referenceRow())).isEmpty())
        assertTrue(playlistAssociatedOriginalAssessments(listOf(referenceRow())).isEmpty())
    }

    @Test fun `each associated song retains English other and unknown decisions independently`() {
        for (language in OriginalNameLanguage.entries) {
            val rows = assessed(listOf(original), language) + referenceRow()
            assertEquals(language, playlistAssociatedOriginalAssessments(rows).single().language)
        }
    }

    @Test fun `new original album context or competing source invalidates stale evidence`() {
        val old = assessed(listOf(original))
        for (changed in listOf(original.copy(name = "Changed"), original.copy(albumId = "MPREother"))) {
            assertTrue(playlistAssociatedOriginalAssessments(old + assessed(listOf(changed), at = 200) + referenceRow()).isEmpty())
        }
        val additional = original.copy(target = song(otherId), sourceVideoId = otherId, name = "別の題名")
        assertTrue(playlistAssociatedOriginalAssessments(old + raw(additional, 200) + referenceRow()).isEmpty())
        assertEquals(1, playlistAssociatedOriginalAssessments(assessed(listOf(original, additional)) + referenceRow()).size)
        assertTrue(playlistAssociatedOriginalAssessments(assessed(listOf(original, original.copy(name = "Conflicting"))) + referenceRow()).isEmpty())
    }

    @Test fun `explicit newer withdrawal suppresses the old association in either row order`() {
        val source = assessed(listOf(original))
        val before = referenceRow()
        for (payload in listOf(null, "{}", "invalid")) {
            val withdrawn = before.copy(observedAt = 200, originEvidenceJson = payload)
            assertTrue(playlistAssociatedOriginalAssessments(source + before + withdrawn).isEmpty())
            assertTrue(playlistAssociatedOriginalAssessments(source + withdrawn + before).isEmpty())
        }
        assertEquals(1, playlistAssociatedOriginalAssessments(source + before + before.copy(observedAt = 200)).size)
    }

    @Test fun `references cannot become chained source originals`() {
        val intermediate = original.copy(target = song(targetId), sourceVideoId = targetId)
        val second = playlistSongReference(PlaylistSongReference(playlistId, "otherEntry", targetId, otherId),
            intermediate, track().copy(id = otherId), playlistId)!!.toMetadataName(200)
        assertEquals(listOf(song(targetId)), playlistAssociatedOriginalAssessments(
            assessed(listOf(original)) + referenceRow() + second).map { it.target })
    }

    private fun reference() = playlistSongReference(edge, original, track(), playlistId)
    private fun referenceRow() = reference()!!.toMetadataName(100)
    private fun song(id: String) = OriginalNameTarget(OriginalNameKind.SONG, id)
    private fun track() = SongItem(targetId, original.name, emptyList(), Album("Album", albumId), thumbnail = "")
    private fun raw(candidate: ArtTrackOriginalName, at: Long = 100) = MetadataNameEntity(
        candidate.target.kind.name, candidate.target.id, "und", candidate.name,
        ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, observedAt = at,
        originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate),
    )
    private fun assessed(candidates: List<ArtTrackOriginalName>, language: OriginalNameLanguage = OriginalNameLanguage.ENGLISH,
        at: Long = 100): List<MetadataNameEntity> {
        val rows = candidates.map { raw(it, at) }
        return candidates.mapIndexed { index, candidate ->
            val assessment = OriginalNameAssessment(candidate.target, candidate.name, candidate.sourceVideoId,
                "https://www.youtube.com/watch?v=${candidate.sourceVideoId}", language, 0.99f,
                OriginalAlbumLanguageResolver.METHOD_VERSION + "/individual-${language.name.lowercase()}", "classifier-input", at)
            rows[index].copy(originEvidenceJson = encodeOriginalAssessment(candidate, assessment, rows))
        }
    }
}
