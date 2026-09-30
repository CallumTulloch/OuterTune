package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.MainSongReference
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.*
import org.junit.Test

class OriginalPublicationInputsTest {
    private val album = OriginalNameTarget(OriginalNameKind.ALBUM, "MPREalbum")
    private val artist = OriginalNameTarget(OriginalNameKind.ARTIST, "UCshared")

    @Test fun `one snapshot shares complete source and album sets across many decisions`() {
        val songs = (0 until 48).map(::song)
        val originals = songs.flatMap { listOf(it, it.copy(target = album, name = "Shared Album"),
            it.copy(target = artist, name = "Shared Artist")) }
        val references = (0 until 64).map { providerReference(songs.first(), "ref%08d".format(it)) }
        val rows = originals.map { raw(it) } + references
        val inputs = OriginalPublicationInputs(rows)
        val first = inputs.forTarget(songs.first().target)

        assertEquals(originals.take(3).map { raw(it) }.toSet(), first.sourceRows)
        assertEquals(songs.toSet(), first.albumSongs)
        for (reference in references) {
            val target = OriginalNameTarget(OriginalNameKind.SONG, reference.targetId)
            val input = inputs.forTarget(target)
            assertEquals(setOf(reference), input.targetRows)
            // Allocation regression: every reference shares the same source snapshot and the
            // same 48-song context, instead of building 64 identical album-sized HashSets.
            assertSame(first.sourceRows, input.sourceRows)
            assertSame(first.albumSongs, input.albumSongs)
            assertFalse(inputs.ready(target))
            assertSame(input, inputs.forTarget(target))
        }
        for (target in songs.map { it.target } + album + artist) {
            assertSame(first.albumSongs, inputs.forTarget(target).albumSongs)
        }
        assertEquals(rows.filter { it.source.startsWith(ORIGINAL_NAME_SOURCE_PREFIX) }.toSet(),
            inputs.forTarget(artist).sourceRows)
        assertSame(first, inputs.forTarget(songs.first().target))
    }

    @Test fun `a new snapshot invalidates readiness for changed album context but not unrelated albums`() {
        val originals = listOf(song(0), song(1))
        val rows = assessed(originals)
        val initial = OriginalPublicationInputs(rows)
        val target = originals.first().target
        val before = initial.forTarget(target)
        assertTrue(initial.ready(target))
        val extra = song(2)

        val unrelated = OriginalPublicationInputs(rows + raw(extra.copy(albumId = "MPREdifferent")))
        assertEquals(before, unrelated.forTarget(target))
        assertTrue(unrelated.ready(target))

        val pending = OriginalPublicationInputs(rows + raw(extra, 200))
        assertNotEquals(before, pending.forTarget(target))
        assertEquals((originals + extra).toSet(), pending.forTarget(target).albumSongs)
        assertFalse(pending.ready(target))
        assertFalse(pending.ready(target))
        assertSame(before, initial.forTarget(target))
        assertTrue(initial.ready(target))

        val finished = OriginalPublicationInputs(assessed(originals + extra, 300))
        assertTrue(finished.ready(target))
        assertNotEquals(pending.forTarget(target), finished.forTarget(target))
    }

    @Test fun `latest withdrawals suppress provider and playlist references without losing equal time evidence`() {
        val first = song(0)
        val second = song(1)
        val target = OriginalNameTarget(OriginalNameKind.SONG, "reference01")
        val provider = providerReference(first, target.id)
        val playlist = playlistReference(second, target.id)
        val originals = listOf(raw(first), raw(second))
        val initial = OriginalPublicationInputs(originals + provider + playlist)
        assertEquals(originals.toSet(), initial.forTarget(target).sourceRows)
        assertFalse(initial.ready(target))

        val withdrawnProvider = provider.copy(observedAt = 200, originEvidenceJson = "{}")
        val remaining = OriginalPublicationInputs(originals + provider + playlist + withdrawnProvider)
        assertEquals(setOf(raw(second)), remaining.forTarget(target).sourceRows)
        assertFalse(remaining.ready(target))

        val withdrawnPlaylist = playlist.copy(observedAt = 200, originEvidenceJson = "{}")
        val rows = originals + provider + playlist + withdrawnProvider + withdrawnPlaylist
        val withdrawn = OriginalPublicationInputs(rows)
        assertTrue(withdrawn.forTarget(target).sourceRows.isEmpty())
        assertTrue(withdrawn.forTarget(target).albumSongs.isEmpty())
        assertTrue(withdrawn.ready(target))
        assertNotEquals(initial.forTarget(target), withdrawn.forTarget(target))

        val tiedRows = rows + provider.copy(observedAt = 200)
        val tied = OriginalPublicationInputs(tiedRows)
        assertEquals(setOf(raw(first)), tied.forTarget(target).sourceRows)
        assertFalse(tied.ready(target))
        assertEquals(tied.forTarget(target), OriginalPublicationInputs(tiedRows.reversed()).forTarget(target))
    }

