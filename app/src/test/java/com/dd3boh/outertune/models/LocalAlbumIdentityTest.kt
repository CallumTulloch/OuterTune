package com.dd3boh.outertune.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAlbumIdentityTest {
    private fun candidate(
        id: String = "LBexisting",
        year: Int? = 2009,
        musicBrainzId: String? = null,
        artistNames: List<String> = listOf("Various Artists"),
        localPaths: List<String> = listOf("/storage/emulated/0/Music/Album/01.mp3"),
    ) = LocalAlbumCandidate(
        id = id,
        title = "Shared Album",
        year = year,
        musicBrainzId = musicBrainzId,
        artistNames = artistNames,
        localPaths = localPaths,
    )

    @Test
    fun `same album artists reuse the local album`() {
        val existing = candidate()

        assertEquals(
            existing,
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Various Artists"),
                year = 2009,
                localPath = "/storage/emulated/0/Other/02.mp3",
                candidates = listOf(existing),
            ),
        )
    }

    @Test
    fun `album artist comparison ignores order case whitespace and duplicates`() {
        val existing = candidate(
            artistNames = listOf("Artist A", "ARTIST B"),
        )

        assertEquals(
            existing,
            selectMatchingLocalAlbum(
                albumArtistNames = listOf(" artist b ", "Ａｒｔｉｓｔ　Ａ", "ARTIST B"),
                year = 2009,
                localPath = "/storage/emulated/0/Other/02.mp3",
                candidates = listOf(existing),
            ),
        )
    }

    @Test
    fun `different album artists do not merge albums with the same title`() {
        assertNull(
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Artist B"),
                year = 2009,
                localPath = "/storage/emulated/0/Other/02.mp3",
                candidates = listOf(candidate(artistNames = listOf("Artist A"))),
            ),
        )
    }

    @Test
    fun `tagged song promotes an untagged album in the same directory`() {
        val untagged = candidate(artistNames = emptyList())

        assertEquals(
            untagged,
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Artist A"),
                year = 2009,
                localPath = "/storage/emulated/0/Music/Album/02.mp3",
                candidates = listOf(untagged),
            ),
        )
    }

    @Test
    fun `tagged song does not promote an untagged album from another directory`() {
        assertNull(
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Artist A"),
                year = 2009,
                localPath = "/storage/emulated/0/Music/Other/02.mp3",
                candidates = listOf(candidate(artistNames = emptyList())),
            ),
        )
    }

    @Test
    fun `missing album artist reuses the only album in the same directory`() {
        val existing = candidate(artistNames = listOf("Artist A"))

        assertEquals(
            existing,
            selectMatchingLocalAlbum(
                albumArtistNames = emptyList(),
                year = 2009,
                localPath = "/storage/emulated/0/Music/Album/02.mp3",
                candidates = listOf(existing),
            ),
        )
    }

    @Test
    fun `missing album artist does not use title fallback across directories`() {
        assertNull(
            selectMatchingLocalAlbum(
                albumArtistNames = emptyList(),
                year = 2009,
                localPath = "/storage/emulated/0/Music/Other/02.mp3",
                candidates = listOf(candidate()),
            ),
        )
    }

    @Test
    fun `missing path uses title fallback only when the candidate is unambiguous`() {
        val existing = candidate()

        assertEquals(
            existing,
            selectMatchingLocalAlbum(
                albumArtistNames = emptyList(),
                year = 2009,
                localPath = null,
                candidates = listOf(existing),
            ),
        )
        assertNull(
            selectMatchingLocalAlbum(
                albumArtistNames = emptyList(),
                year = 2009,
                localPath = null,
                candidates = listOf(existing, candidate(id = "LBother")),
            ),
        )
    }

    @Test
    fun `missing album artist does not guess between albums in one directory`() {
        assertNull(
            selectMatchingLocalAlbum(
                albumArtistNames = emptyList(),
                year = 2009,
                localPath = "/storage/emulated/0/Music/Album/03.mp3",
                candidates = listOf(
                    candidate(id = "LBartistA", artistNames = listOf("Artist A")),
                    candidate(id = "LBartistB", artistNames = listOf("Artist B")),
                ),
            ),
        )
    }

    @Test
    fun `conflicting known years in one directory still merge`() {
        val existing = candidate(year = 2009)

        assertEquals(
            existing,
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Various Artists"),
                year = 2010,
                localPath = "/storage/emulated/0/Music/Album/02.mp3",
                candidates = listOf(existing),
            ),
        )
    }

    @Test
    fun `album artist does not guess between indistinguishable release candidates`() {
        assertNull(
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Artist A"),
                year = 2009,
                localPath = "/storage/emulated/0/Music/Other/03.mp3",
                candidates = listOf(
                    candidate(id = "LBfirst", artistNames = listOf("Artist A")),
                    candidate(
                        id = "LBsecond",
                        artistNames = listOf("Artist A"),
                        localPaths = listOf("/storage/emulated/0/Other/Album/01.mp3"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `conflicting known years in different directories remain separate`() {
        assertNull(
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Various Artists"),
                year = 2010,
                localPath = "/storage/emulated/0/Music/Other/02.mp3",
                candidates = listOf(candidate(year = 2009)),
            ),
        )
    }

    @Test
    fun `musicbrainz release id survives title and year changes`() {
        val existing = candidate(
            year = 2009,
            musicBrainzId = "42d3f760-8f20-4f8a-96bd-955e680c4374",
        )

        assertEquals(
            existing,
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Different Credit"),
                year = 2024,
                localPath = "/storage/emulated/0/Music/Other/02.mp3",
                musicBrainzId = "42D3F760-8F20-4F8A-96BD-955E680C4374",
                candidates = listOf(existing),
            ),
        )
    }

    @Test
    fun `different musicbrainz release ids never merge`() {
        assertNull(
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Various Artists"),
                year = 2009,
                localPath = "/storage/emulated/0/Music/Album/02.mp3",
                musicBrainzId = "b67ea343-3f47-4d75-9819-6adf476f2baa",
                candidates = listOf(
                    candidate(musicBrainzId = "42d3f760-8f20-4f8a-96bd-955e680c4374"),
                ),
            ),
        )
    }

    @Test
    fun `unknown zero year does not split an otherwise matching album`() {
        val existing = candidate(year = 0)

        assertEquals(
            existing,
            selectMatchingLocalAlbum(
                albumArtistNames = listOf("Various Artists"),
                year = 2009,
                localPath = "/storage/emulated/0/Music/Album/02.mp3",
                candidates = listOf(existing),
            ),
        )
    }

    @Test
    fun `joined candidate rows collapse artist and song cross products`() {
        val rows = listOf(
            LocalAlbumCandidateRow(1, "LB1", "Album", 2009, null, "Artist A", 0, null),
            LocalAlbumCandidateRow(1, "LB1", "Album", 2009, null, "Artist B", 1, null),
            LocalAlbumCandidateRow(1, "LB1", "Album", 2009, null, null, null, "/music/01.mp3"),
            LocalAlbumCandidateRow(1, "LB1", "Album", 2009, null, null, null, "/music/02.mp3"),
        )

        assertEquals(
            listOf(
                LocalAlbumCandidate(
                    id = "LB1",
                    title = "Album",
                    year = 2009,
                    musicBrainzId = null,
                    artistNames = listOf("Artist A", "Artist B"),
                    localPaths = listOf("/music/01.mp3", "/music/02.mp3"),
                ),
            ),
            rows.toLocalAlbumCandidates(),
        )
    }
}
