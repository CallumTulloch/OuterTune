package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
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
import com.dd3boh.outertune.utils.createDatabaseSnapshot
import com.dd3boh.outertune.utils.createBackupArchive
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real DB, repository and language model, isolated from app data and live metadata acquisition. */
class MetadataLanguageIntegrationTest {
    @Test fun savedNamesFollowPreferenceAndRemainSearchableWithoutReplacingRawMetadata(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            verifyNames(fixture)
        } finally {
            fixture.close()
        }
    }

    private suspend fun verifyNames(fixture: Fixture) {
        val database = fixture.database
        val songId = "ljUtuoFt-8c"
        val artistId = "UCrPe3hLA51968GwxHSZ1llw"
        val albumId = "MPREb_jPOYfjGgApr"
        val jaTitle = "スメルズ・ライク・ティーン・スピリット"
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
        fixture.start()
        suspend fun awaitNames(title: String, artist: String) = withTimeout(15_000) {
            fixture.names.first { names ->
                names[OriginalNameTarget(OriginalNameKind.SONG, songId)] == title &&
                    names[OriginalNameTarget(OriginalNameKind.ARTIST, artistId)] == artist
            }
        }
        awaitNames(jaTitle, "ニルヴァーナ")
        fixture.preferEnglish(true)
        awaitNames("Smells Like Teen Spirit", "Nirvana")
        withTimeout(15_000) {
            fixture.names.first { names -> albumTitles.all { (id, title) ->
                names[OriginalNameTarget(OriginalNameKind.SONG, id)] == title
            } }
        }
        assertTrue(database.searchSongs("スメルズ").first().any { it.id == songId })
        assertTrue(database.searchSongs("Smells").first().any { it.id == songId })
        assertTrue(database.searchArtistSongs("ニルヴァーナ").first().any { it.id == songId })
        assertTrue(database.searchArtistSongs("nirvan").first().any { it.id == songId })
        fixture.preferEnglish(false)
        awaitNames(jaTitle, "ニルヴァーナ")
        assertEquals("Smells Like Teen Spirit", database.songForArtistCredit(songId)!!.title)
        assertEquals(2, database.metadataNames("SONG", songId).filter { it.source == "detail" }.size)
        fixture.exportIfRequested()
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val export = InstrumentationRegistry.getArguments().getString("exportLocaleFixture") == "true"
        private val databaseName = "metadata-language-test-${UUID.randomUUID()}.db"
        private val internal = if (export) {
            Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build()
        } else {
            Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        }
        val database = MusicDatabase(internal)
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.IO)
        private val locale = YouTubeLocale("JP", "ja")
        private val settings = MutableStateFlow(preferences(preferOriginal = false))
        val names = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())

        fun start() {
            MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = scope, preferences = settings,
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L),
                contextKey = { "metadata-language-isolated" }, observeMetadata = {},
                publishNames = { selected, _ -> names.value = selected },
                queue = { _, _ -> Result.success(emptyList()) },
                album = { _, _ -> Result.failure(IOException("No live album fetch in this fixture")) },
                albumContext = { _, _ -> Result.success(emptyList()) },
                artist = { _, _ -> Result.failure(IOException("No live artist fetch in this fixture")) },
                main = { _, _ -> Result.failure(IOException("No live Main fetch in this fixture")) },
                // Use the production classifier to assess the raw originals seeded above.
            )).start()
        }

        fun preferEnglish(enabled: Boolean) { settings.value = preferences(enabled) }

        /** Explicit UI-fixture export never reads or alters the target application's DB/settings. */
        suspend fun exportIfRequested() {
            if (!export) return
            val snapshot = File(context.cacheDir, "$databaseName.snapshot")
            val preferencesFile = File(context.cacheDir, "$databaseName.preferences_pb")
            val preferencesJob = SupervisorJob()
            try {
                val store = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(preferencesJob + Dispatchers.IO), produceFile = { preferencesFile },
                )
                store.edit {
                    it[ContentCountryKey] = "JP"
                    it[ContentLanguageKey] = "ja"
                    it[PreferEnglishOriginalKey] = settings.value[PreferEnglishOriginalKey] ?: false
                    it[OobeStatusKey] = OOBE_VERSION
                }
                createDatabaseSnapshot(requireNotNull(database.openHelper.writableDatabase.path), snapshot)
                createBackupArchive(preferencesFile, snapshot,
                    File(context.getExternalFilesDir(null), "content-locale-fixture.backup"))
            } finally {
                preferencesJob.cancelAndJoin()
                preferencesFile.delete()
                snapshot.delete()
            }
        }

        suspend fun close() {
            job.cancelAndJoin()
            database.close()
            if (export) context.deleteDatabase(databaseName)
        }

        private fun preferences(preferOriginal: Boolean) = preferencesOf(
            ContentCountryKey to "JP", ContentLanguageKey to "ja", PreferEnglishOriginalKey to preferOriginal,
        )
    }
}
