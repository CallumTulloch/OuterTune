package com.dd3boh.outertune.repositories

import android.os.Bundle
import android.os.SystemClock
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.models.toStoredJson
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessmentCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.MainSongReference
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.File
import java.text.Normalizer
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicit live corpus capture, with the real APIs/model and a separate in-memory Room database.
 * No singleton observer, published UI names, application preferences or application DB are changed.
 * PASS checks completed selection invariants, not that every remote original was obtainable.
 * Review each report's coverage and per-track observations before claiming whole-album coverage.
 *
 * Runner arguments: liveMetadataLanguageCorpus=true, optional albumId, corpusCountry=JP,
 * corpusWaitSeconds=240. Reports: files/live-metadata-language-corpus/<run>/<albumId>.json.
 * The matching .db is a consistent VACUUM INTO snapshot for a separate UI verification restore.
 */
class LiveMetadataLanguageCorpusTest {
    @Test(timeout = 900_000)
    fun captureLiveAlbumsAndVerifyCompletedSelections(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in required for real network corpus capture",
            arguments.getString("liveMetadataLanguageCorpus") == "true")
        val requested = arguments.getString("albumId") ?: arguments.getString("albumQuery")?.let { query ->
            val title = requireNotNull(arguments.getString("expectedAlbumTitle"))
            val artist = requireNotNull(arguments.getString("expectedAlbumArtist"))
            val matches = YouTube.search(query, YouTube.SearchFilter.FILTER_ALBUM,
                YouTubeLocale("JP", "en"), notifyMetadata = false).getOrThrow().items.filterIsInstance<AlbumItem>()
                .filter { it.title.equals(title, ignoreCase = true) &&
                    it.artists.orEmpty().any { credit -> credit.name.equals(artist, ignoreCase = true) } }
            require(matches.isNotEmpty()) { "No exact album title/artist in observed search results" }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "live-corpus-search:${matches.joinToString { it.id + ":" + it.title }}; " +
                    "using first observed matching edition\n")
            })
            matches.first().id
        }
        val albums = requested?.let { listOf(it) } ?: listOf(ABBEY_ROAD, TRIGGER)
        require(albums.all { Regex("[A-Za-z0-9_-]{1,100}").matches(it) })
        val country = arguments.getString("corpusCountry") ?: "JP"
        require(Regex("[A-Z]{2}").matches(country))
        val waitSeconds = arguments.getString("corpusWaitSeconds")?.toLong() ?: 240L
        require(waitSeconds in 30L..270L)
        val directory = File(instrumentation.targetContext.filesDir,
            "live-metadata-language-corpus/${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val failures = mutableListOf<String>()
        for (albumId in albums) {
            val report = captureAlbum(albumId, YouTubeLocale(country, "ja"), waitSeconds, File(directory, "$albumId.db"))
            File(directory, "$albumId.json").writeText(report.toString(2))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "live-corpus-report:${directory.name}/$albumId.json " +
                    "status=${report.optString("validationStatus")} " +
                    "tracks=${report.optInt("trackCount")}\n")
            })
            if (report.optString("validationStatus") != "PASS") {
                failures += "$albumId:${report.optString("stage")}:${report.optString("failureClass")}" +
                    report.optJSONArray("violations").toString()
            }
        }
        assertTrue("Live corpus invariant checks failed; inspect private reports: $failures", failures.isEmpty())
    }

    private suspend fun captureAlbum(albumId: String, locale: YouTubeLocale, waitSeconds: Long, snapshot: File): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
        val job = SupervisorJob()
        val settings = MutableStateFlow(preferencesOf(ContentCountryKey to locale.gl,
            ContentLanguageKey to locale.hl, PreferEnglishOriginalKey to false))
        val selected = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val history = CopyOnWriteArrayList<JSONObject>()
        val report = JSONObject().put("albumId", albumId).put("country", locale.gl)
            .put("configuredLanguage", locale.hl).put("startedAt", System.currentTimeMillis())
            .put("validationStatus", "INCOMPLETE").put("stage", "fetch-configured-album")
            .put("scope", "Live metadata only; no screen or playback verification")
        val violations = mutableListOf<String>()
        var songs = emptyList<SongItem>()
        var off = emptyMap<String, String>()
        var on = emptyMap<String, String>()
        var offAgain = emptyMap<String, String>()
        var stage = "fetch-configured-album"
        try {
            val page = withTimeout(45_000) {
                YouTube.album(albumId, withSongs = true, requestLocale = locale, notifyMetadata = false).getOrThrow()
            }
            check(page.album.id == albumId && page.songs.isNotEmpty())
            songs = page.songs
            check(songs.map { it.id }.distinct().size == songs.size) { "Duplicate album playback IDs" }
            report.put("albumTitle", page.album.title).put("playlistId", page.album.playlistId ?: JSONObject.NULL)
                .put("trackCount", songs.size)
            if (albumId == ABBEY_ROAD && songs.size != 40) violations += "Expected the recorded 40-track Abbey Road edition"
            val initialSongs = songs.map { song -> SongEntity(song.id, song.title,
                duration = song.duration ?: -1, thumbnailUrl = song.thumbnail, localPath = null,
                albumId = albumId, albumName = page.album.title, artistCreditJson = song.artistCredit?.toStoredJson()) }
            database.awaitTransaction {
                insert(AlbumEntity(albumId, playlistId = page.album.playlistId, title = page.album.title,
                    songCount = songs.size, duration = songs.sumOf { it.duration ?: 0 }, hasTrackList = true,
                    thumbnailUrl = page.album.thumbnail, artistCreditJson = page.album.artistCredit?.toStoredJson(),
                    bookmarkedAt = LocalDateTime.now()))
                // Preserve only the real page's separately identified credits. An absent ID is
                // not permission to invent a person, merge names, or copy album credits to songs.
                page.album.artists.orEmpty().filter { !it.id.isNullOrBlank() }.distinctBy { it.id }
                    .forEachIndexed { index, artist ->
                        val id = requireNotNull(artist.id)
                        insert(ArtistEntity(id, artist.name))
                        insert(AlbumArtistMap(albumId, id, index))
                    }
                initialSongs.forEachIndexed { index, song ->
                    insert(song)
                    insert(SongAlbumMap(song.id, albumId, index))
                    songs[index].artists.filter { !it.id.isNullOrBlank() }.distinctBy { it.id }
                        .forEachIndexed { position, artist ->
                            val id = requireNotNull(artist.id)
                            insert(ArtistEntity(id, artist.name))
                            insert(SongArtistMap(song.id, id, position))
                        }
                }
            }
            var observer: ((List<YTItem>, YouTubeLocale, String) -> Unit)? = null
            val repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), preferences = settings,
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                observeMetadata = { observer = it },
                publishNames = { names, _ ->
                    selected.value = names
                    if (history.size < 2_000) history += JSONObject()
                        .put("at", System.currentTimeMillis())
                        .put("requestedPreferOriginal", settings.value[PreferEnglishOriginalKey] == true)
                        .put("songs", JSONObject(displayed(songs, names)))
                },
                // These are the same live API and bundled classifier as the application runtime.
                albumPage = { id, requestLocale -> YouTube.album(id, withSongs = true,
                    requestLocale = requestLocale, notifyMetadata = false) },
                playlistReferences = { id, requestLocale -> YouTube.playlistSongReferences(id, requestLocale) },
                assessOriginals = OriginalAlbumLanguageResolver()::assess,
            ))
            repository.start()
            repository.setForegroundAlbum(albumId, active = true)
            // Feed the actual fetched page through the normal observation path, without touching
            // YouTube.metadataObserver or making up names, references, originals or verdicts.
            requireNotNull(observer).invoke(listOf(page.album) + songs, locale, "album")
            stage = "await-live-acquisition-and-assessment"
            val settled = withTimeoutOrNull(waitSeconds * 1_000) {
                var idleSince: Long? = null
                while (true) {
                    val rows = database.metadataNameSnapshot()
                    val publications = database.metadataOriginalPublicationSnapshot()
                    val originalsAttempted = database.metadataFetchStates("ALBUM", albumId).any { it.language == "und" }
                    val completed = prepareOriginalPublications(rows, publications, System.currentTimeMillis())
                    val ready = originalsAttempted && repository.pendingRequestCount == 0 &&
                        repository.initialized.value && completed != null && completed.toSet() == publications.toSet()
                    if (ready) {
                        val now = SystemClock.elapsedRealtime()
                        if (idleSince == null) idleSince = now
                        if (now - requireNotNull(idleSince) >= 1_000) break
                    } else idleSince = null
                    delay(100)
                }
                true
            } ?: false
            report.put("settled", settled).put("pendingRequests", repository.pendingRequestCount)
            check(settled) { "Live acquisition did not settle within the requested observation window" }
            val rows = database.metadataNameSnapshot()
            val publications = database.metadataOriginalPublicationSnapshot().associateBy { it.kind to it.targetId }
            val assessments = originalAssessmentsByTarget(rows)
            // Independently validate observed Main strings against current direct source inputs
            // and decoded reference identities. A committed publication alone is not proof.
            val observedOriginals = independentlyObservedEnglishOriginals(rows)
            suspend fun select(prefer: Boolean): Map<String, String> {
                settings.value = preferencesOf(ContentCountryKey to locale.gl,
                    ContentLanguageKey to locale.hl, PreferEnglishOriginalKey to prefer)
                val expected = songs.associate { song ->
                    val target = target(song.id)
                    val candidates = rows.filter { it.kind == "SONG" && it.targetId == song.id }
                    song.id to (selectPublishedMetadataDisplayName(target, candidates, locale.hl,
                        prefer, publications["SONG" to song.id]) ?: song.title)
                }
                // Await the real asynchronous publication. The assertions below independently
                // check observed-language membership and the committed English decision.
                withTimeout(15_000) { selected.first { displayed(songs, it) == expected } }
                return displayed(songs, selected.value)
            }
            stage = "off-on-off-and-data-invariants"
            off = select(false)
            on = select(true)
            offAgain = select(false)
            songs.forEach { song ->
                val candidates = rows.filter { it.kind == "SONG" && it.targetId == song.id }
                val configured = candidates.filter { it.language == locale.hl }.map { it.name }.toSet()
                val english = candidates.filter { it.language == "en" }.map { it.name }.toSet()
                val observedEnglish = english + observedOriginals[song.id].orEmpty()
                val publication = publications["SONG" to song.id]
                val evidence = assessments[target(song.id)].orEmpty()
                val originalNames = evidence.map { comparable(it.originalName) }.distinct()
                val supportedEnglish = evidence.any { it.language == OriginalNameLanguage.ENGLISH } &&
                    evidence.none { it.language == OriginalNameLanguage.OTHER } && originalNames.size == 1
                if (off[song.id] !in configured.ifEmpty { setOf(song.title) })
                    violations += "${song.id}: OFF did not use an observed configured-language name or raw fallback"
                if (off[song.id] != offAgain[song.id]) violations += "${song.id}: OFF did not restore the original selection"
                if (publication?.englishName != null) {
                    if (on[song.id] != publication.englishName || publication.englishName !in observedEnglish)
                        violations += "${song.id}: ON did not use its exact committed, observed English alias or verified Main original"
                    if (!supportedEnglish || comparable(publication.englishName) != originalNames.singleOrNull())
                        violations += "${song.id}: English publication lacks compatible original-name evidence"
                } else if (on[song.id] != off[song.id]) {
                    violations += "${song.id}: an unconfirmed original changed the configured selection"
                }
                if (evidence.isNotEmpty() && evidence.all { it.language == OriginalNameLanguage.OTHER } &&
                    on[song.id] != off[song.id]) violations += "${song.id}: non-English original replaced configured text"
                if (supportedEnglish && observedEnglish.any { comparable(it) == originalNames.single() } &&
                    publication?.englishName == null) violations += "${song.id}: completed English original was not published"
                // A few unambiguous recorded English phrases check language decisions separately
                // from selection. Short titles and unobserved originals have no forced verdict.
                if (albumId == ABBEY_ROAD) evidence.filter {
                    it.originalName.substringBefore(" (") in CLEAR_ENGLISH_PHRASES
                }.forEach { if (it.language != OriginalNameLanguage.ENGLISH)
                    violations += "${song.id}: clear English phrase was ${it.language}: ${it.originalName}" }
            }
            if (songs.map { it.id } != database.albumSongs(albumId).first().map { it.id })
                violations += "Album playback IDs or track order changed"
            if (initialSongs != songs.map { database.songForArtistCredit(it.id) }) violations += "Raw song metadata changed"
            val storedIds = database.openHelper.readableDatabase.query("SELECT id FROM song").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            if (storedIds != songs.map { it.id }.toSet()) violations += "Reference discovery inserted or removed playback IDs"
            report.put("validationStatus", if (violations.isEmpty()) "PASS" else "FAIL")
        } catch (failure: Throwable) {
            // Do not serialize HTTP messages/causes, which can contain request or auth details.
            report.put("validationStatus", "FAIL").put("failureClass", failure.javaClass.simpleName)
        } finally {
            job.cancelAndJoin()
            report.put("stage", stage).put("finishedAt", System.currentTimeMillis())
                .put("violations", JSONArray(violations)).put("publicationHistory", JSONArray(history))
            val rows = database.metadataNameSnapshot()
            val publications = database.metadataOriginalPublicationSnapshot().associateBy { it.kind to it.targetId }
            val assessments = originalAssessmentsByTarget(rows)
            val confirmedTrackCount = songs.count { song -> assessments[target(song.id)].orEmpty().let { evidence ->
                evidence.map { it.language }.filter { it != OriginalNameLanguage.UNKNOWN }.distinct().size == 1 &&
                    evidence.map { comparable(it.originalName) }.distinct().size == 1
            } }
            val albumAcquisitionComplete = database.metadataFetchStates("ALBUM", albumId).any {
                it.language == "und" && it.contextKey.startsWith("album-original-context:") && it.status == "SUCCESS"
            }
            report.put("coverageStatus", if (songs.isNotEmpty() && confirmedTrackCount == songs.size &&
                albumAcquisitionComplete) "COMPLETE" else "INCOMPLETE")
            report.put("tracks", JSONArray(songs.mapIndexed { index, song ->
                val evidence = assessments[target(song.id)].orEmpty()
                val publication = publications["SONG" to song.id]
                JSONObject().put("index", index).put("id", song.id).put("rawTitle", song.title)
                    .put("off", off[song.id] ?: JSONObject.NULL).put("on", on[song.id] ?: JSONObject.NULL)
                    .put("offAgain", offAgain[song.id] ?: JSONObject.NULL)
                    .put("committedEnglish", publication?.englishName ?: JSONObject.NULL)
                    .put("publicationEvidence", publication?.evidenceJson?.let(::JSONObject) ?: JSONObject.NULL)
                    .put("observedNames", JSONArray(rows.filter { it.kind == "SONG" && it.targetId == song.id }.map(::nameJson)))
                    .put("originalAssessments", JSONArray(evidence.map { assessment -> JSONObject()
                        .put("originalName", assessment.originalName).put("sourceVideoId", assessment.sourceVideoId)
                        .put("language", assessment.language.name).put("confidence", assessment.confidence.toDouble())
                        .put("method", assessment.method).put("evaluatedAt", assessment.evaluatedAt) }))
                    .put("fetchStates", fetchStates(database, "SONG", song.id))
                    .put("sourceFetchStates", JSONArray(evidence.map { it.sourceVideoId }.distinct().map { id ->
                        JSONObject().put("id", id).put("states", fetchStates(database, "SONG", id))
                    }))
            }))
            report.put("albumFetchStates", fetchStates(database, "ALBUM", albumId))
                .put("finalAlbumTrackIds", JSONArray(database.albumSongs(albumId).first().map { it.id }))
                .put("allObservedOriginals", JSONArray(latestOriginalRows(rows).map(::nameJson)))
                .put("coverage", JSONObject()
                    .put("trackCount", songs.size)
                    .put("withSingleKnownOriginal", confirmedTrackCount)
                    .put("albumAcquisitionComplete", albumAcquisitionComplete)
                    .put("withOriginalAssessment", songs.count { assessments[target(it.id)].orEmpty().isNotEmpty() })
                    .put("withCommittedEnglish", songs.count { publications["SONG" to it.id]?.englishName != null })
                    .put("withOnlyEnglishAssessment", songs.count { song -> assessments[target(song.id)].orEmpty().let {
                        it.isNotEmpty() && it.all { assessment -> assessment.language == OriginalNameLanguage.ENGLISH }
                    } })
                    .put("withOtherLanguageAssessment", songs.count { song -> assessments[target(song.id)].orEmpty().any {
                        it.language == OriginalNameLanguage.OTHER
                    } })
                    .put("withUnknownAssessmentOrNoAssessment", songs.count { song -> assessments[target(song.id)].orEmpty().let {
                        it.isEmpty() || it.any { assessment -> assessment.language == OriginalNameLanguage.UNKNOWN }
                    } })
                    .put("note", "Unknown, failed or missing evidence is reported, never counted as English success"))
            report.put("selectionInvariantStatus", report.optString("validationStatus"))
            if (songs.isNotEmpty()) {
                try {
                    // All test-owned workers have stopped. SQLite copies a coherent database,
                    // including Room's schema identity, without reading the application's DB.
                    database.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(snapshot.absolutePath))
                    SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                        copy.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                            check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext())
                        }
                        copy.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> check(!cursor.moveToFirst()) }
                    }
                    report.put("snapshot", JSONObject().put("status", "PASS").put("file", snapshot.name)
                        .put("bytes", snapshot.length()).put("method", "VACUUM INTO; integrity_check; foreign_key_check"))
                } catch (failure: Exception) {
                    report.put("snapshot", JSONObject().put("status", "FAIL")
                        .put("failureClass", failure.javaClass.simpleName))
                    report.put("validationStatus", "FAIL").put("failureClass", "SnapshotExportFailed")
                }
            } else report.put("snapshot", JSONObject().put("status", "NOT_CAPTURED"))
            database.close()
        }
        return report
    }

    private fun fetchStates(database: MusicDatabase, kind: String, id: String) =
        JSONArray(database.metadataFetchStates(kind, id).map { state -> JSONObject()
            .put("language", state.language).put("status", state.status).put("updatedAt", state.updatedAt) })

    /**
     * Validate the new verbatim-Main path independently of publication selection. In particular,
     * a forged und row, a withdrawn relation, an old source fingerprint or a stale/UNKNOWN/OTHER
     * assessment must not turn a committed string into an accepted observation.
     */
    private fun independentlyObservedEnglishOriginals(rows: List<MetadataNameEntity>): Map<String, Set<String>> {
        val directRows = latestOriginalRows(rows)
        val inputs = originalAssessmentInputs(directRows)
        val sources = directRows.filter { it.kind == "SONG" }.groupBy { it.targetId }.mapNotNull { (id, sourceRows) ->
            val candidate = sourceRows.mapNotNull(::originalCandidate).distinct().singleOrNull() ?: return@mapNotNull null
            if (candidate.target != target(id) || candidate.sourceVideoId != id ||
                sourceRows.any { !hasCurrentOriginalAssessmentInputs(it, inputs) }) return@mapNotNull null
            val assessments = sourceRows.map { row ->
                OriginalNameAssessmentCodec.decode(row.originEvidenceJson, candidate.target, candidate.name)
            }
            if (assessments.any { assessment -> assessment == null || assessment.sourceVideoId != id ||
                    assessment.language != OriginalNameLanguage.ENGLISH ||
                    !assessment.method.startsWith(OriginalAlbumLanguageResolver.METHOD_VERSION + "/") ||
                    assessment.method.length <= OriginalAlbumLanguageResolver.METHOD_VERSION.length + 1 }) return@mapNotNull null
            id to candidate
        }.toMap()
        val observedEnglish = rows.filter { it.kind == "SONG" && it.language == "en" }
            .groupBy { it.targetId }.mapValues { (_, names) -> names.map { comparable(it.name) }.toSet() }
        val result = sources.mapValues { (_, candidate) -> mutableSetOf(candidate.name) }.toMutableMap()
        val latestRelations = rows.filter { it.kind == "SONG" && it.language == "und" &&
            (it.source.startsWith(PROVIDER_SONG_REFERENCE_SOURCE_PREFIX) ||
                it.source.startsWith(PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX)) }
            .groupBy { it.targetId to it.source }.values.flatMap { observations ->
                val newest = observations.maxOf { it.observedAt }
                observations.filter { it.observedAt == newest }
            }
        for (row in latestRelations) {
            val playlist = PlaylistSongReferenceCodec.decode(row)
            if (playlist != null) {
                val source = sources[playlist.sourceVideoId] ?: continue
                // A shorter Music name is corroborating evidence only when both independent
                // source and target observations actually exist in this live capture.
                if (comparable(playlist.sourceMusicName) != comparable(playlist.originalName) &&
                    (comparable(playlist.sourceMusicName) !in observedEnglish[playlist.sourceVideoId].orEmpty() ||
                        comparable(playlist.sourceMusicName) !in observedEnglish[playlist.targetVideoId].orEmpty())) continue
                val sourceMusic = SongItem(playlist.sourceVideoId, playlist.sourceMusicName, emptyList(),
                    Album("Verified canonical album", playlist.targetAlbumId), thumbnail = "")
                val targetMusic = sourceMusic.copy(id = playlist.targetVideoId)
                val rebuilt = playlistSongReference(PlaylistSongReference(playlist.playlistId, playlist.playlistSetVideoId,
                    playlist.sourceVideoId, playlist.targetVideoId), source, targetMusic, playlist.playlistId,
                    expectedAlbumId = playlist.targetAlbumId, sourceMusic = sourceMusic)
                if (rebuilt == playlist) result.getOrPut(row.targetId) { mutableSetOf() }.add(playlist.originalName)
                continue
            }
            val provider = ProviderSongReferenceCodec.decode(row) ?: continue
            val source = sources[provider.sourceVideoId] ?: continue
            val targetMusic = SongItem(provider.targetVideoId, provider.originalName, emptyList(),
                Album("Verified source album", provider.sourceAlbumId), thumbnail = "")
            val rebuilt = providerSongReference(MainSongReference(provider.sourceVideoId, provider.targetVideoId),
                source, targetMusic)
            if (rebuilt == provider) result.getOrPut(row.targetId) { mutableSetOf() }.add(provider.originalName)
        }
        return result.mapValues { it.value.toSet() }
    }

    private fun nameJson(row: MetadataNameEntity) = JSONObject().put("kind", row.kind).put("targetId", row.targetId)
        .put("language", row.language).put("name", row.name).put("source", row.source)
        .put("sourcePriority", row.sourcePriority).put("observedAt", row.observedAt)
        .put("evidence", row.originEvidenceJson?.let(::JSONObject) ?: JSONObject.NULL)

    private fun displayed(songs: List<SongItem>, names: Map<OriginalNameTarget, String>) =
        songs.associate { it.id to (names[target(it.id)] ?: it.title) }
    private fun target(id: String) = OriginalNameTarget(OriginalNameKind.SONG, id)
    private fun comparable(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

    companion object {
        // IDs previously observed in 9/19 album-language and 9/12 artist-idle-refresh records.
        private const val ABBEY_ROAD = "MPREb_tQfaWH32ovE"
        private const val TRIGGER = "MPREb_D37btAezO0h"
        private val CLEAR_ENGLISH_PHRASES = setOf("Come Together", "Here Comes The Sun", "You Never Give Me Your Money")
    }
}
