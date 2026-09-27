package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataOriginalPublicationEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.MainSongReference
import com.zionhuang.innertube.models.SongItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class OriginalNamePublicationTest {
    private val target = OriginalNameTarget(OriginalNameKind.SONG, "abcdefghijk")
    private val original = ArtTrackOriginalName(target, "Something In The Way", target.id, "MPREalbum")
    private val aliases = listOf(alias("設定名", "ja"), alias(original.name, "en", "album-original-context"),
        alias("Something In The Way (Official Video)", "en", "detail"))

    @Test fun `completed original freezes the exact observed alias and its evidence`() {
        val rows = aliases + assessed(listOf(original))
        val publication = completed(rows).single()
        assertEquals(original.name, publication.englishName)
        assertEquals(original.name, display(rows, publication))
        assertEquals(200L, publication.evaluatedAt)
        assertTrue(publication.evidenceJson.contains("sourceSnapshots"))
        assertTrue(publication.evidenceJson.contains(original.sourceVideoId))
        assertEquals(publication, completed(rows, listOf(publication), 300).single())
    }

    @Test fun `changed candidate remains pending until a complete new verdict replaces the exact old name`() {
        val before = aliases + assessed(listOf(original))
        val previous = completed(before).single()
        val changed = original.copy(name = "A New Original Name")
        val pending = aliases + alias(changed.name, "en", "detail") + raw(changed, 300)
        assertNull(prepareOriginalPublications(pending, listOf(previous), 300))
        assertEquals(original.name, display(pending, previous))
        val finished = aliases + alias(changed.name, "en", "detail") + assessed(listOf(changed), at = 300)
        val next = completed(finished, listOf(previous), 400).single()
        assertEquals(changed.name, next.englishName)
        assertEquals(changed.name, display(finished, next))
        assertEquals(original.name, previous.englishName)
    }

    @Test fun `finished unknown and other remove the previous English decision`() {
        val previous = completed(aliases + assessed(listOf(original))).single()
        for (language in listOf(OriginalNameLanguage.UNKNOWN, OriginalNameLanguage.OTHER)) {
            val finished = aliases + assessed(listOf(original), language, 300)
            val next = completed(finished, listOf(previous), 400).single()
            assertNull(next.englishName)
            assertEquals("設定名", display(finished, next))
            assertTrue(next.evidenceJson.contains(language.name))
        }
    }

    @Test fun `completed removal of the last source updates previous targets even without current rows`() {
        val previous = completed(aliases + assessed(listOf(original))).single()
        val next = completed(emptyList(), listOf(previous), 300).single()
        assertEquals(target.id, next.targetId)
        assertNull(next.englishName)
        assertEquals("設定名", display(aliases, next))
    }

    @Test fun `first unassessed original and conflicting completed originals keep the configured name`() {
        assertNull(prepareOriginalPublications(aliases + raw(original), emptyList(), 200))
        assertEquals("設定名", display(aliases + raw(original), null))
        val conflicting = original.copy(name = "A Conflicting Name")
        val rows = aliases + alias(conflicting.name, "en") + assessed(listOf(original, conflicting))
        assertNull(completed(rows).single().englishName)
    }

    @Test fun `preference locale and manual names select immediately without changing the completed decision`() {
        val rows = aliases + alias("Titre configuré", "fr") + assessed(listOf(original))
        val publication = completed(rows).single()
        assertEquals("設定名", display(rows, publication, preferOriginal = false))
        assertEquals("Titre configuré", display(rows, publication, language = "fr", preferOriginal = false))
        assertEquals(original.name, display(rows, publication, language = "fr"))
        val manual = rows + alias("My manual title", "ja", "manual")
        assertEquals("My manual title", display(manual, publication))
        assertEquals(original.name, completed(manual, listOf(publication)).single().englishName)
        assertEquals(original.name, display(rows, publication))
    }

    @Test fun `an unrelated pending original cannot hide an already committed provider reference`() {
        val referencedTarget = OriginalNameTarget(OriginalNameKind.SONG, "lmnopqrstuv")
        val reference = providerSongReference(MainSongReference(target.id, referencedTarget.id), original,
            SongItem(referencedTarget.id, original.name, emptyList(), Album("Album", "MPREalbum"), thumbnail = ""))!!
        val referenceAliases = listOf(alias("参照先", "ja", id = referencedTarget.id),
            alias(original.name, "en", id = referencedTarget.id))
        val rows = aliases + referenceAliases + assessed(listOf(original)) + reference.toMetadataName(100)
        val previous = completed(rows)
        val committed = previous.single { it.targetId == referencedTarget.id }
        assertEquals(original.name, committed.englishName)
        val unrelated = original.copy(target = OriginalNameTarget(OriginalNameKind.SONG, "12345678901"),
            sourceVideoId = "12345678901", albumId = "MPREdifferent", name = "Another Original")
        val pending = rows + raw(unrelated, 300)
        assertNull(prepareOriginalPublications(pending, previous, 300))
        assertEquals(original.name, selectPublishedMetadataDisplayName(referencedTarget,
            referenceAliases, "ja", true, committed))
        val finished = aliases + referenceAliases + assessed(listOf(original, unrelated), at = 300) +
            reference.toMetadataName(100)
        assertEquals(original.name, completed(finished, previous, 400)
            .single { it.targetId == referencedTarget.id }.englishName)
    }

    @Test fun `a completed reference withdrawal is persisted instead of holding English forever`() {
        val referencedTarget = OriginalNameTarget(OriginalNameKind.SONG, "lmnopqrstuv")
        val reference = providerSongReference(MainSongReference(target.id, referencedTarget.id), original,
            SongItem(referencedTarget.id, original.name, emptyList(), Album("Album", "MPREalbum"), thumbnail = ""))!!
        val referenceAliases = listOf(alias("参照先", "ja", id = referencedTarget.id),
            alias(original.name, "en", id = referencedTarget.id))
        val initial = aliases + referenceAliases + assessed(listOf(original)) + reference.toMetadataName(100)
        val previous = completed(initial)
        val withdrawn = reference.toMetadataName(300).copy(originEvidenceJson = "{}")
        val next = completed(initial + withdrawn, previous, 400).single { it.targetId == referencedTarget.id }
        assertNull(next.englishName)
        assertEquals("参照先", selectPublishedMetadataDisplayName(referencedTarget,
            referenceAliases, "ja", true, next))
        assertTrue(next.evidenceJson.contains("relations"))
    }

    @Test fun `foreign publication and completed unknown cannot grant English to a pending candidate`() {
        val valid = completed(aliases + assessed(listOf(original))).single()
        assertEquals("設定名", display(aliases, valid.copy(targetId = "lmnopqrstuv")))
        val unknown = completed(aliases + assessed(listOf(original), OriginalNameLanguage.UNKNOWN)).single()
        val pending = aliases + raw(original, 400)
        assertEquals("設定名", display(pending, unknown))
        assertNull(prepareOriginalPublications(pending, listOf(unknown), 400))
    }

    @Test fun `shared targets retain complete source snapshots without unrelated sources or stale relations`() {
        val album = OriginalNameTarget(OriginalNameKind.ALBUM, "MPREalbum")
        val artist = OriginalNameTarget(OriginalNameKind.ARTIST, "UCshared")
        val second = original.copy(target = OriginalNameTarget(OriginalNameKind.SONG, "12345678901"),
            sourceVideoId = "12345678901", name = "Another Original Song")
        val unrelated = original.copy(target = OriginalNameTarget(OriginalNameKind.SONG, "ZZZZZZZZZZZ"),
            sourceVideoId = "ZZZZZZZZZZZ", name = "Unrelated Song", albumId = "MPREunrelated")
        val originals = listOf(original, second).flatMap { song -> listOf(song,
            song.copy(target = album, name = "Shared Album"),
            song.copy(target = artist, name = "Shared Artist")) } + unrelated
        val referenceTarget = "lmnopqrstuv"
        val reference = providerSongReference(MainSongReference(target.id, referenceTarget), original,
            SongItem(referenceTarget, original.name, emptyList(), Album("Album", album.id), thumbnail = ""))!!
        val rows = aliases + assessed(originals) + alias(original.name, "en", id = referenceTarget) +
            MetadataNameEntity("ALBUM", album.id, "en", "Shared Album", "detail", observedAt = 100) +
            MetadataNameEntity("ARTIST", artist.id, "en", "Shared Artist", "detail", observedAt = 100) +
            raw(original.copy(name = "Stale Source Title"), 50) + reference.toMetadataName(100)
        val publications = completed(rows)
        fun snapshots(id: String, values: List<MetadataOriginalPublicationEntity> = publications) =
            Json.parseToJsonElement(values.single { it.targetId == id }.evidenceJson).jsonObject
                .getValue("sourceSnapshots").jsonArray.map { it.jsonObject }
        fun snapshotKeys(id: String) = snapshots(id).map { row ->
            listOf("kind", "targetId", "source", "name").map { row.getValue(it).jsonPrimitive.content }
        }
        val bothSources = listOf(
            listOf("ALBUM", album.id, ORIGINAL_NAME_SOURCE_PREFIX + second.sourceVideoId, "Shared Album"),
            listOf("ALBUM", album.id, ORIGINAL_NAME_SOURCE_PREFIX + original.sourceVideoId, "Shared Album"),
            listOf("ARTIST", artist.id, ORIGINAL_NAME_SOURCE_PREFIX + second.sourceVideoId, "Shared Artist"),
            listOf("ARTIST", artist.id, ORIGINAL_NAME_SOURCE_PREFIX + original.sourceVideoId, "Shared Artist"),
            listOf("SONG", second.target.id, ORIGINAL_NAME_SOURCE_PREFIX + second.sourceVideoId, second.name),
            listOf("SONG", original.target.id, ORIGINAL_NAME_SOURCE_PREFIX + original.sourceVideoId, original.name),
        )
        assertEquals(bothSources, snapshotKeys(album.id))
        assertEquals(bothSources, snapshotKeys(artist.id))
        val firstSource = bothSources.filter { it[2] == ORIGINAL_NAME_SOURCE_PREFIX + original.sourceVideoId }
        assertEquals(firstSource, snapshotKeys(original.target.id))
        assertEquals(firstSource, snapshotKeys(referenceTarget))
        val expectedEvidence = rows.filter { it.observedAt == 100L && it.source == ORIGINAL_NAME_SOURCE_PREFIX + original.sourceVideoId }
            .associate { it.kind to it.originEvidenceJson }
        snapshots(referenceTarget).forEach { row ->
            assertEquals(expectedEvidence[row.getValue("kind").jsonPrimitive.content],
                row.getValue("evidence").jsonPrimitive.content)
        }
        assertEquals(publications, completed(rows.reversed(), publications, 300))

        val withdrawn = completed(rows + reference.toMetadataName(300).copy(originEvidenceJson = "{}"), publications, 400)
        assertTrue(snapshots(referenceTarget, withdrawn).isEmpty())
        val withdrawnEvidence = Json.parseToJsonElement(withdrawn.single { it.targetId == referenceTarget }.evidenceJson).jsonObject
        assertEquals("{}", withdrawnEvidence.getValue("relations").jsonArray.single().jsonObject
            .getValue("evidence").jsonPrimitive.content)
    }

    private fun display(rows: List<MetadataNameEntity>, publication: MetadataOriginalPublicationEntity?,
        language: String = "ja", preferOriginal: Boolean = true) =
        selectPublishedMetadataDisplayName(target, rows, language, preferOriginal, publication)

    private fun completed(rows: List<MetadataNameEntity>, previous: List<MetadataOriginalPublicationEntity> = emptyList(),
        at: Long = 200) = requireNotNull(prepareOriginalPublications(rows, previous, at))

    private fun alias(name: String, language: String, source: String = "detail", id: String = target.id) =
        MetadataNameEntity("SONG", id, language, name, source, observedAt = 100)

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
                OriginalAlbumLanguageResolver.METHOD_VERSION + "/individual-${language.name.lowercase()}",
                "fixture-${candidate.name}", at)
            rows[index].copy(originEvidenceJson = encodeOriginalAssessment(candidate, assessment, rows))
        }
    }
}
