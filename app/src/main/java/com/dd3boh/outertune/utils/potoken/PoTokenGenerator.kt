package com.dd3boh.outertune.utils.potoken

import android.util.Log
import android.webkit.CookieManager
import com.dd3boh.outertune.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PoTokenGenerator {
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false
    private val sessions = PoTokenSessionCache(
        create = { PoTokenWebView.getNewPoTokenGenerator(App.instance) },
        expired = { generator: PoTokenWebView -> generator.isExpired },
        generate = { generator, identifier -> generator.generatePoToken(identifier) },
        close = { generator -> withContext(Dispatchers.Main) { generator.close() } },
    )

    suspend fun getWebClientPoToken(videoId: String, sessionId: String, authRevision: Long): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) return null
        return try {
            withTimeoutOrNull(30_000L) { sessions.get(videoId, sessionId, authRevision) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: BadWebViewException) {
            Log.e("PoTokenGenerator", "Could not obtain poToken because WebView is broken", broken)
            webViewBadImpl = true
            null
        }
    }
}
