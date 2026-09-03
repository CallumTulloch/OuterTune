package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.SYNC_CD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncUtilsTest {
    private val now = 1_700_000_000L

    @Test
    fun `sync cooldown is thirty minutes in epoch seconds`() {
        assertEquals(30L * 60L, SYNC_CD)
    }

    @Test
    fun `missing sync timestamp is immediately eligible`() {
        assertTrue(isSyncEligible(lastSyncEpochSeconds = 0L, currentEpochSeconds = now))
    }

    @Test
    fun `sync is suppressed before cooldown expires`() {
        assertFalse(
            isSyncEligible(
                lastSyncEpochSeconds = now - SYNC_CD + 1L,
                currentEpochSeconds = now,
            )
        )
    }

    @Test
    fun `sync is eligible exactly at cooldown boundary`() {
        assertTrue(
            isSyncEligible(
                lastSyncEpochSeconds = now - SYNC_CD,
                currentEpochSeconds = now,
            )
        )
    }

    @Test
    fun `sync is eligible after cooldown expires`() {
        assertTrue(
            isSyncEligible(
                lastSyncEpochSeconds = now - SYNC_CD - 1L,
                currentEpochSeconds = now,
            )
        )
    }

    @Test
    fun `future sync timestamp remains in cooldown`() {
        assertFalse(isSyncEligible(lastSyncEpochSeconds = now + 1L, currentEpochSeconds = now))
    }

    @Test
    fun `complete remote data combines all successful sources in source order`() {
        val result = combineCompleteRemoteData(
            listOf(
                Result.success(listOf("library-1", "library-2")),
                Result.success(listOf("upload-1")),
            )
        )

        assertEquals(listOf("library-1", "library-2", "upload-1"), result.getOrThrow())
    }

    @Test
    fun `complete remote data accepts a legitimately empty response`() {
        val result = combineCompleteRemoteData<String>(
            listOf(Result.success(emptyList()), Result.success(emptyList()))
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `complete remote data fails when either source fails`() {
        listOf(
            listOf(
                Result.failure<List<String>>(IllegalStateException("library failed")),
                Result.success(listOf("upload-1")),
            ),
            listOf(
                Result.success(listOf("library-1")),
                Result.failure(IllegalStateException("uploads failed")),
            )
        ).forEach { sources ->
            val result = combineCompleteRemoteData(sources)

            assertTrue(result.isFailure)
        }
    }

    @Test
    fun `manual refresh stays active and runs local before remote`() = runBlocking {
        val localStarted = CompletableDeferred<Unit>()
        val continueLocal = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val coordinator = LibraryRefreshCoordinator {
            calls += "local"
            localStarted.complete(Unit)
            continueLocal.await()
            true
        }

        val result = async {
            coordinator.refresh {
                calls += "remote"
                true
            }
        }
        localStarted.await()
        assertTrue(coordinator.isRefreshing.value)

        continueLocal.complete(Unit)

        assertTrue(result.await())
        assertEquals(listOf("local", "remote"), calls)
        assertFalse(coordinator.isRefreshing.value)
    }

    @Test
    fun `second manual refresh is rejected while the first is running`() = runBlocking {
        val localStarted = CompletableDeferred<Unit>()
        val continueLocal = CompletableDeferred<Unit>()
        var localCalls = 0
        var remoteCalls = 0
        val coordinator = LibraryRefreshCoordinator {
            localCalls++
            localStarted.complete(Unit)
            continueLocal.await()
            true
        }

        val first = async {
            coordinator.refresh {
                remoteCalls++
                true
            }
        }
        localStarted.await()

        assertFalse(
            coordinator.refresh {
                remoteCalls++
                true
            }
        )
        continueLocal.complete(Unit)

        assertTrue(first.await())
        assertEquals(1, localCalls)
        assertEquals(1, remoteCalls)
    }

    @Test
    fun `unsuccessful local refresh skips remote and releases state`() = runBlocking {
        var remoteCalled = false
        val coordinator = LibraryRefreshCoordinator { false }

        assertFalse(
            coordinator.refresh {
                remoteCalled = true
                true
            }
        )
        assertFalse(remoteCalled)
        assertFalse(coordinator.isRefreshing.value)
    }

    @Test
    fun `unsuccessful remote refresh is returned and releases state`() = runBlocking {
        val coordinator = LibraryRefreshCoordinator { true }

        assertFalse(coordinator.refresh { false })
        assertFalse(coordinator.isRefreshing.value)
    }

    @Test
    fun `cancelled refresh releases state and allows retry`() = runBlocking {
        val localStarted = CompletableDeferred<Unit>()
        val continueLocal = CompletableDeferred<Unit>()
        var block = true
        val coordinator = LibraryRefreshCoordinator {
            if (block) {
                localStarted.complete(Unit)
                continueLocal.await()
            }
            true
        }

        val first = launch { coordinator.refresh() }
        localStarted.await()
        first.cancelAndJoin()

        assertFalse(coordinator.isRefreshing.value)
        block = false
        assertTrue(coordinator.refresh())
    }
}
