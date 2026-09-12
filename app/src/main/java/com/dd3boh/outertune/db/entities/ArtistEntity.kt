package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dd3boh.outertune.utils.syncCoroutine
import com.dd3boh.outertune.models.ArtistIdentity
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.YouTubeSyncPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime

@Immutable
@Entity(
    tableName = "artist",
    indices = [Index(value = ["isLocal", "name"]), Index(value = ["onlineId"])],
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
    val onlineId: String? = null,
) {
    val onlineArtistId: String?
        get() = if (isLocal) null else ArtistIdentity.onlineId(onlineId) ?: ArtistIdentity.onlineId(id)

    val isYouTubeArtist: Boolean
        get() = onlineArtistId != null

    fun localToggleLike() = copy(
        bookmarkedAt = if (bookmarkedAt != null) null else LocalDateTime.now(),
    )

    fun toggleLike() = localToggleLike().also {
        if (!YouTubeSyncPolicy.ENABLED) return@also
        val remoteId = onlineArtistId ?: return@also
        CoroutineScope(syncCoroutine).launch {
            if (channelId == null)
                YouTube.subscribeChannel(YouTube.getChannelId(remoteId), bookmarkedAt == null)
            else
                YouTube.subscribeChannel(channelId, bookmarkedAt == null)
            this.cancel()
        }
    }

    companion object {
        fun generateArtistId() = "LA" + RandomStringUtils.insecure().next(8, true, false)
    }
}
