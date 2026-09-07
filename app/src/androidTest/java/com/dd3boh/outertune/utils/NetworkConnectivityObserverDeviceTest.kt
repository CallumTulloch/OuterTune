package com.dd3boh.outertune.utils

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.extensions.isInternetConnected
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs on the dedicated emulator: restore both radio settings even if a check fails. */
class NetworkConnectivityObserverDeviceTest {
    @Test
    fun broadcastsActualConnectionLossAndRecoveryToBothConsumers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wifiEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0
        val dataEnabled = Settings.Global.getInt(context.contentResolver, "mobile_data", 0) != 0
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { it.readText() }
        }
        val observer = NetworkConnectivityObserver(context)
        try {
            withTimeout(30_000) { observer.networkStatus.first { it } }
            assertTrue(context.isInternetConnected())
            // receiveAsFlow previously delivered a change to only one of these collectors.
            val firstConsumer = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) { observer.networkStatus.first { !it } }
            }
            val secondConsumer = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) { observer.networkStatus.first { !it } }
            }
            shell("svc wifi disable")
            shell("svc data disable")
            assertFalse(firstConsumer.await())
            assertFalse(secondConsumer.await())

            shell("svc data enable")
            shell("svc wifi enable")
            assertTrue(withTimeout(45_000) { observer.networkStatus.first { it } })
            assertTrue(context.isInternetConnected())
            observer.unregister()
            observer.unregister()
        } finally {
            observer.unregister()
            shell(if (wifiEnabled) "svc wifi enable" else "svc wifi disable")
            shell(if (dataEnabled) "svc data enable" else "svc data disable")
        }
    }
}
