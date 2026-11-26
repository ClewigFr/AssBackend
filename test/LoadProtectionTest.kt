package com.ass

import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

class LoadProtectionTest {

    @Test
    fun fullWriteBufferReturns503() = withServer(
        "ktor.performance.ingest_capacity" to "1",
        "ktor.performance.flush_interval_ms" to "60000"
    ) {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        val two = """{"pageViews":[${viewJson(page)},${viewJson(page)}]}"""
        assertEquals(HttpStatusCode.ServiceUnavailable, call(HttpMethod.Post, "/views", json = two).status)
    }

    @Test
    fun viewsAreRateLimitedPerClient() = withServer("ktor.performance.views_rate_limit" to "2") {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        val body = """{"pageViews":[${viewJson(page)}]}"""
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = body).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = body).status)
        assertEquals(HttpStatusCode.TooManyRequests, call(HttpMethod.Post, "/views", json = body).status)
    }

    @Test
    fun viewsPerMinuteAreLimitedPerClient() = withServer("ktor.performance.views_per_minute" to "3") {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        val two = """{"pageViews":[${viewJson(page)},${viewJson(page)}]}"""
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = two).status)
        assertEquals(HttpStatusCode.TooManyRequests, call(HttpMethod.Post, "/views", json = two).status)
    }

    @Test
    fun aFullRetryFileIsAccepted() = withServer {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        val views = (1..1000).joinToString(",") { viewJson(page) }
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"pageViews":[$views]}""").status)
    }

    /**
     * Behind Cloudflare (tunnel), X-Forwarded-For is "<whatever the client sent>, <real client IP>":
     * the rate limit must count the last entry, so each client has its own limit and can't dodge it
     * by sending a fake X-Forwarded-For.
     */
    @Test
    fun rateLimitUsesTheLastForwardedAddressInProduction() = withServer(
        "ktor.environment" to "prod",
        "ktor.security.session_sign_key" to "00".repeat(32),
        "ktor.performance.views_rate_limit" to "2"
    ) {
        // Unknown page: answered 400 when allowed, 429 when rate limited, nothing is stored
        val body = """{"pageViews":[${viewJson(java.util.UUID.randomUUID().toString())}]}"""
        suspend fun post(forwardedFor: String) = client.post("/views") {
            header("X-Forwarded-For", forwardedFor)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status
        assertEquals(HttpStatusCode.BadRequest, post("9.9.9.9, 1.1.1.1"))
        assertEquals(HttpStatusCode.BadRequest, post("8.8.8.8, 1.1.1.1"))
        // Same real client, different fake first entry: still limited
        assertEquals(HttpStatusCode.TooManyRequests, post("7.7.7.7, 1.1.1.1"))
        // Another client has its own limit
        assertEquals(HttpStatusCode.BadRequest, post("9.9.9.9, 2.2.2.2"))
    }

    /** Without a proxy in front (TRUST_PROXY=false), a faked X-Forwarded-For doesn't reset the limit. */
    @Test
    fun forwardedForIsIgnoredWithoutTrustedProxy() = withServer(
        "ktor.environment" to "prod",
        "ktor.security.session_sign_key" to "00".repeat(32),
        "ktor.security.trust_proxy" to "false",
        "ktor.performance.views_rate_limit" to "2"
    ) {
        val body = """{"pageViews":[${viewJson(java.util.UUID.randomUUID().toString())}]}"""
        suspend fun post(forwardedFor: String) = client.post("/views") {
            header("X-Forwarded-For", forwardedFor)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status
        assertEquals(HttpStatusCode.BadRequest, post("1.1.1.1"))
        assertEquals(HttpStatusCode.BadRequest, post("2.2.2.2"))
        assertEquals(HttpStatusCode.TooManyRequests, post("3.3.3.3"))
    }

    @Test
    fun largeResponsesAreCompressed() = withServer {
        val alice = registerAndLogin("a@x.io")
        val app = createApp(alice)
        repeat(30) { createPage(alice, app, "page-with-a-long-enough-name-$it") }
        val response = client.request("/apps/$app/stats") {
            this.method = HttpMethod.Get
            headers.append(io.ktor.http.HttpHeaders.Cookie, alice)
            headers.append(io.ktor.http.HttpHeaders.AcceptEncoding, "gzip")
        }
        assertEquals("gzip", response.headers[io.ktor.http.HttpHeaders.ContentEncoding])
    }
}

class IngestionIntegrityTest {
    /**
     * Every accepted view is written, with many concurrent requests and constant flushing.
     * Note: the race this guards against (a timeout cancelling receive() after it took a view)
     * was reproduced with ApacheBench against the real server (1 view lost per ~140k), not here:
     * the test engine's timing is too regular to trigger it.
     */
    @Test
    fun noViewIsLostUnderConcurrentLoad() = withServer(
        "ktor.performance.flush_interval_ms" to "1",
        // 6000 views from one client: not what the flood limits are about here
        "ktor.performance.views_per_ip_app_hour" to "0"
    ) {
        val alice = registerAndLogin("alice@x.io")
        val (app, page) = appWithPage(alice)
        val body = """{"app":"$app","pageViews":[${(1..20).joinToString(",") { viewJson(page) }}]}"""
        kotlinx.coroutines.coroutineScope {
            repeat(300) {
                launch { assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = body).status) }
            }
        }
        awaitIngested()
        val stats = kotlinx.serialization.json.Json.decodeFromString<com.ass.model.PeriodStats>(
            call(HttpMethod.Get, "/apps/$app/stats?period=day", alice).bodyAsText()
        )
        assertEquals(6_000, stats.current.views)
    }
}
