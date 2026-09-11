package com.dd3boh.outertune.repositories

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.app.LocaleManagerCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.App
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.CountryCodeToName
import com.dd3boh.outertune.constants.LanguageCodeToName
import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the running App, its injected publisher, Android callbacks, and its real saved settings. */
class ContentLocaleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as App

    @Test
    fun followDeviceSurvivesEnglishAndJapaneseApplicationOverrides() = runBlocking {
        assumeTrue("Per-app framework language settings require Android 13", Build.VERSION.SDK_INT >= 33)
        withRestoredSettings { manager, callbacks ->
            val systemLocales = LocaleManagerCompat.getSystemLocales(app)
            assertTrue("The emulator must have a device locale", !systemLocales.isEmpty)
            val device = systemLocales[0]!!
            // Mapping is checked exhaustively by the JVM resolver tests. This verifies its real input.
            assumeTrue("Use a supported device language for this integration check", device.language in LanguageCodeToName)
            val expected = YouTubeLocale(device.country.takeIf { it in CountryCodeToName } ?: "US", device.language)
            val publisher = app.contentLocale
            setContent(SYSTEM_DEFAULT, SYSTEM_DEFAULT)
            awaitLocale(expected)
            val observed = CopyOnWriteArrayList<YouTubeLocale>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { YouTube.localeUpdates.collect { observed += it } }
            try {
                for (language in listOf("en", "ja", "en")) {
                    setApplicationLocale(manager, callbacks, language)
                    assertEquals(systemLocales, LocaleManagerCompat.getSystemLocales(app))
                    awaitLocale(expected)
                    assertSame("An app-language change must not replace the publisher", publisher, app.contentLocale)
                    assertTrue("App override $language leaked into content requests: $observed", observed.all { it == expected })
                }
                val stored = app.dataStore.data.first()
                assertEquals(SYSTEM_DEFAULT, stored[ContentLanguageKey])
                assertEquals(SYSTEM_DEFAULT, stored[ContentCountryKey])
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test
    fun savedContentEditsPublishWhileApplicationOverrideRemainsActive() = runBlocking {
        assumeTrue("Per-app framework language settings require Android 13", Build.VERSION.SDK_INT >= 33)
        withRestoredSettings { manager, callbacks ->
            val publisher = app.contentLocale
            setApplicationLocale(manager, callbacks, "en")
            setContent("ja", "JP")
            awaitLocale(YouTubeLocale("JP", "ja"))
            setApplicationLocale(manager, callbacks, "ja")
            awaitLocale(YouTubeLocale("JP", "ja"))
            val observed = CopyOnWriteArrayList<YouTubeLocale>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { YouTube.localeUpdates.collect { observed += it } }
            try {
                // No Activity is launched or recreated: the saved preference flow must publish directly.
                setContent("en", "GB")
                awaitLocale(YouTubeLocale("GB", "en"))
                withTimeout(15_000) { while (YouTubeLocale("GB", "en") !in observed) delay(10) }
                setContent("ja", "US")
                awaitLocale(YouTubeLocale("US", "ja"))
                withTimeout(15_000) {
                    while (YouTubeLocale("GB", "en") !in observed || YouTubeLocale("US", "ja") !in observed) delay(10)
                }
                assertEquals("ja", manager.applicationLocales[0]!!.language)
                assertSame(publisher, app.contentLocale)
                assertEquals(listOf(YouTubeLocale("JP", "ja"), YouTubeLocale("GB", "en"), YouTubeLocale("US", "ja")),
                    observed.toList())
            } finally { observer.cancelAndJoin() }
        }
    }

    /** The runner changes the device language through Android Settings after the status marker. */
    @Test
    fun liveSystemLanguageChangeWithApplicationOverride() = runBlocking {
        assumeTrue("Per-app framework language settings require Android 13", Build.VERSION.SDK_INT >= 33)
        val targetTag = InstrumentationRegistry.getArguments().getString("systemLocaleTarget")
        assumeTrue("Opt in with systemLocaleTarget and change the true device language while this waits", !targetTag.isNullOrBlank())
        val target = Locale.forLanguageTag(requireNotNull(targetTag))
        assumeTrue("Use an English or Japanese system language with a supported country",
            target.language in setOf("en", "ja") && target.country in CountryCodeToName)
        val initialDevice = LocaleManagerCompat.getSystemLocales(app)[0]!!
        assumeTrue("The system locale target must differ from the initial device locale",
            initialDevice.toLanguageTag() != target.toLanguageTag())
        withRestoredSettings { manager, callbacks ->
            val publisher = app.contentLocale
            // The fixed app override masks the system-language change in the app's Resources.
            setApplicationLocale(manager, callbacks, target.language)
            val applicationLocales = manager.applicationLocales
            setContent(SYSTEM_DEFAULT, SYSTEM_DEFAULT)
            val defaults = app.dataStore.data.first()
            awaitLocale(resolveContentLocale(defaults, listOf(initialDevice)))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "waiting-for-system-locale:${target.toLanguageTag()}\n")
            })
            withTimeout(90_000) {
                while (LocaleManagerCompat.getSystemLocales(app)[0]?.toLanguageTag() != target.toLanguageTag()) delay(100)
            }
            // This must be driven by the running application's real locale notification path.
            // Do not call the repository's callbacks or restart the App to make the assertion pass.
            awaitLocale(YouTubeLocale(target.country, target.language))
            assertEquals(applicationLocales, manager.applicationLocales)
            assertEquals(target.language, app.resources.configuration.locales[0]!!.language)
            assertSame(publisher, app.contentLocale)
            val stored = app.dataStore.data.first()
            assertEquals(SYSTEM_DEFAULT, stored[ContentLanguageKey])
            assertEquals(SYSTEM_DEFAULT, stored[ContentCountryKey])
        }
    }

    @RequiresApi(33)
    private suspend fun withRestoredSettings(block: suspend (LocaleManager, Channel<Configuration>) -> Unit) {
        val manager = app.getSystemService(LocaleManager::class.java)
        val originalApplicationLocales = manager.applicationLocales
        val original = app.dataStore.data.first()
        val callbacks = Channel<Configuration>(Channel.UNLIMITED)
        val recorder = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                callbacks.trySend(Configuration(newConfig))
            }
            override fun onLowMemory() = Unit
        }
        instrumentation.runOnMainSync { app.registerComponentCallbacks(recorder) }
        try {
            block(manager, callbacks)
        } finally {
            try {
                app.dataStore.edit { settings ->
                    restore(settings, original, ContentLanguageKey)
                    restore(settings, original, ContentCountryKey)
                }
                instrumentation.runOnMainSync { manager.applicationLocales = originalApplicationLocales }
                instrumentation.waitForIdleSync()
                val systemLocales = LocaleManagerCompat.getSystemLocales(app)
                awaitLocale(resolveContentLocale(original, List(systemLocales.size()) { systemLocales[it]!! }))
            } finally {
                instrumentation.runOnMainSync { app.unregisterComponentCallbacks(recorder) }
                callbacks.close()
            }
        }
    }

    private fun restore(
        target: androidx.datastore.preferences.core.MutablePreferences,
        original: Preferences,
        key: Preferences.Key<String>,
    ) {
        original[key]?.let { target[key] = it } ?: target.remove(key)
    }

    private suspend fun setContent(language: String, country: String) {
        app.dataStore.edit { settings ->
            settings[ContentLanguageKey] = language
            settings[ContentCountryKey] = country
        }
    }

    @RequiresApi(33)
    private suspend fun setApplicationLocale(manager: LocaleManager, callbacks: Channel<Configuration>, language: String) {
        while (callbacks.tryReceive().isSuccess) Unit
        val requested = LocaleList.forLanguageTags(language)
        val changed = manager.applicationLocales != requested
        instrumentation.runOnMainSync { manager.applicationLocales = requested }
        if (changed) {
            // Require Android to deliver a real component callback to the running application.
            withTimeout(15_000) {
                while (callbacks.receive().locales[0]?.language != language) Unit
            }
        }
        withTimeout(15_000) {
            while (app.resources.configuration.locales[0]?.language != language) delay(10)
        }
        instrumentation.waitForIdleSync()
        // Let consumers of the callback settle before checking for transient incorrect publications.
        delay(100)
        assertEquals(requested, manager.applicationLocales)
    }

    private suspend fun awaitLocale(expected: YouTubeLocale) {
        withTimeout(15_000) { YouTube.localeUpdates.first { it == expected } }
        assertEquals(expected, YouTube.locale)
    }
}
