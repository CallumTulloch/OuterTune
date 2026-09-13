package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumWithSongs
import com.dd3boh.outertune.models.MediaMetadata
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.AlbumItem
import java.util.Locale

fun artistNameSeparator(language: String = Locale.getDefault().language): String =
    if (language.substringBefore('-') == "ja") "、" else ", "

private fun unknownArtistName(language: String): String = if (language.substringBefore('-') == "ja")
    "アーティスト不明" else "Unknown artist"

fun ArtistCredit.isVideoCredit(): Boolean = evidence.any { it.startsWith("video-source:") }

/** Never reconstruct an incomplete byline from only the artists established so far. */
fun artistDisplayText(
    credit: ArtistCredit?,
    fallbackNames: List<String>,
    preserveLegacy: Boolean = false,
    language: String = Locale.getDefault().language,
): String {
    if (preserveLegacy) return fallbackNames.joinToString()
    if (credit?.isVideoCredit() == true) {
        return credit.artists.map { it.displayName }.ifEmpty { fallbackNames }.joinToString()
            .ifBlank { credit.rawText }.ifBlank { unknownArtistName(language) }
    }
    if (credit != null) {
        if (credit.status != ArtistCreditStatus.COMPLETE && credit.rawText.isNotBlank()) {
            return credit.rawText
        }
        if (credit.artists.isNotEmpty()) {
            return credit.artists.joinToString(artistNameSeparator(language)) { it.displayName }
        }
        if (credit.rawText.isNotBlank()) return credit.rawText
    }
    return fallbackNames.joinToString(if (credit == null) ", " else artistNameSeparator(language))
        .ifBlank { unknownArtistName(language) }
}

fun Song.artistDisplayText(): String = artistDisplayText(
    artistCredit, artists.artistDisplayTargets(preserveLocalNames = song.isLocal).map { it.name }, song.isLocal,
)

fun SongItem.artistDisplayText(): String = artistDisplayText(artistCredit, artists.map { it.displayName })

fun MediaMetadata.artistDisplayText(): String = artistDisplayText(
    artistCredit, displayArtists().map { it.name }, isLocal,
)

fun Album.artistDisplayText(): String = artistDisplayText(
    album.artistCredit, artists.artistDisplayTargets(preserveLocalNames = album.isLocal).map { it.name }, album.isLocal,
)

fun AlbumWithSongs.artistDisplayText(): String = artistDisplayText(
    album.artistCredit, artists.artistDisplayTargets(preserveLocalNames = album.isLocal).map { it.name }, album.isLocal,
)

fun AlbumItem.artistDisplayText(): String = artistDisplayText(artistCredit, artists.orEmpty().map { it.displayName })

/** Artist discography cards intentionally omit bylines. Do not turn that omission into an error label. */
fun AlbumItem.artistSubtitle(omitMissingArtist: Boolean = false): String? =
    if (omitMissingArtist && artistCredit?.rawText.isNullOrBlank() &&
        artistCredit?.artists.isNullOrEmpty() && artists.isNullOrEmpty()
    ) null else artistDisplayText()

/** A literal/partial byline opens song information; it is not a person's navigation target. */
fun MediaMetadata.hasCompleteArtistList(): Boolean =
    isLocal || artistCredit == null || artistCredit?.status == ArtistCreditStatus.COMPLETE

fun MediaMetadata.singleArtistTarget(): String? =
    displayArtists().singleOrNull()?.id?.takeIf { it.isNotBlank() && hasCompleteArtistList() }
