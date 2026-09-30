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

    @Test fun `reread text reuses exact decoded evidence without corrupting eviction or changed proof`() {
        val cache = OriginalEvidenceCache(maxEntries = 1)
        val payload = ArtTrackOriginalNameCodec.encode(original)
        val decoded = cache.decode(payload, target, original.name)
        repeat(100) {
            val reread = String(payload.toCharArray())
            assertNotSame(payload, reread)
            assertSame(decoded, cache.decode(reread, target, original.name))
            assertSame(decoded, cache.decode(reread, target, original.name))
        }
        val changed = original.copy(albumId = "MPREother")
        assertEquals(changed, cache.decode(ArtTrackOriginalNameCodec.encode(changed), target, original.name).original)
        val afterEviction = cache.decode(String(payload.toCharArray()), target, original.name)
        assertNotSame(decoded, afterEviction)
        assertEquals(original, afterEviction.original)
    }

    @Test fun `simultaneous snapshots share one proof without rebinding it to intervening readers`() {
        val cache = OriginalEvidenceCache()
        val stored = ArtTrackOriginalNameCodec.encode(original)
        val first = cache.canonicalize(String(stored.toCharArray()), target, original.name)!!
        val decoded = cache.decode(first, target, original.name)
        val second = cache.canonicalize(String(stored.toCharArray()), target, original.name)!!
        assertSame(first, second)
        repeat(100) {
            // A source fetch and the evaluator can read the same row while older snapshots live.
            val concurrentRead = String(stored.toCharArray())
            assertSame(decoded, cache.decode(concurrentRead, target, original.name))
            assertSame(first, cache.canonicalize(second, target, original.name))
            assertSame(first, cache.canonicalize(concurrentRead, target, original.name))
            assertSame(decoded, cache.decode(first, target, original.name))
        }
    }

    @Test fun `canonical proof requires full equality even when changed evidence has the same hash`() {
        val cache = OriginalEvidenceCache()
        val firstOriginal = original.copy(albumId = "MPREAa")
        val changedOriginal = original.copy(albumId = "MPREBB")
        val firstPayload = ArtTrackOriginalNameCodec.encode(firstOriginal)
        val changedPayload = ArtTrackOriginalNameCodec.encode(changedOriginal)
        assertEquals(firstPayload.hashCode(), changedPayload.hashCode())
        assertNotEquals(firstPayload, changedPayload)
        val first = cache.canonicalize(firstPayload, target, original.name)
        val changed = cache.canonicalize(changedPayload, target, original.name)
        assertNotSame(first, changed)
        assertEquals(firstOriginal, cache.decode(first, target, original.name).original)
        assertEquals(changedOriginal, cache.decode(changed, target, original.name).original)
        assertNull(cache.decode(cache.canonicalize("{}", target, original.name), target, original.name).original)
        assertSame(first, cache.canonicalize(String(firstPayload.toCharArray()), target, original.name))
    }

    @Test fun `canonicalization obeys the existing entry and text retention limits`() {
        val stored = ArtTrackOriginalNameCodec.encode(original)
        for (cache in listOf(OriginalEvidenceCache(maxEntries = 1),
            OriginalEvidenceCache(maxCharacters = stored.length + target.id.length + original.name.length))) {
            val first = cache.canonicalize(String(stored.toCharArray()), target, original.name)
            cache.canonicalize("{}", target, original.name)
            val afterEviction = cache.canonicalize(String(stored.toCharArray()), target, original.name)
            assertNotSame(first, afterEviction)
            assertEquals(first, afterEviction)
            assertEquals(original, cache.decode(afterEviction, target, original.name).original)
        }
        val disabled = OriginalEvidenceCache(maxCharacters = 1)
        assertNotSame(disabled.canonicalize(String(stored.toCharArray()), target, original.name),
            disabled.canonicalize(String(stored.toCharArray()), target, original.name))
    }
}
