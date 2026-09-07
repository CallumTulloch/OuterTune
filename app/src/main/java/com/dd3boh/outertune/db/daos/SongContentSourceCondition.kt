package com.dd3boh.outertune.db.daos

import com.dd3boh.outertune.constants.LibraryContentFilter

/** Song source predicates shared by the album, artist, and playlist content filters. */
internal fun songContentSourceCondition(
    filter: LibraryContentFilter,
    songAlias: String = "song",
): String = when (filter) {
    LibraryContentFilter.LIBRARY -> "$songAlias.isLocal = 0 AND $songAlias.inLibrary IS NOT NULL"
    LibraryContentFilter.DOWNLOADED -> "$songAlias.isLocal = 0 AND $songAlias.dateDownload IS NOT NULL"
    LibraryContentFilter.FOLDER -> "$songAlias.isLocal = 1 AND $songAlias.inLibrary IS NOT NULL"
}

internal fun librarySongContentCondition(filters: Set<LibraryContentFilter>): String =
    LibraryContentFilter.effective(filters).joinToString(separator = " OR ") { filter ->
        "(${songContentSourceCondition(filter)})"
    }
