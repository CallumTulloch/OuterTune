package com.zionhuang.innertube

import com.zionhuang.innertube.models.Context
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.body.*
import com.zionhuang.innertube.models.response.VisitorResponse
import com.zionhuang.innertube.utils.cookieAuthorization
import com.zionhuang.innertube.utils.parseCookieString
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.encodeBase64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.net.Proxy
import java.io.IOException
import java.util.*

/** One request's credentials. Never expose credential contents through incidental logging. */
class YouTubeAuthentication internal constructor(
    val cookie: String?,
    val visitorData: String?,
    val dataSyncId: String?,
    val useLoginForBrowse: Boolean,
    val revision: Long,
) {
    internal val cookieMap: Map<String, String> = Collections.unmodifiableMap(cookie?.let(::parseCookieString).orEmpty())

    /** Header availability is diagnostic information, not a claim that the session is valid. */
    val authorizationAvailable: Boolean
        get() = cookieAuthorization(cookieMap, YouTubeClient.ORIGIN_YOUTUBE_MUSIC) != null

    override fun toString(): String = "YouTubeAuthentication(revision=$revision, useLoginForBrowse=$useLoginForBrowse)"
}

class AuthenticationChangedException : IOException("Authentication changed while the request was in progress")

/**
 * Provide access to InnerTube endpoints.
 * For making HTTP requests, not parsing response.
 */
class InnerTube {
    internal val authenticationLock = Any()
    @Volatile private var currentAuthentication = YouTubeAuthentication(null, null, null, false, 0)
    private val mutableAuthUpdates = MutableStateFlow(0L)
    val authUpdates = mutableAuthUpdates.asStateFlow()
    val authentication: YouTubeAuthentication get() = currentAuthentication
    private var httpClient = createClient()
    private val visitorDataByClient = mutableMapOf<String, String>()

    @Volatile
    var locale = YouTubeLocale(gl = "US", hl = "en")
    fun setAuthentication(cookie: String?, visitorData: String?, dataSyncId: String?, useLoginForBrowse: Boolean) {
        synchronized(authenticationLock) {
            val previous = authentication
            if (previous.cookie == cookie && previous.visitorData == visitorData &&
                previous.dataSyncId == dataSyncId && previous.useLoginForBrowse == useLoginForBrowse) return
            currentAuthentication = YouTubeAuthentication(cookie, visitorData, dataSyncId, useLoginForBrowse, previous.revision + 1)
            visitorDataByClient.clear()
            mutableAuthUpdates.value = currentAuthentication.revision
        }
    }

    private fun updateAuthentication(update: (YouTubeAuthentication) -> Unit) = synchronized(authenticationLock) {
        update(authentication)
    }

    var visitorData: String?
        get() = authentication.visitorData
        set(value) = updateAuthentication { setAuthentication(it.cookie, value, it.dataSyncId, it.useLoginForBrowse) }
    var dataSyncId: String?
        get() = authentication.dataSyncId
        set(value) = updateAuthentication { setAuthentication(it.cookie, it.visitorData, value, it.useLoginForBrowse) }
    var cookie: String?
        get() = authentication.cookie
        set(value) = updateAuthentication { setAuthentication(value, it.visitorData, it.dataSyncId, it.useLoginForBrowse) }
    var useLoginForBrowse: Boolean
        get() = authentication.useLoginForBrowse
        set(value) = updateAuthentication { setAuthentication(it.cookie, it.visitorData, it.dataSyncId, value) }

    internal fun ensureAuthenticationCurrent(requestAuthentication: YouTubeAuthentication) {
        if (requestAuthentication !== authentication) throw AuthenticationChangedException()
    }

    private suspend fun post(
        url: String,
        requestAuthentication: YouTubeAuthentication = authentication,
        rejectStaleResponse: Boolean = false,
        block: HttpRequestBuilder.(YouTubeAuthentication) -> Unit,
    ): HttpResponse {
        ensureAuthenticationCurrent(requestAuthentication)
        return httpClient.post(url) {
            ensureAuthenticationCurrent(requestAuthentication)
            block(requestAuthentication)
        }.also { if (rejectStaleResponse) ensureAuthenticationCurrent(requestAuthentication) }
    }

