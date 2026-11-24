package com.ass.signup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Checks the CAPTCHA token sent with a sign-up. */
fun interface CaptchaVerifier {
    suspend fun verify(token: String, clientIp: String): Boolean
}

/**
 * Cloudflare Turnstile (privacy-friendly, no puzzle most of the time): the dashboard's widget
 * gives a token, checked here with Cloudflare's siteverify. Fails closed: if Cloudflare can't be
 * reached, the sign-up is refused (the person can retry).
 */
class TurnstileVerifier(
    private val secret: String,
    private val url: String = "https://challenges.cloudflare.com/turnstile/v0/siteverify"
) : CaptchaVerifier {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val log = LoggerFactory.getLogger("Turnstile")

    override suspend fun verify(token: String, clientIp: String): Boolean {
        if (token.isEmpty() || token.length > 2048) return false
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("secret=${enc(secret)}&response=${enc(token)}&remoteip=${enc(clientIp)}"))
            .build()
        return try {
            val response = withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            parseSuccess(response.body())
        } catch (e: Exception) {
            log.warn("Turnstile verification failed: {}", e.toString())
            false
        }
    }

    companion object {
        internal fun parseSuccess(body: String): Boolean = try {
            Json.parseToJsonElement(body).jsonObject["success"]?.jsonPrimitive?.boolean == true
        } catch (e: Exception) {
            false
        }
    }
}
