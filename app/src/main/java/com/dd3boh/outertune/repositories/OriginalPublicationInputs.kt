package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget

/** The complete read set of one display decision, including absent/withdrawn source snapshots. */
internal data class OriginalPublicationInput(
    val targetRows: Set<MetadataNameEntity>,
    val sourceRows: Set<MetadataNameEntity>,
    val albumSongs: Set<ArtTrackOriginalName>,
)

/** Index one database snapshot. Unrelated albums do not participate in a target's commit guard.
 * Caches belong only to this snapshot; a later snapshot must recompute withdrawals and readiness.
 */
internal class OriginalPublicationInputs(rows: List<MetadataNameEntity>) {
    val originals = latestOriginalRows(rows)
    val assessmentInputs by lazy { originalAssessmentInputs(originals) }
    private val candidates = originals.associateWith { requireNotNull(originalCandidate(it)) }
    private val byTarget = originals.groupBy { candidates.getValue(it).target }
    val bySource = originals.groupBy { candidates.getValue(it).sourceVideoId }
    private val songsByAlbum = candidates.values.filter { it.target.kind == OriginalNameKind.SONG }
        .groupBy { it.albumId }
    private val targetRows = rows.filter { (it.language == "en" || it.language == "und") && it.source != "manual" }
        .groupBy { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }

    private class Dependencies(
        val requiredRows: Collection<MetadataNameEntity>,
        val referencedSources: Set<String>,
    ) {
        var input: OriginalPublicationInput? = null
        var ready: Boolean? = null
    }
    private val dependenciesByTarget = mutableMapOf<OriginalNameTarget, Dependencies>()
    private val sourceRowSets = mutableMapOf<String, Set<MetadataNameEntity>>()
    private val albumSongSets = mutableMapOf<String, Set<ArtTrackOriginalName>>()

    private fun referencedSources(target: OriginalNameTarget): Set<String> {
        val observations = targetRows[target].orEmpty()
        val newestBySource = mutableMapOf<String, Long>()
        for (row in observations) {
            if (row.language == "und" && !row.source.startsWith(ORIGINAL_NAME_SOURCE_PREFIX)) {
                val newest = newestBySource[row.source]
                if (newest == null || row.observedAt > newest) newestBySource[row.source] = row.observedAt
            }
        }
        if (newestBySource.isEmpty()) return emptySet()
        val sources = mutableSetOf<String>()
        for (row in observations) {
            // Pick the newest observation before decoding: a withdrawal must hide older edges,
            // while equally recent conflicting observations still participate in the guard.
            if (row.language == "und" && row.observedAt == newestBySource[row.source]) {
                val source = ProviderSongReferenceCodec.decode(row)?.sourceVideoId
                    ?: PlaylistSongReferenceCodec.decode(row)?.sourceVideoId
                if (source != null) sources += source
            }
        }
        return sources
    }

    private fun dependencies(target: OriginalNameTarget): Dependencies = dependenciesByTarget.getOrPut(target) {
        val references = referencedSources(target)
        val ownRows = byTarget[target].orEmpty()
        // latestOriginalRows already deduplicated ownRows. Only a referenced source can overlap.
        val required = if (references.isEmpty()) ownRows else mutableSetOf<MetadataNameEntity>().apply {
            addAll(ownRows)
            for (source in references) {
                for (row in bySource[source].orEmpty()) if (row.kind == "SONG") add(row)
            }
        }
        Dependencies(required, references)
    }

    /** An unassessed referenced source is pending too; it must not withdraw the last good name. */
    fun ready(target: OriginalNameTarget): Boolean {
        val dependencies = dependencies(target)
        return dependencies.ready ?: dependencies.requiredRows
            .all { hasCurrentOriginalAssessmentInputs(it, assessmentInputs) }
            .also { dependencies.ready = it }
    }

    fun forTarget(target: OriginalNameTarget): OriginalPublicationInput {
        val dependencies = dependencies(target)
        dependencies.input?.let { return it }
        val sourceIds = mutableSetOf<String>()
        val albums = mutableSetOf<String>()
        for (row in dependencies.requiredRows) {
            val candidate = candidates.getValue(row)
            sourceIds += candidate.sourceVideoId
            candidate.albumId?.let { albums += it }
        }
        sourceIds.addAll(dependencies.referencedSources)
        return OriginalPublicationInput(
            targetRows[target].orEmpty().toSet(),
            sharedUnion(sourceIds) { source -> sourceRowSets.getOrPut(source) { bySource[source].orEmpty().toSet() } },
            sharedUnion(albums) { album -> albumSongSets.getOrPut(album) { songsByAlbum[album].orEmpty().toSet() } },
        ).also { dependencies.input = it }
    }

    /** Most song/reference decisions use one source and album. Share those complete sets instead
     * of allocating the same album-sized HashSet again for every track in the snapshot.
     */
    private inline fun <T> sharedUnion(keys: Set<String>, values: (String) -> Set<T>): Set<T> = when (keys.size) {
        0 -> emptySet()
        1 -> values(keys.first())
        else -> mutableSetOf<T>().apply { for (key in keys) addAll(values(key)) }
    }
}