    private suspend fun get(
        url: String,
        requestAuthentication: YouTubeAuthentication = authentication,
        rejectStaleResponse: Boolean = false,
        block: HttpRequestBuilder.(YouTubeAuthentication) -> Unit = {},
    ): HttpResponse {
        ensureAuthenticationCurrent(requestAuthentication)
        return httpClient.get(url) {
            ensureAuthenticationCurrent(requestAuthentication)
            block(requestAuthentication)
        }.also { if (rejectStaleResponse) ensureAuthenticationCurrent(requestAuthentication) }
    }

    var proxy: Proxy? = null
        set(value) {
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() = HttpClient(OkHttp) {
        expectSuccess = true

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            })
        }

        install(ContentEncoding) {
            gzip(0.9F)
            deflate(0.8F)
        }

        if (proxy != null) {
            engine {
                proxy = this@InnerTube.proxy
            }
        }

        defaultRequest {
            url(YouTubeClient.API_URL_YOUTUBE_MUSIC)
        }
    }

    private fun HttpRequestBuilder.ytClient(client: YouTubeClient, authentication: YouTubeAuthentication, setLogin: Boolean = false) {
        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", client.apiFormatVersion)
            append("X-YouTube-Client-Name", client.clientId /* Not a typo. The Client-Name header does contain the client id. */)
            append("X-YouTube-Client-Version", client.clientVersion)
            if (client.sendMusicHeaders) {
                append("X-Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
                append("Referer", YouTubeClient.REFERER_YOUTUBE_MUSIC)
            }
            if (setLogin && client.loginSupported) {
                authentication.cookie?.let { cookie ->
                    append("cookie", cookie)
                    cookieAuthorization(authentication.cookieMap, YouTubeClient.ORIGIN_YOUTUBE_MUSIC)?.let {
                        append("Authorization", it)
                    }
                }
            }
        }
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        requestLocale: YouTubeLocale = locale,
    ) = post("search", rejectStaleResponse = true) { auth ->
        ytClient(client, auth, setLogin = auth.useLoginForBrowse)
        setBody(
            SearchBody(
                context = client.toContext(
                    requestLocale,
                    auth.visitorData,
                    if (auth.useLoginForBrowse) auth.dataSyncId else null
                ),
                query = query,
                params = params
            )
        )
        parameter("continuation", continuation)
        parameter("ctoken", continuation)
    }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        webPlayerPot: String?,
        requestLocale: YouTubeLocale = locale,
        requestAuthentication: YouTubeAuthentication = authentication,
    ): HttpResponse {
        val auth = requestAuthentication
        ensureAuthenticationCurrent(auth)
        val playerVisitorData = if (client.requiresFreshVisitorData) {
            val cached = synchronized(authenticationLock) {
                ensureAuthenticationCurrent(auth)
                visitorDataByClient[client.clientName]
            }
            cached ?: post("${client.apiUrl}visitor_id", auth, rejectStaleResponse = true) { snapshot ->
                ytClient(client, snapshot)
                setBody(VisitorBody(client.toContext(requestLocale, null, null)))
            }.body<VisitorResponse>().responseContext.visitorData.also { visitor ->
                synchronized(authenticationLock) {
                    // A suspended visitor response must never refill the next session's cache.
                    ensureAuthenticationCurrent(auth)
                    if (visitor != null) visitorDataByClient[client.clientName] = visitor
                }
            }
        } else {
            auth.visitorData
        }
        val contentPlaybackNonce = if (client.useContentPlaybackNonce) {
            (1..16).map {
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[Random().nextInt(64)]
            }.joinToString("")
        } else null

        return post("${client.apiUrl}player", auth, rejectStaleResponse = true) { snapshot ->
            ytClient(client, snapshot, setLogin = true)
            setBody(
                PlayerBody(
                    context = client.toContext(requestLocale, playerVisitorData, snapshot.dataSyncId).let {
                    if (client.isEmbedded) {
                        it.copy(
                            thirdParty = Context.ThirdParty(
                                embedUrl = "https://www.youtube.com/watch?v=${videoId}"
                            )
                        )
                    } else it
                },
                    videoId = videoId,
                    playlistId = playlistId,
                    playbackContext = if (client.useSignatureTimestamp && signatureTimestamp != null) {
                        PlayerBody.PlaybackContext(
                            PlayerBody.PlaybackContext.ContentPlaybackContext(
                                signatureTimestamp
                            )
                        )
                    } else null,
                    serviceIntegrityDimensions = if (client.useWebPoTokens && webPlayerPot != null) {
                        PlayerBody.ServiceIntegrityDimensions(webPlayerPot)
                    } else null,
                    cpn = contentPlaybackNonce,
                )
            )
        }
    }

    /** Public Main YouTube metadata; keep this separate from playback clients and credentials. */
    suspend fun artTrackOriginalMetadata(
        videoId: String,
        requestLocale: YouTubeLocale = locale,
    ) = post("${YouTubeClient.API_URL_YOUTUBE}player", rejectStaleResponse = true) { auth ->
        val client = YouTubeClient.WEB.copy(sendMusicHeaders = false)
        ytClient(client, auth)
        setBody(
            PlayerBody(
                context = client.toContext(requestLocale, null, null),
                videoId = videoId,
                playlistId = null,
            )
        )
    }

    /** Public Main song attribution, isolated from Music account and visitor context. */
    suspend fun mainSongReference(
        videoId: String,
        requestLocale: YouTubeLocale = locale,
    ) = post("${YouTubeClient.API_URL_YOUTUBE}next", rejectStaleResponse = true) { auth ->
        val client = YouTubeClient.WEB.copy(sendMusicHeaders = false)
        ytClient(client, auth)
        setBody(NextBody(
            context = client.toContext(requestLocale, null, null),
            videoId = videoId,
            playlistId = null,
            playlistSetVideoId = null,
            index = null,
            params = null,
            continuation = null,
        ))
    }

    suspend fun registerPlayback(
        url: String,
        cpn: String,
        playlistId: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ) = get(url) { auth ->
        ytClient(client, auth, true)
        parameter("ver", "2")
        parameter("c", client.clientName)
        parameter("cpn", cpn)

        if (playlistId != null) {
            parameter("list", playlistId)
            parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
        }
    }

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
        requestLocale: YouTubeLocale = locale,
    ) = post("browse", rejectStaleResponse = true) { auth ->
        ytClient(client, auth, setLogin = setLogin || auth.useLoginForBrowse)
        setBody(
            BrowseBody(
                context = client.toContext(
                    requestLocale,
                    auth.visitorData,
                    if (setLogin || auth.useLoginForBrowse) auth.dataSyncId else null
                ),
                browseId = browseId,
                params = params,
                continuation = continuation
            )
        )
    }

    suspend fun resolveArtistUrl(
        url: String,
        requestLocale: YouTubeLocale = locale,
    ): HttpResponse {
        val canonicalUrl = parseYouTubeArtistUrl(url).canonicalUrl
        val client = YouTubeClient.WEB_REMIX
        return post("navigation/resolve_url", rejectStaleResponse = true) { auth ->
            ytClient(client, auth, setLogin = auth.useLoginForBrowse)
            setBody(ResolveUrlBody(
                client.toContext(requestLocale, auth.visitorData, if (auth.useLoginForBrowse) auth.dataSyncId else null),
                canonicalUrl,
            ))
        }
    }

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
        requestLocale: YouTubeLocale = locale,
    ) = post("next", rejectStaleResponse = true) { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            NextBody(
                context = client.toContext(requestLocale, auth.visitorData, auth.dataSyncId),
                videoId = videoId,
                playlistId = playlistId,
                playlistSetVideoId = playlistSetVideoId,
                index = index,
                params = params,
                continuation = continuation
            )
        )
    }

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
        requestLocale: YouTubeLocale = locale,
    ) = post("music/get_search_suggestions", rejectStaleResponse = true) { auth ->
        ytClient(client, auth)
        setBody(
            GetSearchSuggestionsBody(
                context = client.toContext(requestLocale, auth.visitorData, null),
                input = input
            )
        )
    }

    suspend fun getQueue(
        client: YouTubeClient,
        videoIds: List<String>?,
        playlistId: String?,
        requestLocale: YouTubeLocale = locale,
    ) = post("music/get_queue", rejectStaleResponse = true) { auth ->
        ytClient(client, auth)
        setBody(
            GetQueueBody(
                context = client.toContext(requestLocale, auth.visitorData, null),
                videoIds = videoIds,
                playlistId = playlistId
            )
        )
    }

    suspend fun getTranscript(
        client: YouTubeClient,
        videoId: String,
        requestLocale: YouTubeLocale = locale,
    ) = post("https://music.youtube.com/youtubei/v1/get_transcript", rejectStaleResponse = true) { _ ->
        parameter("key", "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3")
        headers {
            append("Content-Type", "application/json")
        }
        setBody(
            GetTranscriptBody(
                context = client.toContext(requestLocale, null, null),
                params = "\n${11.toChar()}$videoId".encodeBase64()
            )
        )
    }

    suspend fun getSwJsData() = get("https://music.youtube.com/sw.js_data", rejectStaleResponse = true)

    suspend fun accountMenu(client: YouTubeClient) = post("account/account_menu", rejectStaleResponse = true) { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(AccountMenuBody(client.toContext(locale, auth.visitorData, auth.dataSyncId)))
    }

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = post("like/like") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId)
            )
        )
    }

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = post("like/removelike") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId)
            )
        )
    }

    suspend fun subscribeChannel(
        client: YouTubeClient,
        channelId: String,
    ) = post("subscription/subscribe") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            SubscribeBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                channelIds = listOf(channelId)
            )
        )
    }

    suspend fun unsubscribeChannel(
        client: YouTubeClient,
        channelId: String,
    ) = post("subscription/unsubscribe") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            SubscribeBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                channelIds = listOf(channelId)
            )
        )
    }

    suspend fun likePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = post("like/like") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                target = LikeBody.Target.PlaylistTarget(playlistId)
            )
        )
    }

    suspend fun unlikePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = post("like/removelike") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                target = LikeBody.Target.PlaylistTarget(playlistId)
            )
        )
    }

    suspend fun addToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
    ) = post("browse/edit_playlist") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions = listOf(
                    Action.AddVideoAction(addedVideoId = videoId)
                )
            )
        )
    }

    suspend fun addPlaylistToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        addPlaylistId: String,
    ) = post("browse/edit_playlist") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions = listOf(
                    Action.AddPlaylistAction(addedFullListId = addPlaylistId)
                )
            )
        )
    }

    suspend fun removeFromPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = post("browse/edit_playlist") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions = listOf(
                    Action.RemoveVideoAction(
                        removedVideoId = videoId,
                        setVideoId = setVideoId,
                    )
                )
            )
        )
    }

    suspend fun moveSongPlaylist(
        client: YouTubeClient,
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String,
    ) = post("browse/edit_playlist") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                playlistId = playlistId,
                actions = listOf(
                    Action.MoveVideoAction(
                        movedSetVideoIdSuccessor = successorSetVideoId,
                        setVideoId = setVideoId,
                    )
                )

            )
        )
    }

    suspend fun createPlaylist(
        client: YouTubeClient,
        title: String,
    ) = post("playlist/create") { auth ->
        ytClient(client, auth, true)
        setBody(
            CreatePlaylistBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                title = title
            )
        )
    }

    suspend fun renamePlaylist(
        client: YouTubeClient,
        playlistId: String,
        name: String,
    ) = post("browse/edit_playlist") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                playlistId = playlistId,
                actions = listOf(
                    Action.RenamePlaylistAction(
                        playlistName = name
                    )
                )
            )
        )
    }

    suspend fun deletePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = post("playlist/delete") { auth ->
        ytClient(client, auth, setLogin = true)
        setBody(
            PlaylistDeleteBody(
                context = client.toContext(locale, auth.visitorData, auth.dataSyncId),
                playlistId = playlistId
            )
        )
    }
}
