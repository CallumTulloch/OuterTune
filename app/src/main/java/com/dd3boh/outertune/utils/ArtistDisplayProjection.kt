package com.dd3boh.outertune.utils

import androidx.compose.runtime.mutableStateOf
import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A display/navigation value, never an artist entity or a playback tag to persist. */
data class ArtistDisplayTarget(
    val sourceId: String?,
    val id: String?,
    val name: String,
    val thumbnailUrl: String?,
    internal val linked: Boolean = false,
    internal val onlineIdentity: String? = null,
)

/** Only explicitly linked local artists are published here. Stored tags and relationships stay raw. */
object ArtistDisplayProjection {
    private val mappings = mutableStateOf<Map<String, ArtistDisplayMapping>>(emptyMap())
    private val revision = MutableStateFlow(0L)
    val updates = revision.asStateFlow()

    internal fun publish(values: List<ArtistDisplayMapping>) {
        val next = values.associateBy { it.sourceArtistId }
        if (mappings.value == next) return
        mappings.value = next
        revision.value += 1
    }

    fun resolve(sourceId: String?): ArtistDisplayTarget? = sourceId?.let { mappings.value[it] }?.let { mapping ->
        ArtistDisplayTarget(
            sourceId = mapping.sourceArtistId,
            id = mapping.canonicalArtistId,
            name = MetadataNames.resolve(OriginalNameKind.ARTIST, mapping.canonicalArtistId, mapping.name),
            thumbnailUrl = mapping.thumbnailUrl,
            linked = true,
            onlineIdentity = mapping.canonicalArtistId,
        )
    }
}

val ArtistEntity.artistNavigationId: String
    get() = ArtistDisplayProjection.resolve(id)?.id ?: id

val MediaMetadata.Artist.artistNavigationId: String?
    get() = ArtistDisplayProjection.resolve(id)?.id ?: id

val ArtistEntity.displayThumbnailUrl: String?
    get() {
        val linked = ArtistDisplayProjection.resolve(id)
        return if (linked != null) linked.thumbnailUrl else thumbnailUrl
    }

val MediaMetadata.Artist.displayThumbnailUrl: String?
    get() = ArtistDisplayProjection.resolve(id)?.thumbnailUrl

fun ArtistEntity.displayArtistTarget(): ArtistDisplayTarget = ArtistDisplayProjection.resolve(id)
    ?: ArtistDisplayTarget(id, id, displayName, thumbnailUrl, onlineIdentity = onlineArtistId)

fun MediaMetadata.Artist.displayArtistTarget(): ArtistDisplayTarget = ArtistDisplayProjection.resolve(id)
    ?: ArtistDisplayTarget(id, id, displayName, null,
        onlineIdentity = ArtistIdentity.onlineId(onlineId) ?: ArtistIdentity.onlineId(id))

/** Keep order and unresolved labels; collapse a shared identity only when a manual link established it. */
private fun distinctLinkedTargets(targets: List<ArtistDisplayTarget>): List<ArtistDisplayTarget> {
    val linked = targets.filter { it.linked }.associateBy { it.onlineIdentity }
    if (linked.isEmpty()) return targets
    val seen = mutableSetOf<String>()
    return targets.mapNotNull { target ->
        val canonical = target.onlineIdentity
        val replacement = linked[canonical]
        if (canonical == null || replacement == null) target
        else if (seen.add(canonical)) replacement else null
    }
}

@JvmName("artistEntityDisplayTargets")
fun List<ArtistEntity>.artistDisplayTargets(preserveLocalNames: Boolean = false): List<ArtistDisplayTarget> =
    distinctLinkedTargets(map { artist ->
        ArtistDisplayProjection.resolve(artist.id) ?: if (preserveLocalNames) {
            ArtistDisplayTarget(artist.id, artist.id, artist.name, artist.thumbnailUrl)
        } else artist.displayArtistTarget()
    })

@JvmName("mediaArtistDisplayTargets")
fun List<MediaMetadata.Artist>.artistDisplayTargets(preserveLocalNames: Boolean = false): List<ArtistDisplayTarget> =
    distinctLinkedTargets(map { artist ->
        ArtistDisplayProjection.resolve(artist.id) ?: if (preserveLocalNames) {
            ArtistDisplayTarget(artist.id, artist.id, artist.name, null)
        } else artist.displayArtistTarget()
    })

fun MediaMetadata.displayArtists(): List<ArtistDisplayTarget> = artists.artistDisplayTargets(preserveLocalNames = isLocal)
