package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.zionhuang.innertube.models.YouTubeLocale
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

class ContentConsumerConfigurationTest {
    private val japanese = YouTubeLocale("JP", "ja")
    private fun settings() = preferencesOf(ContentLanguageKey to "ja", ContentCountryKey to "JP")

    @Test
    fun `search invalidates for a published language or country without a preferences emission`() = runBlocking {
        val locales = MutableStateFlow(japanese)
        val preferences = MutableStateFlow<Preferences>(settings())
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bilingualSearchConfigurationChanges(locales, preferences).collect { changes.send(it) }
        }
        try {
            // Wait for initial subscriptions and verify that opening a search does not invalidate itself.
            assertNull(withTimeoutOrNull(100) { changes.receive() })
            locales.value = japanese.copy(hl = "fr")
            withTimeout(5_000) { changes.receive() }
            locales.value = japanese.copy(hl = "fr", gl = "FR")
            withTimeout(5_000) { changes.receive() }
            assertEquals(settings(), preferences.value)
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun `search waits for the published locale and keeps checkbox changes separate from authentication`() = runBlocking {
        val locales = MutableStateFlow(japanese)
        val preferences = MutableStateFlow<Preferences>(settings())
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bilingualSearchConfigurationChanges(locales, preferences).collect { changes.send(it) }
        }
        try {
            assertNull(withTimeoutOrNull(100) { changes.receive() })
            preferences.value = preferencesOf(ContentLanguageKey to "fr", PreferEnglishOriginalKey to false)
            assertNull(withTimeoutOrNull(100) { changes.receive() })
            locales.value = japanese.copy(hl = "fr")
            withTimeout(5_000) { changes.receive() }
            preferences.value = preferencesOf(ContentLanguageKey to "fr", PreferEnglishOriginalKey to false,
                DataSyncIdKey to "another-account")
            withTimeout(5_000) { changes.receive() }
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun `album refresh follows published locale and account even if only one input changes`() = runBlocking {
        val locales = MutableStateFlow(japanese)
        val preferences = MutableStateFlow<Preferences>(settings())
        val changes = Channel<YouTubeLocale>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            metadataRequestConfiguration(locales, preferences).collect { changes.send(it) }
        }
        try {
            assertEquals(japanese, withTimeout(5_000) { changes.receive() })
            locales.value = japanese.copy(hl = "fr")
            assertEquals(locales.value, withTimeout(5_000) { changes.receive() })
            preferences.value = preferencesOf(ContentLanguageKey to "de", PreferEnglishOriginalKey to false)
            assertNull(withTimeoutOrNull(100) { changes.receive() })
            preferences.value = preferencesOf(ContentLanguageKey to "de", PreferEnglishOriginalKey to false,
                DataSyncIdKey to "another-account")
            assertEquals(locales.value, withTimeout(5_000) { changes.receive() })
            locales.value = japanese.copy(hl = "de", gl = "DE")
            assertEquals(locales.value, withTimeout(5_000) { changes.receive() })
        } finally { collector.cancelAndJoin() }
    }
}
