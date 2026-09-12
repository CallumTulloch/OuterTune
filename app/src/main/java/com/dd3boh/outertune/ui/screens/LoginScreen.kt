package com.dd3boh.outertune.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.App
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
) {
    val scope = rememberCoroutineScope()
    val authentication = App.instance.authentication

    var webView: WebView? = null

    AndroidView(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    private var pageGeneration = 0L

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        pageGeneration++
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (url == null || Uri.parse(url).host != "music.youtube.com") return
                        val generation = pageGeneration
                        val cookie = CookieManager.getInstance().getCookie(url)
                        // Retrieve the page's two identifiers together, then commit all credentials together.
                        view.evaluateJavascript("""
                            (function() {
                                var config = window.yt && window.yt.config_;
                                return config ? {visitorData: config.VISITOR_DATA || null,
                                    dataSyncId: config.DATASYNC_ID || null} : null;
                            })()
                        """.trimIndent()) sessionConfig@ { encoded ->
                            if (generation != pageGeneration) return@sessionConfig
                            val config = runCatching { Json.parseToJsonElement(encoded).jsonObject }.getOrNull()
                                ?: return@sessionConfig
                            scope.launch {
                                if (generation != pageGeneration) return@launch
                                try {
                                    val session = authentication.saveLogin(cookie,
                                        config["visitorData"]?.jsonPrimitive?.contentOrNull,
                                        config["dataSyncId"]?.jsonPrimitive?.contentOrNull)
                                    YouTube.accountInfo().onSuccess {
                                        authentication.saveAccountInfo(session, it)
                                    }.onFailure {
                                        if (it is CancellationException) throw it
                                        reportException(it)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    reportException(error)
                                }
                            }
                        }
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                }
                webView = this
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        }
    )

    TopAppBar(
        title = { Text(stringResource(R.string.login)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
    )

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}
