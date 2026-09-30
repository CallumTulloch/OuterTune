package com.dd3boh.outertune.models.metadata

import org.junit.Assert.*
import org.junit.Test

class OriginalEvidenceCacheTest {
    private val target = OriginalNameTarget(OriginalNameKind.SONG, "abcdefghijk")
    private val original = ArtTrackOriginalName(target, "Original Name", target.id, "MPREalbum")

    @Test fun `cached proof remains bound to the exact row and updated payload`() {
        val cache = OriginalEvidenceCache()
        val payload = ArtTrackOriginalNameCodec.encode(original)
        assertEquals(original, cache.decode(payload, target, original.name).original)
        assertNull(cache.decode(payload, target.copy(id = "12345678901"), original.name).original)
        assertNull(cache.decode(payload, target, "Another Name").original)
        assertNull(cache.decode("{}", target, original.name).original)
        assertNull(cache.decode("invalid", target, original.name).original)
        val changed = original.copy(albumId = "MPREchanged")
        assertEquals(changed, cache.decode(ArtTrackOriginalNameCodec.encode(changed), target, original.name).original)
        assertEquals(original, cache.decode(payload, target, original.name).original)
    }

    @Test fun `entry and text eviction do not alter decoded identities`() {
        for (cache in listOf(OriginalEvidenceCache(maxEntries = 1), OriginalEvidenceCache(maxCharacters = 1))) {
            val payload = ArtTrackOriginalNameCodec.encode(original)
            assertEquals(original, cache.decode(payload, target, original.name).original)
            assertNull(cache.decode("{}", target, original.name).original)
            assertEquals(original, cache.decode(payload, target, original.name).original)
        }
    }
}
