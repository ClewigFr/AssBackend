package com.ass.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope

/** Largest request body accepted, as sent (the largest legit one, 1000 page names, is ~300 KB). */
const val MAX_BODY_BYTES = 1L shl 20

/**
 * Refuses bodies larger than [MAX_BODY_BYTES] with 413, so a huge form or JSON body can't
 * exhaust memory. Requests are never decompressed by Ktor; POST /views caps its own decoded
 * size (see ViewsBody.kt).
 */
val BodyLimit = createApplicationPlugin("BodyLimit") {
    onCall { call ->
        val length = call.request.contentLength()
        if (length != null && length > MAX_BODY_BYTES) throw PayloadTooLargeException(MAX_BODY_BYTES)
    }
    on(ReceiveRequestBytes) { _, body -> body.limitedTo(MAX_BODY_BYTES) }
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun ByteReadChannel.limitedTo(max: Long): ByteReadChannel = GlobalScope.writer(Dispatchers.Unconfined) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = this@limitedTo.readAvailable(buffer)
        if (n == -1) break
        total += n
        if (total > max) throw PayloadTooLargeException(max)
        channel.writeFully(buffer, 0, n)
    }
}.channel

/**
 * CSRF protection: the session cookie is SameSite=None (the dashboard is on another site), so a
 * state-changing request coming from a browser must come from an allowed dashboard origin.
 * Requests without Origin (the Android library, curl) aren't from a browser page and pass;
 * POST /views is public anyway.
 */
fun originCheck(allowedOrigins: List<String>) = createApplicationPlugin("OriginCheck") {
    val allowed = allowedOrigins.toSet()
    onCall { call ->
        val method = call.request.httpMethod
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return@onCall
        if (call.request.path() == "/views") return@onCall
        val origin = call.request.headers[HttpHeaders.Origin] ?: return@onCall
        if (origin.trimEnd('/') !in allowed) call.respond(HttpStatusCode.Forbidden, "origin not allowed")
    }
}

/**
 * Defensive headers on every response. The API serves JSON and text only, so nothing may be
 * sniffed as HTML, framed or leak a Referer; HSTS outside dev (served over HTTPS by Cloudflare).
 */
fun securityHeaders(hsts: Boolean) = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        with(call.response.headers) {
            append("X-Content-Type-Options", "nosniff")
            append("X-Frame-Options", "DENY")
            append("Referrer-Policy", "no-referrer")
            append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            if (hsts) append("Strict-Transport-Security", "max-age=31536000")
        }
    }
}
