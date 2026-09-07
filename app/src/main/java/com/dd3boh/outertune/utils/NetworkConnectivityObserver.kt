/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.dd3boh.outertune.extensions.hasValidatedInternet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkConnectivityObserver(context: Context) {
    private val monitor = DefaultNetworkConnectivityMonitor(
        AndroidDefaultNetworkBackend(
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        )
    )

    val networkStatus: StateFlow<Boolean> = monitor.networkStatus

    fun unregister() = monitor.unregister()
}

private class AndroidDefaultNetworkBackend(
    private val connectivityManager: ConnectivityManager,
) : DefaultNetworkBackend<Network> {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun register(callback: DefaultNetworkCallback<Network>) {
        val platformCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = callback.onAvailable(network)

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                callback.onCapabilitiesChanged(network, capabilities.hasValidatedInternet())

            override fun onLost(network: Network) = callback.onLost(network)
        }
        connectivityManager.registerDefaultNetworkCallback(platformCallback)
        networkCallback = platformCallback
    }

    override fun current(): DefaultNetworkSnapshot<Network> {
        val network = connectivityManager.activeNetwork
        val connected = connectivityManager.getNetworkCapabilities(network).hasValidatedInternet()
        // Do not seed a connection that stopped being the default during this snapshot.
        return if (network == connectivityManager.activeNetwork) {
            DefaultNetworkSnapshot(network, connected)
        } else {
            DefaultNetworkSnapshot(null, false)
        }
    }

    override fun unregister() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Already removed by the platform; teardown remains idempotent.
        }
    }
}

internal data class DefaultNetworkSnapshot<N>(val network: N?, val isConnected: Boolean)

internal interface DefaultNetworkCallback<N> {
    fun onAvailable(network: N)
    fun onCapabilitiesChanged(network: N, isConnected: Boolean)
    fun onLost(network: N)
}

internal interface DefaultNetworkBackend<N> {
    fun register(callback: DefaultNetworkCallback<N>)
    fun current(): DefaultNetworkSnapshot<N>
    fun unregister()
}

/** Keeps the initial query and callback updates ordered without querying inside callbacks. */
internal class DefaultNetworkConnectivityMonitor<N>(private val backend: DefaultNetworkBackend<N>) {
    private val lock = Any()
    private val initialSnapshot = backend.current()
    private var currentNetwork: N? = initialSnapshot.network
    private var callbackGeneration = 0L
    private var closed = false
    private val status = MutableStateFlow(initialSnapshot.network != null && initialSnapshot.isConnected)
    val networkStatus: StateFlow<Boolean> = status.asStateFlow()

    private val callback = object : DefaultNetworkCallback<N> {
        override fun onAvailable(network: N) = synchronized(lock) {
            if (closed) return@synchronized
            callbackGeneration++
            currentNetwork = network
            // Keep the last confirmed state until this default's capabilities arrive. A normal
            // validated handover must not briefly look like a loss of internet to search clients.
        }

        override fun onCapabilitiesChanged(network: N, isConnected: Boolean) = synchronized(lock) {
            if (closed || network != currentNetwork) return@synchronized
            callbackGeneration++
            status.value = isConnected
        }

        override fun onLost(network: N) = synchronized(lock) {
            if (closed || network != currentNetwork) return@synchronized
            callbackGeneration++
            currentNetwork = null
            status.value = false
        }
    }

    init {
        val generation = synchronized(lock) { callbackGeneration }
        backend.register(callback)
        try {
            // Close the gap between the initial snapshot and callback registration. Events from
            // registration itself also win over this query, not only events received during it.
            val snapshot = backend.current()
            synchronized(lock) {
                if (callbackGeneration == generation) {
                    currentNetwork = snapshot.network
                    status.value = snapshot.network != null && snapshot.isConnected
                }
            }
        } catch (error: Exception) {
            unregister()
            throw error
        }
    }

    fun unregister() {
        val shouldUnregister = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldUnregister) backend.unregister()
    }
}
