package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
enum class ArtistCreditStatus { RAW, PARTIAL, COMPLETE, CONFLICT }

/** The literal byline is independent of individually established artists and their page IDs. */
@Serializable
data class ArtistCredit(
    val rawText: String,
    val artists: List<Artist>,
    val status: ArtistCreditStatus,
    val source: String = "",
    val language: String = "",
    val evidence: List<String> = emptyList(),
) : java.io.Serializable

/** An unanswered request carries no byline or adopted person, even if it records a language. */
fun ArtistCredit.isEmptyByline(): Boolean =
    status == ArtistCreditStatus.RAW && rawText.isBlank() && artists.isEmpty()

/** A channel fallback is usable in the app, without establishing any performer identity. */
fun ArtistCredit.isChannelByline(): Boolean = artists.isNotEmpty() && artists.all { it.isChannel }

/** Merge evidence without turning a later, thinner response into a destructive replacement. */
fun ArtistCredit.merge(incoming: ArtistCredit): ArtistCredit {
    if (incoming == this) return this
    val notes = (evidence + incoming.evidence).distinct()
    // A failed request in an earlier language must not permanently block a successful repair.
    // Keep the language guard for actual literal bylines and previously adopted people.
    if (isEmptyByline()) return incoming.copy(evidence = notes)
    if (language.isNotEmpty() && incoming.language.isNotEmpty() && language != incoming.language) return this
    fun conflict() = copy(
        evidence = (notes + "conflict:${incoming.source}:${incoming.artists.map { it.name to it.id }}").distinct())
    if (status == ArtistCreditStatus.CONFLICT) return copy(evidence = notes)
    if (incoming.status == ArtistCreditStatus.CONFLICT) return conflict()
    // Performer evidence replaces a fallback as a whole, preserving its own literal byline.
    // Conversely a later uploader response must not replace already established artists.
    if (isChannelByline() && !incoming.isChannelByline() && incoming.artists.isNotEmpty() &&
        incoming.status != ArtistCreditStatus.RAW) return incoming.copy(evidence = notes)
    if (incoming.isChannelByline() && !isChannelByline() && artists.isNotEmpty()) return copy(evidence = notes)
    if (incoming.status == ArtistCreditStatus.RAW) return copy(
        rawText = rawText.ifEmpty { incoming.rawText }, evidence = notes)
    if (status == ArtistCreditStatus.RAW) return incoming.copy(
        rawText = rawText.ifEmpty { incoming.rawText },
        language = language.ifEmpty { incoming.language }, evidence = notes)
    fun matches(a: Artist, b: Artist): Boolean {
        if (a.isChannel != b.isChannel) return false
        if (a.isChannel) {
            return if (a.sourceChannelId != null || b.sourceChannelId != null)
                a.sourceChannelId != null && a.sourceChannelId == b.sourceChannelId
            else a.ref != null && a.ref == b.ref
        }
        return a.name == b.name || (a.id != null && a.id == b.id)
    }
    // Every previously adopted name must survive a complete replacement.
    if (incoming.status == ArtistCreditStatus.COMPLETE && artists.any { old ->
            incoming.artists.none { matches(old, it) }
        }) return conflict()
    if (status == ArtistCreditStatus.COMPLETE && incoming.artists.any { fresh ->
            artists.none { matches(it, fresh) }
        }) return conflict()
    if (status == ArtistCreditStatus.COMPLETE && incoming.status == ArtistCreditStatus.COMPLETE &&
        (artists.size != incoming.artists.size || artists.zip(incoming.artists).any { !matches(it.first, it.second) }))
        return conflict()
    if (artists.any { old -> incoming.artists.any { fresh ->
            old.name == fresh.name && old.id != null && fresh.id != null && old.id != fresh.id
        } }) return conflict()
    val partialSuperset = status == ArtistCreditStatus.PARTIAL && incoming.status == ArtistCreditStatus.PARTIAL &&
        artists.all { old -> incoming.artists.any { matches(old, it) } } && incoming.artists.size > artists.size
    val base = if ((incoming.status == ArtistCreditStatus.COMPLETE && status != ArtistCreditStatus.COMPLETE) || partialSuperset)
        incoming.artists else artists
    val merged = base.map { artist ->
        val old = artists.singleOrNull { matches(it, artist) }
        val fresh = incoming.artists.singleOrNull { matches(artist, it) }
        artist.copy(name = old?.name ?: artist.name, id = if (artist.isChannel) null else old?.id ?: fresh?.id ?: artist.id,
            ref = old?.ref ?: artist.ref ?: fresh?.ref)
    }
    // Independent partial results are not a proof that their union is complete.
    return copy(artists = merged,
        status = if (status == ArtistCreditStatus.COMPLETE || incoming.status == ArtistCreditStatus.COMPLETE)
            ArtistCreditStatus.COMPLETE else ArtistCreditStatus.PARTIAL,
        source = if (status != ArtistCreditStatus.COMPLETE && incoming.status == ArtistCreditStatus.COMPLETE)
            incoming.source else source,
        evidence = notes)
}

