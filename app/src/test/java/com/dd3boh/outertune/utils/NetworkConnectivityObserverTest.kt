package com.dd3boh.outertune.utils

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkConnectivityObserverTest {
    @Test
    fun `initial snapshot is available to every collector before callbacks arrive`() = runBlocking {
        val backend = FakeBackend(DefaultNetworkSnapshot("wifi", true))
        val monitor = DefaultNetworkConnectivityMonitor(backend)
        assertTrue(monitor.networkStatus.value)
        assertEquals(listOf(false, true), backend.queryWasRegistered)

        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) {
            monitor.networkStatus.take(2).toList(first)
        }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) {
            monitor.networkStatus.take(2).toList(second)
        }
        backend.callback.onCapabilitiesChanged("wifi", false)
        firstJob.join()
        secondJob.join()

        assertEquals(listOf(true, false), first)
        assertEquals(first, second)
        monitor.unregister()
    }

    @Test
    fun `availability waits for validation and validation loss is observed`() {
        val backend = FakeBackend(DefaultNetworkSnapshot(null, false))
        val monitor = DefaultNetworkConnectivityMonitor(backend)

        backend.callback.onAvailable("wifi")
        assertFalse(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("wifi", false)
        assertFalse(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("wifi", true)
        assertTrue(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("wifi", false)
        assertFalse(monitor.networkStatus.value)
        assertEquals(2, backend.queryCount)
        monitor.unregister()
    }

    @Test
    fun `old network loss and capability updates cannot disconnect the new default`() {
        val backend = FakeBackend(DefaultNetworkSnapshot("wifi", true))
        val monitor = DefaultNetworkConnectivityMonitor(backend)
        backend.callback.onAvailable("wifi")
        assertTrue(monitor.networkStatus.value)

        backend.callback.onAvailable("mobile")
        assertTrue("A normal handover must not trigger offline fallback", monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("mobile", true)
        backend.callback.onLost("wifi")
        backend.callback.onCapabilitiesChanged("wifi", false)
        assertTrue(monitor.networkStatus.value)

        backend.callback.onLost("mobile")
        assertFalse(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("mobile", true)
        assertFalse(monitor.networkStatus.value)
        assertEquals(2, backend.queryCount)
        monitor.unregister()
    }

    @Test
    fun `handover only becomes disconnected when new default fails validation`() {
        val backend = FakeBackend(DefaultNetworkSnapshot("wifi", true))
        val monitor = DefaultNetworkConnectivityMonitor(backend)

        backend.callback.onAvailable("mobile")
        backend.callback.onLost("wifi")
        assertTrue(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("mobile", false)
        assertFalse(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("mobile", true)
        assertTrue(monitor.networkStatus.value)
        monitor.unregister()
    }

    @Test
    fun `actual loss before handover stays disconnected until the new default validates`() {
        val backend = FakeBackend(DefaultNetworkSnapshot("wifi", true))
        val monitor = DefaultNetworkConnectivityMonitor(backend)

        backend.callback.onLost("wifi")
        backend.callback.onAvailable("mobile")
        assertFalse(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("mobile", true)
        assertTrue(monitor.networkStatus.value)
        monitor.unregister()
    }

    @Test
    fun `callbacks received during registration win over the following stale query`() {
        listOf(false, true).forEach { staleConnection ->
            val backend = FakeBackend(DefaultNetworkSnapshot("wifi", staleConnection))
            backend.duringRegister = {
                backend.callback.onAvailable("mobile")
                backend.callback.onCapabilitiesChanged("mobile", !staleConnection)
            }

            val monitor = DefaultNetworkConnectivityMonitor(backend)
            assertEquals(!staleConnection, monitor.networkStatus.value)
            backend.callback.onLost("wifi")
            assertEquals(!staleConnection, monitor.networkStatus.value)
            monitor.unregister()
        }
    }

    @Test
    fun `second snapshot catches a disconnect in the callback registration gap`() {
        val backend = FakeBackend(DefaultNetworkSnapshot("wifi", true))
        backend.duringRegister = { backend.snapshot = DefaultNetworkSnapshot(null, false) }

        val monitor = DefaultNetworkConnectivityMonitor(backend)
        assertFalse(monitor.networkStatus.value)
        assertEquals(listOf(false, true), backend.queryWasRegistered)
        monitor.unregister()
    }

    @Test
    fun `callback during initial query wins over a stale connected snapshot`() {
        val backend = FakeBackend(DefaultNetworkSnapshot("wifi", true))
        backend.duringQuery = {
            backend.callback.onAvailable("mobile")
            backend.callback.onCapabilitiesChanged("mobile", false)
        }

        val monitor = DefaultNetworkConnectivityMonitor(backend)
        assertFalse(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("mobile", true)
        assertTrue(monitor.networkStatus.value)
        monitor.unregister()
    }

    @Test
    fun `callback during initial query wins over a stale disconnected snapshot`() {
        val backend = FakeBackend(DefaultNetworkSnapshot(null, false))
        backend.duringQuery = {
            backend.callback.onAvailable("wifi")
            backend.callback.onCapabilitiesChanged("wifi", true)
        }

        val monitor = DefaultNetworkConnectivityMonitor(backend)
        assertTrue(monitor.networkStatus.value)
        monitor.unregister()
    }

    @Test
    fun `loss during initial query cannot be overwritten by the old connection`() {
        val backend = FakeBackend(DefaultNetworkSnapshot("wifi", true))
        backend.duringQuery = {
            backend.callback.onAvailable("wifi")
            backend.callback.onLost("wifi")
        }

        val monitor = DefaultNetworkConnectivityMonitor(backend)
        assertFalse(monitor.networkStatus.value)
        backend.callback.onCapabilitiesChanged("wifi", true)
        assertFalse(monitor.networkStatus.value)
        monitor.unregister()
    }

    @Test
    fun `unregister is idempotent and late callbacks cannot change its last state`() {
        val backend = FakeBackend(DefaultNetworkSnapshot("wifi", true))
        val monitor = DefaultNetworkConnectivityMonitor(backend)
        monitor.unregister()
        monitor.unregister()

        backend.callback.onLost("wifi")
        backend.callback.onAvailable("mobile")
        backend.callback.onCapabilitiesChanged("mobile", false)
        assertTrue(monitor.networkStatus.value)
        assertEquals(1, backend.unregisterCount)
    }

    private class FakeBackend(
        var snapshot: DefaultNetworkSnapshot<String>,
    ) : DefaultNetworkBackend<String> {
        lateinit var callback: DefaultNetworkCallback<String>
        var duringRegister: () -> Unit = {}
        var duringQuery: () -> Unit = {}
        val queryWasRegistered = mutableListOf<Boolean>()
        var queryCount = 0
        var unregisterCount = 0

        override fun register(callback: DefaultNetworkCallback<String>) {
            this.callback = callback
            duringRegister()
        }

        override fun current(): DefaultNetworkSnapshot<String> {
            queryWasRegistered += ::callback.isInitialized
            queryCount++
            if (::callback.isInitialized) duringQuery()
            return snapshot
        }

        override fun unregister() {
            unregisterCount++
        }
    }
}
