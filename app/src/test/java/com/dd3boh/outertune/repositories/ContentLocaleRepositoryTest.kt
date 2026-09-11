package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.CountryCodeToName
import com.dd3boh.outertune.constants.LanguageCodeToName
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.zionhuang.innertube.models.YouTubeLocale
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentLocaleRepositoryTest {
    private val japanese = YouTubeLocale(gl = "JP", hl = "ja")
    private val french = YouTubeLocale(gl = "FR", hl = "fr")

    @Test
    fun `every supported explicit language and country is preserved exactly`() {
        for (language in LanguageCodeToName.keys) {
            for (country in CountryCodeToName.keys) {
                val preferences = preferencesOf(ContentLanguageKey to language, ContentCountryKey to country)
                assertEquals(
                    "Explicit content setting $language/$country",
                    YouTubeLocale(gl = country, hl = language),
                    resolveContentLocale(preferences, listOf(Locale.JAPAN)),
                )
            }
        }
    }

    @Test
    fun `missing and system default preferences use the actual device locale`() {
        assertEquals(japanese, resolveContentLocale(emptyPreferences(), listOf(Locale.JAPAN)))
        assertEquals(japanese, resolveContentLocale(
            preferencesOf(ContentLanguageKey to SYSTEM_DEFAULT, ContentCountryKey to SYSTEM_DEFAULT),
            listOf(Locale.JAPAN),
        ))
    }

    @Test
    fun `language and country defaults are resolved independently`() {
        assertEquals(YouTubeLocale("JP", "de"), resolveContentLocale(
            preferencesOf(ContentLanguageKey to "de", ContentCountryKey to SYSTEM_DEFAULT),
            listOf(Locale.JAPAN),
        ))
        assertEquals(YouTubeLocale("GB", "ja"), resolveContentLocale(
            preferencesOf(ContentLanguageKey to SYSTEM_DEFAULT, ContentCountryKey to "GB"),
            listOf(Locale.JAPAN),
        ))
    }

    @Test
    fun `empty and unsupported primary system locales have stable English US fallback`() {
        assertEquals(YouTubeLocale("US", "en"), resolveContentLocale(emptyPreferences(), emptyList()))
        assertEquals(YouTubeLocale("US", "en"), resolveContentLocale(
            emptyPreferences(), listOf(Locale.forLanguageTag("xx-ZZ"), Locale.JAPAN),
        ))
        assertEquals(YouTubeLocale("JP", "en"), resolveContentLocale(
            emptyPreferences(), listOf(Locale.forLanguageTag("xx-JP")),
        ))
        assertEquals(YouTubeLocale("US", "ja"), resolveContentLocale(
            emptyPreferences(), listOf(Locale.forLanguageTag("ja-ZZ")),
        ))
    }

    @Test
    fun `system fallback retains existing base language then normalized tag ordering`() {
        val cases = mapOf(
            "en-GB" to YouTubeLocale("GB", "en"),
            "fr-CA" to YouTubeLocale("CA", "fr"),
            "pt-PT" to YouTubeLocale("PT", "pt"),
            "zh-Hant-TW" to YouTubeLocale("TW", "zh-TW"),
            "zh-Hant-HK" to YouTubeLocale("HK", "zh-HK"),
            "zh-CN" to YouTubeLocale("US", "zh-CN"),
        )
        for ((tag, expected) in cases) {
            assertEquals(tag, expected, resolveContentLocale(emptyPreferences(), listOf(Locale.forLanguageTag(tag))))
        }
    }

    @Test
    fun `changing the app process locale to English or Japanese cannot change content defaults`() {
        val original = Locale.getDefault()
        try {
            for (appLocale in listOf(Locale.US, Locale.JAPAN)) {
                Locale.setDefault(appLocale)
                assertEquals(japanese, resolveContentLocale(emptyPreferences(), listOf(Locale.JAPAN)))
                assertEquals(french, resolveContentLocale(emptyPreferences(), listOf(Locale.FRANCE)))
                assertEquals(YouTubeLocale("US", "en"), resolveContentLocale(emptyPreferences(), emptyList()))
                assertEquals(YouTubeLocale("CA", "fr-CA"), resolveContentLocale(
                    preferencesOf(ContentLanguageKey to "fr-CA", ContentCountryKey to "CA"),
                    listOf(Locale.JAPAN),
                ))
            }
        } finally { Locale.setDefault(original) }
    }

    @Test
    fun `device language and region changes emit without a preferences emission`() = runBlocking {
        val preferences = MutableStateFlow<Preferences>(emptyPreferences())
        val systemLocales = MutableStateFlow(listOf(Locale.JAPAN))
        val emissions = Channel<YouTubeLocale>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            contentLocaleChanges(preferences, systemLocales).collect { emissions.send(it) }
        }
        try {
            assertEquals(japanese, withTimeout(5_000) { emissions.receive() })
            systemLocales.value = listOf(Locale.FRANCE)
            assertEquals(french, withTimeout(5_000) { emissions.receive() })
            systemLocales.value = listOf(Locale.CANADA_FRENCH)
            assertEquals(YouTubeLocale("CA", "fr"), withTimeout(5_000) { emissions.receive() })
            assertEquals(emptyPreferences(), preferences.value)
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun `explicit settings ignore system changes and switching back to default uses latest device state`() = runBlocking {
        val preferences = MutableStateFlow<Preferences>(
            preferencesOf(ContentLanguageKey to "ja", ContentCountryKey to "JP"),
        )
        val systemLocales = MutableStateFlow(listOf(Locale.US))
        val emissions = Channel<YouTubeLocale>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            contentLocaleChanges(preferences, systemLocales).collect { emissions.send(it) }
        }
        try {
            assertEquals(japanese, withTimeout(5_000) { emissions.receive() })
            systemLocales.value = listOf(Locale.FRANCE)
            assertNull(withTimeoutOrNull(100) { emissions.receive() })
            preferences.value = preferencesOf(ContentLanguageKey to SYSTEM_DEFAULT, ContentCountryKey to SYSTEM_DEFAULT)
            assertEquals(french, withTimeout(5_000) { emissions.receive() })
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun `English display checkbox and unrelated preferences cannot emit new content locales`() = runBlocking {
        val preferences = MutableStateFlow<Preferences>(emptyPreferences())
        val systemLocales = MutableStateFlow(listOf(Locale.JAPAN))
        val emissions = Channel<YouTubeLocale>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            contentLocaleChanges(preferences, systemLocales).collect { emissions.send(it) }
        }
        try {
            assertEquals(japanese, withTimeout(5_000) { emissions.receive() })
            preferences.value = preferencesOf(PreferEnglishOriginalKey to false)
            assertNull(withTimeoutOrNull(100) { emissions.receive() })
            preferences.value = preferencesOf(PreferEnglishOriginalKey to true, SkipSilenceKey to true)
            assertNull(withTimeoutOrNull(100) { emissions.receive() })
            preferences.value = preferencesOf(ContentLanguageKey to "ja", ContentCountryKey to "JP")
            assertNull(withTimeoutOrNull(100) { emissions.receive() })
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun `system locale list changes that resolve to the same language and region do not emit`() = runBlocking {
        val preferences = MutableStateFlow<Preferences>(emptyPreferences())
        val systemLocales = MutableStateFlow(listOf(Locale.JAPAN))
        val emissions = Channel<YouTubeLocale>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            contentLocaleChanges(preferences, systemLocales).collect { emissions.send(it) }
        }
        try {
            assertEquals(japanese, withTimeout(5_000) { emissions.receive() })
            systemLocales.value = listOf(Locale.JAPAN, Locale.FRANCE)
            assertNull(withTimeoutOrNull(100) { emissions.receive() })
            systemLocales.value = listOf(Locale.forLanguageTag("ja-Jpan-JP"), Locale.US)
            assertNull(withTimeoutOrNull(100) { emissions.receive() })
            systemLocales.value = listOf(Locale.FRANCE, Locale.JAPAN)
            assertEquals(french, withTimeout(5_000) { emissions.receive() })
        } finally { collector.cancelAndJoin() }
    }
}
