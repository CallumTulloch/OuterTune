package com.dd3boh.outertune.extensions

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.TabletUiKey
import com.dd3boh.outertune.constants.YtmSyncKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.zionhuang.innertube.YouTubeSyncPolicy
import com.zionhuang.innertube.utils.parseCookieString

fun Context.isAutoSyncEnabled(): Boolean {
    return YouTubeSyncPolicy.ENABLED && dataStore.get(YtmSyncKey, true) && isUserLoggedIn()
}

fun Context.isUserLoggedIn(): Boolean {
    val cookie = dataStore.get(InnerTubeCookieKey, "")
    return "SAPISID" in parseCookieString(cookie)
}

fun Context.isInternetConnected(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val connected = connectivityManager.getNetworkCapabilities(network).hasValidatedInternet()
    return connected && network == connectivityManager.activeNetwork
}

internal fun NetworkCapabilities?.hasValidatedInternet(): Boolean =
    this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

fun Context.supportsWideScreen() : Boolean {
    val config = resources.configuration
    return config.screenWidthDp >= 600
}

/**
 * If screen is large enough to support tablet UI mode.
 * Current screen must be at least 600dp.
 */
fun Context.tabMode(): Boolean {
    val config = resources.configuration
    val isTablet = config.smallestScreenWidthDp >= 600
    return (isTablet || dataStore.get(TabletUiKey, false)) && config.screenWidthDp >= 600
}

fun Context.isPowerSaver(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isPowerSaveMode
}
