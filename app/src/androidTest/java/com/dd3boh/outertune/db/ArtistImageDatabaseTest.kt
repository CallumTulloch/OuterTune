package com.dd3boh.outertune.db

import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.pages.ArtistPage
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtistImageDatabaseTest {
    @Test fun profileUpdatePreservesConcurrentEditsRelationsAndImageAcrossReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "artist-image-${UUID.randomUUID()}.db"
        val id = "LA-preserved-profile"
        val onlineId = "UC-profile"
        val image = "https://example.invalid/artist-image"
        val bookmarked = LocalDateTime.of(2026, 9, 7, 12, 0)
        try {
            val database = InternalDatabase.newTestInstance(context, filename)
            try {
                val stale = ArtistEntity(id, "Original", onlineId = onlineId)
                database.insert(stale)
                database.insert(SongEntity(id = "profile-song", title = "Song", localPath = null))
                database.insert(SongArtistMap("profile-song", id, 0))
                database.update(stale.copy(name = "User edit", bookmarkedAt = bookmarked))
                database.update(stale, ArtistPage(profile(onlineId, image), emptyList(), null))
                val saved = database.artistById(id)!!
                assertEquals(image, saved.thumbnailUrl)
                assertEquals("User edit", saved.name)
                assertEquals(bookmarked, saved.bookmarkedAt)
                assertEquals(listOf(id), database.artistIdsForSong("profile-song"))
                val updatedAt = saved.lastUpdateTime
                database.saveArtistProfile(profile(onlineId, null))
                database.saveArtistProfile(profile(onlineId, "  "))
                assertEquals(image, database.artistById(id)!!.thumbnailUrl)
                assertEquals(updatedAt, database.artistById(id)!!.lastUpdateTime)
                database.insert(ArtistEntity("local-artist", "Local", onlineId = onlineId, isLocal = true))
                database.saveArtistProfile(profile(onlineId, image))
                assertNull(database.artistById("local-artist")!!.thumbnailUrl)
                assertEquals(0, database.saveArtistProfile(profile("UC-not-saved", image)))
                assertNull(database.artistById("UC-not-saved"))
            } finally { database.close() }
            val reopened = InternalDatabase.newTestInstance(context, filename)
            try {
                val saved = reopened.artistById(id)!!
                assertEquals(image, saved.thumbnailUrl)
                assertEquals("User edit", saved.name)
                assertEquals(bookmarked, saved.bookmarkedAt)
                assertEquals(listOf(id), reopened.artistIdsForSong("profile-song"))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(filename) }
    }

    private fun profile(id: String, image: String?) = ArtistItem(id, "Remote name", image,
        shuffleEndpoint = null, radioEndpoint = null)
}
