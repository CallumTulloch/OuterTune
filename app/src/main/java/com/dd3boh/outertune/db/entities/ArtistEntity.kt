package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dd3boh.outertune.utils.syncCoroutine
import com.dd3boh.outertune.models.isYouTubeArtistBrowseId
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime

@Immutable
@Entity(
    tableName = "artist",
    indices = [
        Index(value = ["isLocal", "name"]),
        Index(value = ["browseId"], unique = true),
    ],
)
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val channelId: String? = null,
    val lastUpdateTime: LocalDateTime = LocalDateTime.now(),
    val bookmarkedAt: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = false.toString())
    val isLocal: Boolean = false,
    /** YouTube Music browse id. [id] remains the stable internal database primary key. */
    val browseId: String? = initialArtistBrowseId(id, isLocal),
) {
    val isYouTubeArtist: Boolean
        get() = !isLocal && browseId?.isYouTubeArtistBrowseId() == true

    /** Id that may safely be exposed to artist navigation. */
    val navigationId: String?
        get() = if (isLocal) id else browseId?.takeIf { it.isYouTubeArtistBrowseId() }

    fun localToggleLike() = copy(
        bookmarkedAt = if (bookmarkedAt != null) null else LocalDateTime.now(),
    )

    fun toggleLike() = localToggleLike().also {
        val artistBrowseId = navigationId?.takeUnless { isLocal } ?: return@also
        CoroutineScope(syncCoroutine).launch {
            if (channelId == null)
                YouTube.subscribeChannel(YouTube.getChannelId(artistBrowseId), bookmarkedAt == null)
            else
                YouTube.subscribeChannel(channelId, bookmarkedAt == null)
            this.cancel()
        }
    }

    companion object {
        fun generateArtistId() = "LA" + RandomStringUtils.insecure().next(8, true, false)
    }
}

/**
 * Recognizes ids that callers may safely use as a YouTube artist browse id without guessing.
 */
internal fun initialArtistBrowseId(id: String, isLocal: Boolean): String? = id.takeIf {
    !isLocal && it.isYouTubeArtistBrowseId()
}
