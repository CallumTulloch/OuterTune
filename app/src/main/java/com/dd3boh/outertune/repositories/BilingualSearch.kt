package com.dd3boh.outertune.repositories

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.UseLoginForBrowse
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SearchSuggestions
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Each language keeps its own request locale and pagination state; only stable identities merge. */
class BilingualSearch internal constructor(private val runtime: Runtime) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(Runtime(
        requestLocale = { YouTube.locale },
        sectionTitle = { kind -> context.getString(when (kind) {
            SearchCategory.SONG -> R.string.filter_songs
            SearchCategory.VIDEO -> R.string.filter_videos
            SearchCategory.ALBUM -> R.string.filter_albums
            SearchCategory.ARTIST -> R.string.filter_artists
            SearchCategory.PLAYLIST -> R.string.filter_playlists
        }) },
        configurationChanges = bilingualSearchConfigurationChanges(YouTube.localeUpdates, context.dataStore.data),
    ))

    internal class Runtime(
        val requestLocale: suspend () -> YouTubeLocale = { YouTube.locale },
        val contextKey: () -> String = {
            searchAuthenticationContextKey(YouTube.useLoginForBrowse, YouTube.cookie, YouTube.visitorData, YouTube.dataSyncId)
        },
        val searchSummary: suspend (String, YouTubeLocale) -> Result<SearchSummaryPage> = YouTube::searchSummary,
        val search: suspend (String, YouTube.SearchFilter, YouTubeLocale) -> Result<SearchResult> = YouTube::search,
        val searchContinuation: suspend (String, YouTubeLocale) -> Result<SearchResult> = YouTube::searchContinuation,
        val searchSuggestions: suspend (String, YouTubeLocale) -> Result<SearchSuggestions> = YouTube::searchSuggestions,
        val sectionTitle: (SearchCategory) -> String = { "" },
        val configurationChanges: Flow<Unit> = emptyFlow(),
    )

    val configurationChanges: Flow<Unit> get() = runtime.configurationChanges
    private var pendingSummary: PendingSummary? = null

    private data class PendingSummary(
        val query: String,
        val locale: YouTubeLocale,
        val contextKey: String,
        val pages: Map<String, SearchSummaryPage>,
    )

    fun summaryNeedsRetry(query: String): Boolean = pendingSummary?.query == query

    fun clearSummary(query: String) {
        if (pendingSummary?.query == query) pendingSummary = null
    }

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> = checked {
        val locale = runtime.requestLocale()
        val contextKey = runtime.contextKey()
        val locales = requestLocales(locale)
        val cached = pendingSummary?.takeIf {
            it.query == query && it.locale == locale && it.contextKey == contextKey
        }?.pages.orEmpty()
        checkContext(locale, contextKey)
        val results = parallel(locales.filterNot { it.hl in cached }) { runtime.searchSummary(query, it) }
        checkContext(locale, contextKey)
        val pages = cached + results.mapNotNull { (requested, result) -> result.getOrNull()?.let { requested.hl to it } }
        pendingSummary = if (pages.size < locales.size) PendingSummary(query, locale, contextKey, pages) else null
        if (pages.isEmpty()) throw firstFailure(results.map { it.second })
        mergeSummary(pages[locale.hl], pages["en"].takeIf { locale.hl != "en" })
    }

    suspend fun search(query: String, filter: YouTube.SearchFilter): Result<SearchResult> = checked {
        val locale = runtime.requestLocale()
        val cursor = SearchCursor(query = query, filter = filter.value, configuredLocale = locale, contextKey = runtime.contextKey(),
            branches = requestLocales(locale).map { SearchBranch(it) })
        advance(cursor)
    }

    suspend fun searchContinuation(continuation: String): Result<SearchResult> = checked {
        require(continuation.startsWith(CURSOR_PREFIX)) { "Unknown bilingual search continuation" }
        val cursor = decodeCursor(continuation.removePrefix(CURSOR_PREFIX))
        val currentLocale = runtime.requestLocale()
        require(cursor.configuredLocale == currentLocale) { "Search language or country changed" }
        advance(cursor)
    }

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> = checked {
        val locale = runtime.requestLocale()
        val contextKey = runtime.contextKey()
        checkContext(locale, contextKey)
        val results = parallel(requestLocales(locale)) { runtime.searchSuggestions(query, it) }
        checkContext(locale, contextKey)
        val pages = results.mapNotNull { it.second.getOrNull() }
        if (pages.isEmpty()) throw firstFailure(results.map { it.second })
        SearchSuggestions(
            queries = pages.flatMap { it.queries }.distinct(),
            recommendedItems = pages.flatMap { it.recommendedItems }.distinctBy(YTItem::searchIdentity),
        )
    }

    private suspend fun advance(cursor: SearchCursor): SearchResult {
        checkContext(cursor.configuredLocale, cursor.contextKey)
        val results = parallel(cursor.branches) { branch ->
            if (branch.initial) runtime.search(cursor.query, YouTube.SearchFilter(cursor.filter), branch.locale)
            else runtime.searchContinuation(requireNotNull(branch.continuation), branch.locale)
        }
        checkContext(cursor.configuredLocale, cursor.contextKey)
        val successes = results.mapNotNull { it.second.getOrNull() }
        if (successes.isEmpty()) throw firstFailure(results.map { it.second })
        val remaining = results.mapNotNull { (branch, result) ->
            result.fold(
                onSuccess = { page -> page.continuation?.let { branch.copy(initial = false, continuation = it) } },
                onFailure = { branch },
            )
        }
        return SearchResult(
            items = successes.flatMap { it.items }.distinctBy(YTItem::searchIdentity),
            continuation = remaining.takeIf { it.isNotEmpty() }?.let {
                CURSOR_PREFIX + encodeCursor(cursor.copy(branches = it))
            },
        )
    }

    private fun mergeSummary(configured: SearchSummaryPage?, english: SearchSummaryPage?): SearchSummaryPage {
        val seen = mutableSetOf<Pair<String, String>>()
        val groups = configured?.summaries.orEmpty().mapNotNull { group ->
            group.copy(items = group.items.filter { seen.add(it.searchIdentity()) }).takeIf { it.items.isNotEmpty() }
        }.toMutableList()
        english?.summaries.orEmpty().flatMap { it.items }.filter { seen.add(it.searchIdentity()) }
            .groupBy(YTItem::searchCategory).forEach { (kind, items) ->
                val index = groups.indexOfFirst { group ->
                    !group.isTopResult && group.items.isNotEmpty() && group.items.all { it.searchCategory() == kind }
                }
                if (index >= 0) groups[index] = groups[index].copy(items = groups[index].items + items)
                else groups += SearchSummary(runtime.sectionTitle(kind), items)
            }
        return SearchSummaryPage(groups)
    }

    private suspend fun checkContext(expectedLocale: YouTubeLocale, expected: String) {
        check(runtime.requestLocale() == expectedLocale) { "Search language or country changed" }
        check(runtime.contextKey() == expected) { "Search authentication context changed" }
    }

    private suspend fun <K, T> parallel(keys: List<K>, fetch: suspend (K) -> Result<T>): List<Pair<K, Result<T>>> =
        coroutineScope { keys.map { key -> async { key to checkedResult { fetch(key) } } }.awaitAll() }

    private suspend fun <T> checked(block: suspend () -> T): Result<T> = checkedResult { Result.success(block()) }

    private suspend fun <T> checkedResult(block: suspend () -> Result<T>): Result<T> = try {
        val result = block()
        currentCoroutineContext().ensureActive()
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private data class SearchCursor(
        val version: Int = 1,
        val query: String,
        val filter: String,
        val configuredLocale: YouTubeLocale,
        val contextKey: String,
        val branches: List<SearchBranch>,
    )

    private data class SearchBranch(
        val locale: YouTubeLocale,
        val initial: Boolean = true,
        val continuation: String? = null,
    )

    companion object {
        private const val CURSOR_PREFIX = "bilingual-search:"
        private val json = Json

        private fun requestLocales(locale: YouTubeLocale): List<YouTubeLocale> =
            listOf(locale, locale.copy(hl = "en")).distinctBy { it.hl }

        private fun firstFailure(results: List<Result<*>>): Throwable =
            results.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IllegalStateException("Search returned no response")

        // Explicit JSON keeps this app module independent of the serialization compiler plugin.
        private fun encodeCursor(cursor: SearchCursor): String = buildJsonObject {
            put("version", cursor.version)
            put("query", cursor.query)
            put("filter", cursor.filter)
            put("configuredLocale", encodeLocale(cursor.configuredLocale))
            put("contextKey", cursor.contextKey)
            put("branches", JsonArray(cursor.branches.map { branch -> buildJsonObject {
                put("locale", encodeLocale(branch.locale))
                put("initial", branch.initial)
                put("continuation", branch.continuation?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }.toString()

        private fun encodeLocale(locale: YouTubeLocale): JsonObject = buildJsonObject {
            put("gl", locale.gl)
            put("hl", locale.hl)
        }

        private fun decodeCursor(value: String): SearchCursor {
            val root = json.parseToJsonElement(value) as? JsonObject
                ?: error("Search continuation must be an object")
            val version = root["version"] as? JsonPrimitive
            require(version != null && !version.isString && version.intOrNull == 1) {
                "Unsupported search continuation version"
            }
            val configuredLocale = decodeLocale(root["configuredLocale"])
            val allowedLocales = requestLocales(configuredLocale)
            val encodedBranches = root["branches"] as? JsonArray
                ?: error("Search continuation requires language branches")
            require(encodedBranches.size in 1..allowedLocales.size) { "Invalid search continuation branch count" }
            val branches = encodedBranches.map { element ->
                val branch = element as? JsonObject ?: error("Search continuation branch must be an object")
                val locale = decodeLocale(branch["locale"])
                require(locale in allowedLocales) { "Search continuation has an unrelated language or country" }
                val initialValue = branch["initial"] as? JsonPrimitive
                require(initialValue != null && !initialValue.isString && initialValue.booleanOrNull != null) {
                    "Search continuation branch requires an initial flag"
                }
                val initial = initialValue.booleanOrNull!!
                val token = when (val encodedToken = branch["continuation"]) {
                    null, JsonNull -> null
                    is JsonPrimitive -> {
                        require(encodedToken.isString && encodedToken.content.isNotBlank()) { "Invalid language continuation token" }
                        encodedToken.content
                    }
                    else -> error("Invalid language continuation token")
                }
                require(if (initial) token == null else token != null) { "Inconsistent search continuation branch state" }
                SearchBranch(locale, initial, token)
            }
            require(branches.map { it.locale }.distinct().size == branches.size) { "Duplicate search continuation language" }
            val ordering = branches.map { allowedLocales.indexOf(it.locale) }
            require(ordering == ordering.sorted()) { "Search continuation languages are out of order" }
            return SearchCursor(
                query = root.requiredText("query"), filter = root.requiredText("filter"),
                configuredLocale = configuredLocale, contextKey = root.requiredText("contextKey"), branches = branches,
            )
        }

        private fun decodeLocale(value: JsonElement?): YouTubeLocale {
            val locale = value as? JsonObject ?: error("Search continuation requires a locale object")
            val gl = locale.requiredText("gl")
            val hl = locale.requiredText("hl")
            require(gl.none(Char::isWhitespace) && hl.none(Char::isWhitespace)) { "Invalid search continuation locale" }
            return YouTubeLocale(gl, hl)
        }

        private fun JsonObject.requiredText(key: String): String {
            val value = get(key) as? JsonPrimitive
            require(value != null && value.isString && value.content.isNotBlank()) { "Search continuation requires text for $key" }
            return value.content
        }

    }
}

/** System-locale changes can publish a new request locale without changing saved preferences. */
internal fun bilingualSearchConfigurationChanges(
    locales: Flow<YouTubeLocale>,
    preferences: Flow<Preferences>,
): Flow<Unit> = combine(locales, preferences) { locale, settings ->
    locale to searchAuthenticationContextKey(
        settings[UseLoginForBrowse] != false, settings[InnerTubeCookieKey],
        settings[VisitorDataKey], settings[DataSyncIdKey],
    )
}.distinctUntilChanged().drop(1).map { Unit }

/** Account/visitor identifiers are never stored in continuation state, only their combined digest. */
internal fun searchAuthenticationContextKey(useLogin: Boolean, cookie: String?, visitorData: String?, dataSyncId: String?): String {
    val input = listOf(useLogin.toString(), cookie.orEmpty(), visitorData.orEmpty(), dataSyncId.orEmpty())
        .joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** The same raw id in two result kinds is not the same entity. Video/audio identity uses its video id. */
internal fun YTItem.searchIdentity(): Pair<String, String> = when (this) {
    is SongItem -> "SONG" to id
    is AlbumItem -> "ALBUM" to id
    is ArtistItem -> "ARTIST" to id
    is PlaylistItem -> "PLAYLIST" to id
}

internal enum class SearchCategory { SONG, VIDEO, ALBUM, ARTIST, PLAYLIST }

private fun YTItem.searchCategory(): SearchCategory = when (this) {
    is SongItem -> if (endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
        in setOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC") || artistCredit?.evidence.orEmpty().any { it.startsWith("video-source:") })
        SearchCategory.VIDEO else SearchCategory.SONG
    is AlbumItem -> SearchCategory.ALBUM
    is ArtistItem -> SearchCategory.ARTIST
    is PlaylistItem -> SearchCategory.PLAYLIST
}
