package com.dd3boh.outertune.repositories

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SearchSuggestions
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilingualSearchTest {
    private val japanese = YouTubeLocale("JP", "ja")

    @Test
    fun `summary retains configured groups and ordering while matching by kind and id`() = runBlocking {
        val requested = mutableListOf<YouTubeLocale>()
        val search = service(summary = { _, locale ->
            requested += locale
            Result.success(if (locale.hl == "ja") SearchSummaryPage(listOf(
                SearchSummary("一番上の結果", listOf(song("top")), isTopResult = true),
                SearchSummary("曲", listOf(song("shared", "設定表記"), song("ja-only"))),
            )) else SearchSummaryPage(listOf(
                SearchSummary("Top result", listOf(song("top")), isTopResult = true),
                SearchSummary("Songs", listOf(song("shared", "English"), song("en-only"))),
                SearchSummary("Artists", listOf(artist("shared"))),
            )))
        })
        val result = search.searchSummary("query").getOrThrow().summaries
        assertEquals(listOf(japanese, japanese.copy(hl = "en")), requested)
        assertEquals(listOf("一番上の結果", "曲", "人物"), result.map { it.title })
        assertEquals(listOf("top"), result[0].items.map { it.id })
        assertEquals(listOf("shared", "ja-only", "en-only"), result[1].items.map { it.id })
        assertEquals("設定表記", result[1].items[0].title)
        assertTrue(result[2].items.single() is ArtistItem)
    }

    @Test
    fun `English content setting makes only one request and keeps its own region`() = runBlocking {
        val requests = mutableListOf<YouTubeLocale>()
        val search = service(locale = { YouTubeLocale("GB", "en") }, category = { _, _, locale ->
            requests += locale
            Result.success(SearchResult(listOf(song("one"))))
        })
        assertEquals(1, search.search("query", YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.size)
        assertEquals(listOf(YouTubeLocale("GB", "en")), requests)
    }

    @Test
    fun `a failed initial language retries independently and failed pagination keeps its exact token`() = runBlocking {
        val initialCalls = mutableListOf<String>()
        val continuationCalls = mutableListOf<Pair<String, YouTubeLocale>>()
        var failEnglish = true
        var failJapaneseContinuation = true
        val search = service(category = { query, filter, locale ->
            assertEquals("Nirvana", query)
            assertEquals(YouTube.SearchFilter.FILTER_SONG, filter)
            initialCalls += locale.hl
            if (locale.hl == "en" && failEnglish) Result.failure(IllegalStateException("English unavailable"))
            else Result.success(SearchResult(listOf(song(locale.hl)), if (locale.hl == "ja") "ja-next" else null))
        }, continuation = { token, locale ->
            continuationCalls += token to locale
            if (failJapaneseContinuation) Result.failure(IllegalStateException("Japanese page unavailable"))
            else Result.success(SearchResult(listOf(song("ja-more"))))
        })

        val first = search.search("Nirvana", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
        assertEquals(listOf("ja"), first.items.map { it.id })
        failEnglish = false
        val second = search.searchContinuation(first.continuation!!).getOrThrow()
        assertEquals(listOf("en"), second.items.map { it.id })
        assertEquals(listOf("ja", "en", "en"), initialCalls)
        failJapaneseContinuation = false
        val third = search.searchContinuation(second.continuation!!).getOrThrow()
        assertEquals(listOf("ja-more"), third.items.map { it.id })
        assertNull(third.continuation)
        assertEquals(listOf("ja-next" to japanese, "ja-next" to japanese), continuationCalls)
    }

    @Test
    fun `each successful language advances its own continuation without changing locale`() = runBlocking {
        val calls = mutableListOf<Pair<String, YouTubeLocale>>()
        val search = service(category = { _, _, locale ->
            Result.success(SearchResult(listOf(song("shared", locale.hl)), "${locale.hl}-next"))
        }, continuation = { token, locale ->
            calls += token to locale
            Result.success(SearchResult(listOf(song("${locale.hl}-more"))))
        })
        val first = search.search("query", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
        assertEquals("ja", first.items.single().title)
        val second = search.searchContinuation(first.continuation!!).getOrThrow()
        assertEquals(listOf("ja-more", "en-more"), second.items.map { it.id })
        assertEquals(listOf("ja-next" to japanese, "en-next" to japanese.copy(hl = "en")), calls)
        assertNull(second.continuation)
    }

    @Test
    fun `continuation codec preserves unicode queries and escaped opaque tokens exactly`() = runBlocking {
        val query = "ニルヴァーナ \"Smells\"\n\\live"
        fun token(language: String) = "$language/日本語\"\\\n\t=end"
        val calls = mutableListOf<Pair<String, YouTubeLocale>>()
        val search = service(category = { receivedQuery, _, locale ->
            assertEquals(query, receivedQuery)
            Result.success(SearchResult(emptyList(), token(locale.hl)))
        }, continuation = { receivedToken, locale ->
            calls += receivedToken to locale
            Result.success(SearchResult(emptyList()))
        })
        val cursor = search.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().continuation!!
        assertTrue(search.searchContinuation(cursor).isSuccess)
        assertEquals(listOf(token("ja") to japanese, token("en") to japanese.copy(hl = "en")), calls)
    }

    @Test
    fun `malformed continuation types versions and language branches never reach the network`() = runBlocking {
        var networkCalls = 0
        val search = service(category = { _, _, _ ->
            networkCalls++
            Result.success(SearchResult(emptyList(), "next"))
        }, continuation = { _, _ ->
            networkCalls++
            Result.success(SearchResult(emptyList()))
        })
        val prefix = "bilingual-search:"
        val token = search.search("query", YouTube.SearchFilter.FILTER_SONG).getOrThrow().continuation!!
        networkCalls = 0
        val root = Json.parseToJsonElement(token.removePrefix(prefix)).jsonObject
        val branches = root.getValue("branches").jsonArray
        fun changedRoot(key: String, value: JsonElement) = JsonObject(root + (key to value))
        fun changedBranch(key: String, value: JsonElement) = changedRoot("branches", JsonArray(
            listOf(JsonObject(branches[0].jsonObject + (key to value))) + branches.drop(1),
        ))
        val invalid = listOf(
            JsonNull,
            JsonObject(root - "contextKey"),
            changedRoot("version", JsonPrimitive(2)),
            changedRoot("version", JsonPrimitive("1")),
            changedRoot("query", JsonPrimitive(42)),
            changedRoot("configuredLocale", JsonPrimitive("JP/ja")),
            changedRoot("branches", JsonArray(emptyList())),
            changedRoot("branches", JsonArray(listOf(branches[0], branches[0]))),
            changedRoot("branches", JsonArray(branches.reversed())),
            changedBranch("initial", JsonPrimitive("false")),
            changedBranch("initial", JsonPrimitive(true)),
            changedBranch("continuation", JsonPrimitive(42)),
            changedBranch("continuation", JsonNull),
            changedBranch("locale", JsonObject(mapOf("gl" to JsonPrimitive("JP"), "hl" to JsonPrimitive("fr")))),
            changedBranch("locale", JsonObject(mapOf("gl" to JsonPrimitive("US"), "hl" to JsonPrimitive("ja")))),
        )
        invalid.forEachIndexed { index, cursor ->
            assertTrue("Malformed cursor $index", search.searchContinuation(prefix + cursor).isFailure)
        }
        assertTrue(search.searchContinuation(prefix + "invalid-json").isFailure)
        assertEquals(0, networkCalls)
    }

    @Test
    fun `summary retries keep the successful side without refetching or losing it`() = runBlocking {
        val calls = mutableListOf<String>()
        var unavailable = true
        val search = service(summary = { _, locale ->
            calls += locale.hl
            if (locale.hl == "en" && unavailable) Result.failure(IllegalStateException("Offline"))
            else Result.success(SearchSummaryPage(listOf(SearchSummary("曲", listOf(song(locale.hl))))))
        })
        assertEquals(listOf("ja"), search.searchSummary("query").getOrThrow().summaries.single().items.map { it.id })
        assertTrue(search.summaryNeedsRetry("query"))
        unavailable = false
        assertEquals(listOf("ja", "en"), search.searchSummary("query").getOrThrow().summaries.single().items.map { it.id })
        assertEquals(listOf("ja", "en", "en"), calls)
        assertFalse(search.summaryNeedsRetry("query"))
    }

    @Test
    fun `English-only summary uses localized new headings when configured request fails`() = runBlocking {
        val search = service(summary = { _, locale ->
            if (locale.hl == "ja") Result.failure(IllegalStateException("Offline"))
            else Result.success(SearchSummaryPage(listOf(SearchSummary("Artists", listOf(artist("artist"))))))
        })
        assertEquals("人物", search.searchSummary("query").getOrThrow().summaries.single().title)
    }

    @Test
    fun `language country and authentication changes reject earlier continuation state`() = runBlocking {
        var locale = japanese
        var auth = "public"
        var continuationCalls = 0
        val search = service(locale = { locale }, auth = { auth }, category = { _, _, _ ->
            Result.success(SearchResult(emptyList(), "next"))
        }, continuation = { _, _ ->
            continuationCalls++
            Result.success(SearchResult(emptyList()))
        })
        val token = search.search("query", YouTube.SearchFilter.FILTER_SONG).getOrThrow().continuation!!
        locale = japanese.copy(hl = "fr")
        assertTrue(search.searchContinuation(token).isFailure)
        locale = japanese.copy(gl = "US")
        assertTrue(search.searchContinuation(token).isFailure)
        locale = japanese
        auth = "signed-in"
        assertTrue(search.searchContinuation(token).isFailure)
        assertEquals(0, continuationCalls)
    }

    @Test
    fun `account switches invalidate authentication context even when cookie and visitor stay the same`() {
        val initial = searchAuthenticationContextKey(true, "cookie", "visitor", "account-one")
        assertFalse(initial == searchAuthenticationContextKey(true, "cookie", "visitor", "account-two"))
        assertFalse(initial.contains("cookie"))
        assertFalse(initial.contains("account-one"))
        // Length-prefixing prevents different field boundaries from producing the same input.
        assertFalse(searchAuthenticationContextKey(true, "a/b", "c", "account") ==
            searchAuthenticationContextKey(true, "a", "b/c", "account"))
    }

    @Test
    fun `all initial search routes reject settings changed while both language requests were pending`() = runBlocking {
        for (replacement in listOf(japanese.copy(hl = "fr"), japanese.copy(gl = "US"))) {
            for (route in listOf("summary", "category", "suggestions")) {
                var currentLocale = japanese
                val requests = mutableListOf<YouTubeLocale>()
                fun arrived(locale: YouTubeLocale) {
                    requests += locale
                    if (locale.hl == "en") currentLocale = replacement
                }
                val search = service(locale = { currentLocale },
                    summary = { _, locale ->
                        arrived(locale)
                        Result.success(SearchSummaryPage(listOf(SearchSummary("曲", listOf(song(locale.hl))))))
                    }, category = { _, _, locale ->
                        arrived(locale)
                        Result.success(SearchResult(listOf(song(locale.hl))))
                    }, suggestions = { _, locale ->
                        arrived(locale)
                        Result.success(SearchSuggestions(listOf(locale.hl), listOf(song(locale.hl))))
                    },
                )
                val result = when (route) {
                    "summary" -> search.searchSummary("query")
                    "category" -> search.search("query", YouTube.SearchFilter.FILTER_SONG)
                    else -> search.searchSuggestions("query")
                }
                assertTrue("$route changed to $replacement", result.isFailure)
                assertEquals(listOf(japanese, japanese.copy(hl = "en")), requests)
                assertFalse(search.summaryNeedsRetry("query"))
            }
        }
    }

    @Test
    fun `completed continuation is rejected when locale changes during its requests`() = runBlocking {
        var currentLocale = japanese
        val requests = mutableListOf<YouTubeLocale>()
        val search = service(locale = { currentLocale }, category = { _, _, _ ->
            Result.success(SearchResult(listOf(song("first")), "next"))
        }, continuation = { _, locale ->
            requests += locale
            if (locale.hl == "en") currentLocale = japanese.copy(hl = "fr")
            Result.success(SearchResult(listOf(song("old-language-more"))))
        })
        val token = search.search("query", YouTube.SearchFilter.FILTER_SONG).getOrThrow().continuation!!
        assertTrue(search.searchContinuation(token).isFailure)
        assertEquals(listOf(japanese, japanese.copy(hl = "en")), requests)
    }

    @Test
    fun `completed results are rejected when the active account changes during the requests`() = runBlocking {
        var account = "account-one"
        val search = service(auth = { searchAuthenticationContextKey(true, "cookie", "visitor", account) },
            category = { _, _, locale ->
                if (locale.hl == "en") account = "account-two"
                Result.success(SearchResult(listOf(song(locale.hl))))
            },
        )
        assertTrue(search.search("query", YouTube.SearchFilter.FILTER_SONG).isFailure)
    }

    @Test
    fun `suggestions retain configured ranking and distinct kind identities`() = runBlocking {
        val search = service(suggestions = { _, locale -> Result.success(
            if (locale.hl == "ja") SearchSuggestions(listOf("ニルヴァーナ", "Nirvana"), listOf(song("shared", "日本語")))
            else SearchSuggestions(listOf("Nirvana", "Nirvana unplugged"), listOf(song("shared", "English"), artist("shared"))),
        ) })
        val result = search.searchSuggestions("nirv").getOrThrow()
        assertEquals(listOf("ニルヴァーナ", "Nirvana", "Nirvana unplugged"), result.queries)
        assertEquals(2, result.recommendedItems.size)
        assertEquals("日本語", result.recommendedItems.first().title)
    }

    @Test
    fun `wrapped cancellation cannot publish a successful sibling as a partial result`() = runBlocking {
        val englishStarted = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Result<SearchResult>>()
        var published: Result<SearchResult>? = null
        val search = service(category = { _, _, locale ->
            if (locale.hl == "ja") Result.success(SearchResult(listOf(song("ja"))))
            else {
                englishStarted.complete(Unit)
                try { pending.await() } catch (cancelled: CancellationException) { Result.failure(cancelled) }
            }
        })
        val job = launch { published = search.search("query", YouTube.SearchFilter.FILTER_SONG) }
        withTimeout(5_000) { englishStarted.await() }
        job.cancelAndJoin()
        assertNull(published)
    }

    private fun service(
        locale: suspend () -> YouTubeLocale = { japanese },
        auth: () -> String = { "public" },
        summary: suspend (String, YouTubeLocale) -> Result<SearchSummaryPage> = { _, _ -> Result.success(SearchSummaryPage(emptyList())) },
        category: suspend (String, YouTube.SearchFilter, YouTubeLocale) -> Result<SearchResult> = { _, _, _ -> Result.success(SearchResult(emptyList())) },
        continuation: suspend (String, YouTubeLocale) -> Result<SearchResult> = { _, _ -> Result.success(SearchResult(emptyList())) },
        suggestions: suspend (String, YouTubeLocale) -> Result<SearchSuggestions> = { _, _ -> Result.success(SearchSuggestions(emptyList(), emptyList())) },
    ) = BilingualSearch(BilingualSearch.Runtime(
        requestLocale = locale, contextKey = auth, searchSummary = summary, search = category,
        searchContinuation = continuation, searchSuggestions = suggestions,
        sectionTitle = { if (it == SearchCategory.ARTIST) "人物" else "追加結果" },
    ))

    private fun song(id: String, title: String = id): YTItem = SongItem(id, title, emptyList(), thumbnail = "")
    private fun artist(id: String): YTItem = ArtistItem(id, id, null, shuffleEndpoint = null, radioEndpoint = null)
}
