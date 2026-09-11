package com.dd3boh.outertune.repositories

import android.content.ComponentCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.CountryCodeToName
import com.dd3boh.outertune.constants.LanguageCodeToName
import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The only app publisher of the locale used for remote content and stored-name selection. */
@Singleton
class ContentLocaleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : ComponentCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val systemLocales = MutableStateFlow<List<Locale>>(emptyList())
    private val localeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_LOCALE_CHANGED) systemLocales.value = readSystemLocales()
        }
    }
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        context.registerComponentCallbacks(this)
        // A per-app language can mask system changes from application Configuration callbacks.
        // The protected system broadcast still reports a changed device preference order.
        ContextCompat.registerReceiver(context, localeChanges, IntentFilter(Intent.ACTION_LOCALE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        systemLocales.value = readSystemLocales()
        // Publish before any repository or screen can make its first request. Hilt construction
        // alone must not capture an app-specific locale as a content-language default.
        val preferences = runBlocking(Dispatchers.IO) { context.dataStore.data.first() }
        YouTube.locale = resolveContentLocale(preferences, systemLocales.value)
        scope.launch {
            contentLocaleChanges(context.dataStore.data, systemLocales).collect { YouTube.locale = it }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Configuration.locales may start with the app override. This API deliberately returns
        // the device locales, including when only the application's display language changed.
        systemLocales.value = readSystemLocales()
    }

    override fun onLowMemory() = Unit

    private fun readSystemLocales(): List<Locale> = LocaleManagerCompat.getSystemLocales(context).let { locales ->
        List(locales.size()) { index -> locales[index]!! }
    }
}

internal fun contentLocaleChanges(
    preferences: Flow<Preferences>,
    systemLocales: Flow<List<Locale>>,
): Flow<YouTubeLocale> = combine(preferences, systemLocales, ::resolveContentLocale).distinctUntilChanged()

/** Preserve the existing supported-language/country fallback order for "follow device". */
internal fun resolveContentLocale(preferences: Preferences, systemLocales: List<Locale>): YouTubeLocale {
    val deviceLocale = systemLocales.firstOrNull()
    val tag = deviceLocale?.toLanguageTag()?.replace("-Hant", "")
    return YouTubeLocale(
        gl = preferences[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }
            ?: deviceLocale?.country?.takeIf { it in CountryCodeToName } ?: "US",
        hl = preferences[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }
            ?: deviceLocale?.language?.takeIf { it in LanguageCodeToName }
            ?: tag?.takeIf { it in LanguageCodeToName } ?: "en",
    )
}
