package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.daos.MetadataDisplayName
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.YouTubeLocale

/** Serial display reader. Retains only published strings, never the library's evidence or rows. */
internal class MetadataDisplayReader(private val database: MusicDatabase) {
    data class Publication(
        val names: Map<OriginalNameTarget, String>,
        val aliases: Map<OriginalNameTarget, List<String>>,
    )

    private var options: Pair<YouTubeLocale, Boolean>? = null
    private var revisions = emptyMap<OriginalNameTarget, Long>()
    private var publication = Publication(emptyMap(), emptyMap())

    /** A locale can change while a read is in flight. A skipped UI publication did not consume
     * that frame, even if the settings change back before the next conflated notification.
     */
    fun invalidate() { options = null }

    suspend fun read(locale: YouTubeLocale, preferOriginal: Boolean): Publication? {
        val nextOptions = locale to preferOriginal
        val fullRead = options != nextOptions
        var nextRevisions = emptyMap<OriginalNameTarget, Long>()
        var changed = emptySet<OriginalNameTarget>()
        var rows = emptyList<MetadataDisplayName>()
        // Names, committed originals and revisions must describe the same commit. In particular,
        // a write between the initial full read and its watermark must not disappear forever.
        database.awaitTransaction {
            nextRevisions = metadataDisplayRevisionSnapshot().associate {
                OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) to it.revision
            }
            if (fullRead) {
                rows = metadataDisplayNameSnapshot()
            } else {
                changed = nextRevisions.filter { (target, version) -> revisions[target] != version }.keys +
                    (revisions.keys - nextRevisions.keys)
                rows = changed.groupBy { it.kind }.flatMap { (kind, targets) ->
                    targets.map { it.id }.chunked(400).flatMap { ids ->
                        metadataDisplayNamesForTargets(kind.name, ids)
                    }
                }
            }
        }
        if (!fullRead && changed.isEmpty()) return null
        val names = if (fullRead) mutableMapOf() else publication.names.toMutableMap()
        val aliases = if (fullRead) mutableMapOf() else publication.aliases.toMutableMap()
        changed.forEach { names.remove(it); aliases.remove(it) }
        rows.groupBy { OriginalNameTarget(OriginalNameKind.valueOf(it.name.kind), it.name.targetId) }
            .forEach { (target, candidates) ->
                selectCommittedMetadataDisplayName(target, candidates.map { it.name }, locale.hl,
                    preferOriginal, candidates.first().englishName)?.let { names[target] = it }
                aliases[target] = candidates.map { it.name.name }.distinct()
            }
        revisions = nextRevisions
        options = nextOptions
        if (!fullRead && names == publication.names && aliases == publication.aliases) return null
        return Publication(names, aliases).also { publication = it }
    }
}
