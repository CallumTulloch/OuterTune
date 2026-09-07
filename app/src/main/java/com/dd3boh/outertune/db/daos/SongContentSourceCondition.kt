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

/** With no explicit source selection, include bookmarks whose songs have not been fetched yet. */
internal fun libraryBookmarkedContentCondition(
    filters: Set<LibraryContentFilter>,
    likedOnly: Boolean,
    table: String,
): String {
    if (!likedOnly) return librarySongContentCondition(filters)
    val bookmarked = "$table.bookmarkedAt IS NOT NULL"
    return if (filters.isEmpty()) bookmarked else "($bookmarked) AND (${librarySongContentCondition(filters)})"
}
