package com.dd3boh.outertune.utils

import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.SerializationException

enum class PlaybackFailureStage {
    REQUEST, PLAYABILITY, FORMAT, URL, EXPIRY, STREAM_HTTP, STREAM_NETWORK, SKIPPED_LOGIN,
}

/** Bounded diagnostic values only: never retain credentials, response bodies, URLs, or throwables. */
internal class PlaybackDiagnostics(
    private val cookiePresent: Boolean,
    private val visitorPresent: Boolean,
    private val sessionPresent: Boolean,
    private val authRevision: Long,
    private val requiredItag: Int?,
    private val authorizationAvailable: Boolean = false,
) {
    var poTokenAvailable: Boolean = false
    var signatureTimestampAvailable: Boolean = false

    private val attempts = mutableListOf<String>()
    private var truncated = false

    fun record(
        client: String,
        authenticationSent: Boolean,
        stage: PlaybackFailureStage,
        status: String? = null,
        httpCode: Int? = null,
        selectedItag: Int? = null,
        failure: Throwable? = null,
    ) {
        if (attempts.size == MAX_ATTEMPTS) {
            truncated = true
            return
        }
        // Apply allowlists before storing anything. A throwable's message, cause and toString()
        // can contain signed URLs or response payloads, even when its class is familiar.
        val safeClient = client.takeIf { it in CLIENTS } ?: "OTHER"
        val safeStatus = status?.let { it.takeIf { name -> name in STATUSES } ?: "OTHER" } ?: "NONE"
        // Type checks survive release name obfuscation; arbitrary reflected names do not.
        val safeFailure = when (failure) {
            is ResponseException -> "ResponseException"
            is SerializationException -> "SerializationException"
            else -> failure?.javaClass?.simpleName
                ?.let { it.takeIf { name -> name in FAILURE_CLASSES } ?: "OTHER" } ?: "NONE"
        }
        val responseCode = httpCode ?: (failure as? ResponseException)?.response?.status?.value
        attempts += "${attempts.size + 1}: client=$safeClient cookieHeaderConfigured=$authenticationSent " +
            "stage=${stage.name} status=$safeStatus http=${responseCode ?: "NONE"} " +
            "itag=${selectedItag ?: "NONE"} exception=$safeFailure"
    }

    /** The returned exception owns a finished string, with no link to later attempts or failures. */
    fun asException(): Exception = Exception(buildString {
        append("Playback diagnostics v1")
        append("\ncookiePresent=$cookiePresent visitorPresent=$visitorPresent sessionPresent=$sessionPresent")
        append(" authorizationAvailable=$authorizationAvailable")
        append(" authRevision=$authRevision requiredItag=${requiredItag ?: "NONE"}")
        append("\npoTokenAvailable=$poTokenAvailable signatureTimestampAvailable=$signatureTimestampAvailable")
        append(" attempts=${attempts.size} truncated=$truncated")
        attempts.forEach { append('\n').append(it) }
    })

    private companion object {
        const val MAX_ATTEMPTS = 7
        val CLIENTS = setOf(
            "ANDROID_VR", "VISIONOS", "WEB_REMIX", "ANDROID", "TVHTML5",
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "IOS",
        )
        val STATUSES = setOf(
            "OK", "ERROR", "LOGIN_REQUIRED", "UNPLAYABLE", "LIVE_STREAM_OFFLINE",
            "CONTENT_CHECK_REQUIRED", "AGE_CHECK_REQUIRED",
        )
        val FAILURE_CLASSES = setOf(
            "Exception", "RuntimeException", "IllegalStateException", "IllegalArgumentException",
            "NullPointerException", "IOException", "ConnectException", "UnknownHostException",
            "SocketException", "SocketTimeoutException", "InterruptedIOException",
            "SSLException", "SSLHandshakeException", "SSLPeerUnverifiedException",
            "CertificateException", "CertPathValidatorException", "ClientRequestException",
            "ServerResponseException", "ResponseException", "SerializationException",
            "JsonDecodingException", "MissingFieldException", "PoTokenException",
            "BadWebViewException", "AuthenticationChangedException", "ExtractionException",
            "ParsingException", "ReCaptchaException", "PlaybackException",
        )
    }
}