    @Test fun `removing a source after caching inputs still withdraws its published reference`() {
        val original = song(0)
        val target = OriginalNameTarget(OriginalNameKind.SONG, "reference01")
        val reference = providerReference(original, target.id)
        val alias = MetadataNameEntity("SONG", target.id, "en", original.name, "detail", observedAt = 100)
        val rows = assessed(listOf(original)) + reference + alias
        val initial = OriginalPublicationInputs(rows)
        val before = initial.forTarget(target)
        assertTrue(initial.ready(target))
        val previous = prepareOriginalPublications(rows, emptyList(), 200)
        assertEquals(original.name, previous.single { it.targetId == target.id }.englishName)

        val removed = listOf(reference, alias)
        val current = OriginalPublicationInputs(removed)
        assertTrue(current.ready(target))
        assertTrue(current.forTarget(target).sourceRows.isEmpty())
        assertNotEquals(before, current.forTarget(target))
        assertSame(before, initial.forTarget(target))
        assertNull(prepareOriginalPublications(removed, previous, 300)
            .single { it.targetId == target.id }.englishName)
    }

    @Test fun `shared artist keeps complete source unions and detects a newer snapshot dropping its artist link`() {
        val first = song(0)
        val second = song(1).copy(albumId = "MPREother")
        val artistRows = listOf(first.copy(target = artist, name = "Shared Artist"),
            second.copy(target = artist, name = "Shared Artist"))
        val originals = listOf(first, second) + artistRows
        val rows = originals.map { raw(it) }
        val initial = OriginalPublicationInputs(rows)
        val before = initial.forTarget(artist)
        assertEquals(rows.toSet(), before.sourceRows)
        assertEquals(setOf(first, second), before.albumSongs)

        // A newer song row dates the whole source snapshot. Retained historical artist aliases
        // must not keep the first source or its album in this artist's current dependency set.
        val current = OriginalPublicationInputs(rows + raw(first, 200))
        val after = current.forTarget(artist)
        assertEquals(setOf(raw(second), raw(artistRows.last())), after.sourceRows)
        assertEquals(setOf(second), after.albumSongs)
        assertNotEquals(before, after)
        assertSame(before, initial.forTarget(artist))
    }

    private fun song(index: Int): ArtTrackOriginalName {
        val id = "song%07d".format(index)
        return ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id),
            "Original Song $index", id, album.id)
    }

    private fun providerReference(source: ArtTrackOriginalName, targetId: String): MetadataNameEntity =
        requireNotNull(providerSongReference(MainSongReference(source.sourceVideoId, targetId), source,
            SongItem(targetId, source.name, emptyList(), Album("Album", source.albumId!!), thumbnail = "")))
            .toMetadataName(100)

    private fun playlistReference(source: ArtTrackOriginalName, targetId: String): MetadataNameEntity {
        val playlistId = "OLAK5uy_playlist"
        return requireNotNull(playlistSongReference(
            PlaylistSongReference(playlistId, "playlistEntry", source.sourceVideoId, targetId), source,
            SongItem(targetId, source.name, emptyList(), Album("Album", source.albumId!!), thumbnail = ""), playlistId))
            .toMetadataName(100)
    }

    private fun raw(candidate: ArtTrackOriginalName, at: Long = 100) = MetadataNameEntity(
        candidate.target.kind.name, candidate.target.id, "und", candidate.name,
        ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, observedAt = at,
        originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate),
    )

    private fun assessed(candidates: List<ArtTrackOriginalName>, at: Long = 100): List<MetadataNameEntity> {
        val rows = candidates.map { raw(it, at) }
        val inputs = originalAssessmentInputs(rows)
        return candidates.mapIndexed { index, candidate ->
            val assessment = OriginalNameAssessment(candidate.target, candidate.name, candidate.sourceVideoId,
                "https://www.youtube.com/watch?v=${candidate.sourceVideoId}", OriginalNameLanguage.ENGLISH, 0.99f,
                OriginalAlbumLanguageResolver.METHOD_VERSION + "/individual-english", "fixture-${candidate.name}", at)
            rows[index].copy(originEvidenceJson = encodeOriginalAssessment(candidate, assessment, inputs))
        }
    }
}
