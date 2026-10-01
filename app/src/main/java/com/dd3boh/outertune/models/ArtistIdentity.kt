package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.AlbumItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Internal identity is independent of the spelling returned by a later artist-page request. */
object ArtistIdentity {
    /** Album-scoped display identity, never an online person identifier or a stored credit ref. */
    fun albumGroupId(albumId: String, name: String): String? {
        if (!albumId.startsWith("MPRE") && !albumId.startsWith("FEmusic_library_privately_owned_release")) return null
        val normalizedName = normalizeLocalMetadataText(name).takeIf(String::isNotEmpty) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("album-artist-v1\u0000$albumId\u0000$normalizedName".toByteArray(Charsets.UTF_8))
        return "AG" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun stableId(videoId: String, name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$videoId\u0000${normalizeLocalMetadataText(name)}".toByteArray(Charsets.UTF_8))
        return "LA" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Channel sources share an internal identity across videos, separate from online artists. */
    fun channelId(sourceChannelId: String?, videoId: String, name: String): String {
        val source = sourceChannelId?.takeIf(String::isNotBlank)
            ?: "$videoId\u0000${normalizeLocalMetadataText(name)}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("channel-source-v1\u0000$source".toByteArray(Charsets.UTF_8))
        return "CS" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun sourceId(videoId: String, artist: com.zionhuang.innertube.models.Artist): String =
        if (artist.isChannel) channelId(artist.sourceChannelId, videoId, artist.name)
        else artist.ref ?: onlineId(artist.id) ?: stableId(videoId, artist.name)

    fun onlineId(id: String?): String? = id?.takeIf {
        it.startsWith("UC") || it.startsWith("FEmusic_library_privately_owned_artist")
    }

    fun withStableRefs(videoId: String, credit: ArtistCredit, previous: ArtistCredit? = null): ArtistCredit =
        credit.copy(artists = if (credit.status == ArtistCreditStatus.RAW) emptyList() else credit.artists.map { artist ->
            val previousArtist = previous?.artists?.firstOrNull {
                it.isChannel == artist.isChannel && (if (artist.isChannel)
                    it.sourceChannelId == artist.sourceChannelId &&
                        (artist.sourceChannelId != null || it.name == artist.name)
                else normalizeLocalMetadataText(it.name) == normalizeLocalMetadataText(artist.name) ||
                    (onlineId(artist.id) != null && it.id == artist.id)
                )
            }
            artist.copy(
                id = if (artist.isChannel) null else onlineId(artist.id),
                ref = if (artist.isChannel) channelId(artist.sourceChannelId, videoId, artist.name)
                    else previousArtist?.ref ?: sourceId(videoId, artist),
            )
        })
}

private val artistCreditJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun ArtistCredit.toStoredJson(): String = artistCreditJson.encodeToString(this)

fun artistCreditFromJson(value: String?): ArtistCredit? = value?.let {
    runCatching { artistCreditJson.decodeFromString<ArtistCredit>(it) }.getOrNull()
}

internal fun AlbumItem.creditForPersistence(): ArtistCredit {
    artistCredit?.let { return it }
    val labels = artists.orEmpty()
    val confirmed = labels.filter { ArtistIdentity.onlineId(it.id) != null }
    return ArtistCredit(
        rawText = labels.joinToString { it.name },
        artists = confirmed,
        status = when {
            confirmed.isEmpty() -> ArtistCreditStatus.RAW
            confirmed.size == labels.size -> ArtistCreditStatus.COMPLETE
            else -> ArtistCreditStatus.PARTIAL
        },
        source = "legacy-album",
    )
}

/**
 * Unicode-aware fallback for artist names that SQLite NOCASE cannot compare (for example,
 * full-width Latin characters). Source is always part of the identity.
 */
internal fun selectArtistByNormalizedName(
    name: String,
    isLocal: Boolean,
    candidates: Iterable<ArtistEntity>,
): ArtistEntity? {
    val normalizedName = normalizeLocalMetadataText(name)
    return candidates.firstOrNull { candidate ->
        candidate.isLocal == isLocal &&
            normalizeLocalMetadataText(candidate.name) == normalizedName
    }
}
