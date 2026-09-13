package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.utils.ArtistDisplayProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtistDisplayRepositoryTest {
    @Test fun persistedLinksArePublishedOnStartupAndRemovalClearsTheProjectionWithoutNetworkOrWrites() = runBlocking {
        val source = MutableStateFlow(listOf(ArtistDisplayMapping("LA-local", "UCabcdefghijklmnopqrstuv", "Online", "image")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var publications = 0
        val repository = ArtistDisplayRepository(source, scope) {
            publications++
            ArtistDisplayProjection.publish(it)
        }
        try {
            repository.start()
            repository.start()
            assertEquals(1, publications)
            assertEquals("UCabcdefghijklmnopqrstuv", ArtistDisplayProjection.resolve("LA-local")?.id)
            source.value = emptyList()
            assertEquals(2, publications)
            assertNull(ArtistDisplayProjection.resolve("LA-local"))
        } finally {
            scope.cancel()
            ArtistDisplayProjection.publish(emptyList())
        }
    }
}