private val artistSeparators = setOf("、", "・", " & ", ", ", " × ", " and ", " feat. ", " featuring ")
private val duration = Regex("""^\d+:[0-5]\d(?::[0-5]\d)?$""")

/** Restrict a metadata column to its byline; never split the contents of one Run. */
fun List<Run>.artistBylineRuns(): List<Run> {
    val byline = splitBySeparator().clean().firstOrNull().orEmpty()
    return byline.filterNot {
        it.navigationEndpoint == null && duration.matches(it.text.trim())
    }
}

/** Only an explicitly typed artist endpoint supplies an online artist ID. */
fun Run.toArtist(): Artist {
    val endpoint = navigationEndpoint?.browseEndpoint
    return if (endpoint?.isChannelEndpoint == true) Artist(text, null, sourceChannelId = endpoint.browseId)
    else Artist(text, endpoint?.takeIf { it.isArtistEndpoint }?.browseId)
}

fun List<Run>.toArtistCredit(source: String, language: String = "", allowChannelFallback: Boolean = true): ArtistCredit {
    val runs = artistBylineRuns()
    val raw = runs.joinToString("") { it.text }
    val names = runs.filterNot { it.navigationEndpoint == null && (it.text in artistSeparators || it.text.isBlank()) }
    val supplied = names.map { it.toArtist() }
    val artists = if (supplied.any { it.id != null }) supplied.filterNot { it.isChannel } else supplied
    val channelFallback = allowChannelFallback && artists.isNotEmpty() && artists.all { it.isChannel }
    val singleLinked = artists.size == 1 && artists[0].id != null
    val explicitList = names.size > 1 && runs.size == names.size * 2 - 1 &&
        artists.none { it.isChannel } &&
        runs.filterIndexed { index, _ -> index % 2 == 1 }.all { it.navigationEndpoint == null && it.text in artistSeparators }
    val allLinked = artists.size > 1 && artists.all { it.id != null }
    val complete = channelFallback || singleLinked || explicitList || allLinked
    return ArtistCredit(raw, if (complete) artists else emptyList(),
        if (complete) ArtistCreditStatus.COMPLETE else ArtistCreditStatus.RAW, source, language,
        listOf(if (channelFallback) "channel-byline:$source" else if (complete) "structured-byline:$source" else "literal-byline:$source"))
}

fun Menu?.artistBrowseIds(): List<String> = this?.menuRenderer?.items.orEmpty().mapNotNull {
    it.menuNavigationItemRenderer?.takeIf { item -> item.icon.iconType == "ARTIST" }
        ?.navigationEndpoint?.browseEndpoint?.takeIf { endpoint -> endpoint.isArtistEndpoint }?.browseId
}.distinct()

/** Album subtitles can contain only release type/year; these are not artist names. */
fun List<Run>.toAlbumArtistCredit(source: String, language: String = ""): ArtistCredit {
    val sections = splitBySeparator()
    val artistSection = sections.firstOrNull { section ->
        section.any { it.navigationEndpoint?.browseEndpoint?.isArtistEndpoint == true }
    } ?: sections.firstOrNull { section ->
        val text = section.joinToString("") { it.text }
        text.isNotBlank() && text !in setOf("アルバム", "シングル", "Album", "Single", "EP", "专辑", "專輯") &&
            !Regex("^\\d{4}年?$").matches(text)
    }.orEmpty()
    return artistSection.toArtistCredit(source, language, allowChannelFallback = false)
}

private fun ArtistCredit.withVideoEndpoint(vararg endpoints: WatchEndpoint?): ArtistCredit {
    val type = endpoints.firstNotNullOfOrNull {
        it?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
    } ?: return this
    return if (type == "MUSIC_VIDEO_TYPE_ATV") this
    else copy(evidence = (evidence + "video-source:$type").distinct())
}

fun ArtistCredit.withVideoSource(renderer: MusicResponsiveListItemRenderer) = withVideoEndpoint(
    renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
        ?.playNavigationEndpoint?.anyWatchEndpoint,
    renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
        ?.firstOrNull()?.navigationEndpoint?.anyWatchEndpoint,
    renderer.navigationEndpoint?.anyWatchEndpoint)

fun ArtistCredit.withVideoSource(renderer: MusicTwoRowItemRenderer) = withVideoEndpoint(renderer.navigationEndpoint.anyWatchEndpoint)
fun ArtistCredit.withVideoSource(renderer: MusicCardShelfRenderer) = withVideoEndpoint(renderer.onTap.anyWatchEndpoint)
fun ArtistCredit.withVideoSource(renderer: PlaylistPanelVideoRenderer) = withVideoEndpoint(renderer.navigationEndpoint.anyWatchEndpoint)
