package com.dd3boh.outertune.repositories

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.artistDisplayText
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.isEmptyByline
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live response check with an isolated repository DB/preferences. */
class LiveArtistBylineProbeTest {
    @Test
    fun missingAlbumVideoBylineResolvesAndSurvivesDatabaseReopen() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveArtistBylineProbe") == "true")
        assumeTrue("The live probe requires an anonymous app session", YouTube.authentication.cookie.isNullOrBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "live-artist-byline-$suffix.db"
        val preferenceName = "live-artist-byline-$suffix"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        val previousLocale = YouTube.locale
        val locale = YouTubeLocale("JP", "ja")
        val videoId = "Ohf-kbf6cR4"
        val albumId = "MPREb_D37btAezO0h"
        val artistId = "UCPCiIrrrNJOKvi_5vr3G6PA"
        val jobs = mutableListOf<kotlinx.coroutines.CompletableJob>()
        var internal: InternalDatabase? = null
        var stage = "ALBUM_FETCH"
        try {
            withTimeout(90_000) {
                YouTube.locale = locale
                val page = YouTube.album(albumId, requestLocale = locale, notifyMetadata = false).getOrThrow()
                stage = "ALBUM_SHAPE"
                val track = page.songs.single { it.id == videoId }
                val supplied = requireNotNull(track.artistCredit)
                assertTrue(supplied.isEmptyByline())
                assertTrue(supplied.source == "AlbumPage" && supplied.language == "ja")
                assertTrue("video-source:MUSIC_VIDEO_TYPE_OMV" in supplied.evidence)
                assertTrue(page.album.artistCredit?.artists?.any { it.id == artistId } == true)

                stage = "INITIAL_SAVE"
                val opened = Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build()
                internal = opened
                val database = MusicDatabase(opened)
                database.insert(page)
                val initial = requireNotNull(database.song(videoId).first())
                val savedAlbum = requireNotNull(database.albumById(albumId))
                val albumArtistIds = database.albumArtistIdsForAlbum(albumId)
                assertTrue(initial.artistCredit?.isEmptyByline() == true)
                assertTrue(initial.artists.isEmpty())
                assertTrue(savedAlbum.artistCredit?.status == ArtistCreditStatus.COMPLETE)
                assertTrue(albumArtistIds.isNotEmpty())

                stage = "TRACK_RESOLUTION"
                assertTrue(YouTube.authentication.cookie.isNullOrBlank() && YouTube.locale == locale)
                val job = SupervisorJob().also(jobs::add)
                // Keep the production fetch implementation, including same-video queue parsing.
                val repository = ArtistCreditRepository(database, isolatedContext,
                    ArtistCreditRepository.Runtime(scope = CoroutineScope(job + Dispatchers.IO)))
                repository.request(initial.toMediaMetadata(), priority = true)
                job.children.toList().joinAll()

                stage = "TRACK_SAVED"
                val repaired = requireNotNull(database.song(videoId).first())
                val credit = requireNotNull(repaired.artistCredit)
                assertTrue(credit.status == ArtistCreditStatus.COMPLETE)
                assertTrue(credit.language == "ja")
                assertTrue(credit.artists.any { it.name == "天音かなた" && it.id == artistId })
                assertTrue(repaired.artists.any { it.name == "天音かなた" && it.onlineArtistId == artistId })
                assertTrue(database.artistIdsForSong(videoId) == credit.artists.map { it.ref }.distinct())
                val displayed = repository.withCredit(initial.toMediaMetadata())
                assertTrue(displayed.artists.any { it.name == "天音かなた" && it.onlineId == artistId })
                assertTrue(displayed.artistDisplayText() == "天音かなた")
                assertTrue(repaired.song.inLibrary == null && !repaired.song.liked)
                assertTrue(savedAlbum == database.albumById(albumId))
                assertTrue(albumArtistIds == database.albumArtistIdsForAlbum(albumId))

                stage = "DATABASE_REOPEN"
                job.cancelAndJoin()
                opened.close()
                internal = null
                // Remove the isolated cache so persistence is proved by the reopened DB alone.
                assertTrue(preferences.edit().clear().commit())
                val reopened = Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build()
                internal = reopened
                val restoredDatabase = MusicDatabase(reopened)
                val restoredSong = requireNotNull(restoredDatabase.song(videoId).first())
                assertTrue(restoredSong.artistCredit == credit)
                assertTrue(restoredSong.artists == repaired.artists)
                val restoredJob = SupervisorJob().also(jobs::add)
                val restoredRepository = ArtistCreditRepository(restoredDatabase, isolatedContext,
                    ArtistCreditRepository.Runtime(scope = CoroutineScope(restoredJob + Dispatchers.IO)))
                val restoredSource = restoredSong.toMediaMetadata()
                restoredRepository.request(restoredSource, priority = true)
                restoredJob.children.toList().joinAll()
                assertTrue(restoredRepository.withCredit(restoredSource).artistCredit == credit)
                assertTrue(restoredRepository.withCredit(restoredSource).artistDisplayText() == "天音かなた")
                assertTrue(restoredDatabase.albumById(albumId) == savedAlbum)
            }
        } catch (error: Throwable) {
            // Never attach the original response, URL, exception message, or cause.
            throw AssertionError("Live artist byline failed at $stage: ${error.javaClass.simpleName}")
        } finally {
            try {
                withContext(NonCancellable) {
                    try {
                        jobs.forEach { it.cancelAndJoin() }
                    } finally {
                        try {
                            internal?.close()
                        } finally {
                            try {
                                context.deleteDatabase(databaseName)
                            } finally {
                                context.deleteSharedPreferences(preferenceName)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                throw AssertionError("Live artist byline failed at CLEANUP: ${error.javaClass.simpleName}")
            } finally {
                YouTube.locale = previousLocale
            }
        }
    }
}
