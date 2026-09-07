package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Runs on a disposable test application. Exercises the real DB -> repository -> display subscription. */
class MetadataLanguageIntegrationTest {
    @Test fun savedNamesFollowPreferenceAndRemainSearchableWithoutReplacingRawMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = EntryPointAccessors.fromApplication(context, MetadataLanguageTestEntryPoint::class.java).database()
        val songId = "ljUtuoFt-8c"
        val artistId = "UCrPe3hLA51968GwxHSZ1llw"
        val albumId = "MPREb_jPOYfjGgApr"
        val jaTitle = "スメルズ・ライク・ティーン・スピリット"
        context.dataStore.edit {
            it[ContentLanguageKey] = "ja"
            it.remove(PreferEnglishOriginalKey)
            it[OobeStatusKey] = OOBE_VERSION
        }
        database.insert(SongEntity(songId, "Smells Like Teen Spirit", localPath = null,
            albumId = albumId, albumName = "Nevermind", inLibrary = LocalDateTime.now()))
        database.insert(ArtistEntity(artistId, "Nirvana"))
        database.insert(SongArtistMap(songId, artistId, 0))
        database.insert(AlbumEntity(albumId, title = "Nevermind", songCount = 1, duration = 301,
            bookmarkedAt = LocalDateTime.now()))
        database.insert(SongAlbumMap(songId, albumId, 0))
        database.insert(AlbumArtistMap(albumId, artistId, 0))
        database.recordMetadataNames(listOf(
            MetadataNameEntity("SONG", songId, "en", "Smells Like Teen Spirit", "detail", 100),
            MetadataNameEntity("SONG", songId, "ja", jaTitle, "detail", 100),
            MetadataNameEntity("ARTIST", artistId, "en", "Nirvana", "detail", 100),
            MetadataNameEntity("ARTIST", artistId, "ja", "ニルヴァーナ", "detail", 100),
            MetadataNameEntity("ALBUM", albumId, "en", "Nevermind", "detail", 100),
            MetadataNameEntity("ALBUM", albumId, "ja", "Nevermind", "detail", 100),
        ))
        // Raw source fixtures, not pre-approved IDs or injected language verdicts. The real
        // repository classifies the linked album's originals and persists its assessments.
        val albumTitles = listOf(
            "ljUtuoFt-8c" to "Smells Like Teen Spirit", "ng_vqlKtxLs" to "In Bloom",
            "ZEMBDKMtHqM" to "Come As You Are", "ox_BG6sLPq8" to "Breed",
            "_oWUgfpGi0M" to "Lithium", "h5cZvNWxnho" to "Polly",
            "W64Bz9wXvRs" to "Territorial Pissings", "jFU6xiWbHT0" to "Drain You",
            "jwcq2Ci6jx0" to "Lounge Act", "UGA6zBEyo8Y" to "Stay Away",
            "INFH5bt8hxM" to "On A Plain", "SVSjcS-N224" to "Something In The Way",
            "MCBzJ2vjYyg" to "Endless, Nameless",
        )
        val japaneseTitles = listOf(jaTitle, "イン・ブルーム", "カム・アズ・ユー・アー", "ブリード",
            "リチウム", "ポーリー", "テリトリアル・ピッシングス", "ドレイン・ユー", "ラウンジ・アクト",
            "ステイ・アウェイ", "オン・ア・プレイン", "サムシング・イン・ザ・ウェイ～", "Endless, Nameless")
        database.recordMetadataNames(albumTitles.mapIndexed { index, (id, _) ->
            MetadataNameEntity("SONG", id, "ja", japaneseTitles[index], "detail", 100)
        })
        val originals = albumTitles.map { (id, title) -> ArtTrackOriginalName(
            OriginalNameTarget(OriginalNameKind.SONG, id), title, id, albumId,
        ) } + listOf(
            ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.ARTIST, artistId), "Nirvana", songId, albumId),
            ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.ALBUM, albumId), "Nevermind", songId, albumId),
        )
        // Like captureOriginalTitle, all names from the same fetched snapshot share one timestamp.
        val originalObservedAt = System.currentTimeMillis()
        database.recordMetadataNames(originals.flatMap { candidate -> listOf(
            MetadataNameEntity(candidate.target.kind.name, candidate.target.id, "en", candidate.name, "detail", 100),
            MetadataNameEntity(candidate.target.kind.name, candidate.target.id, "und", candidate.name,
                ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, 10, observedAt = originalObservedAt,
                originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate)),
        ) })
        suspend fun awaitNames(title: String, artist: String) = withTimeout(15_000) {
            while (MetadataNames.resolve(OriginalNameKind.SONG, songId, "") != title ||
                MetadataNames.resolve(OriginalNameKind.ARTIST, artistId, "") != artist) delay(25)
        }
        awaitNames(jaTitle, "ニルヴァーナ")
        context.dataStore.edit { it[PreferEnglishOriginalKey] = true }
        awaitNames("Smells Like Teen Spirit", "Nirvana")
        for ((id, title) in albumTitles) {
            assertEquals(title, MetadataNames.resolve(OriginalNameKind.SONG, id, ""))
        }
        assertTrue(database.searchSongs("スメルズ").first().any { it.id == songId })
        assertTrue(database.searchSongs("Smells").first().any { it.id == songId })
        assertTrue(database.searchArtistSongs("ニルヴァーナ").first().any { it.id == songId })
        assertTrue(database.searchArtistSongs("nirvan").first().any { it.id == songId })
        context.dataStore.edit { it[PreferEnglishOriginalKey] = false }
        awaitNames(jaTitle, "ニルヴァーナ")
        assertEquals("Smells Like Teen Spirit", database.songForArtistCredit(songId)!!.title)
        assertEquals(2, database.metadataNames("SONG", songId).filter { it.source == "detail" }.size)
        // Keep this record available for the subsequent process-restart/UI check in the isolated emulator.
    }
}
