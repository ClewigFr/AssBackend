package com.ass

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.request
import kotlin.test.Test
import kotlin.test.assertEquals

class LoadProtectionTest {

    @Test
    fun fullWriteBufferReturns503() = withServer(
        "ktor.performance.ingest_capacity" to "1",
        "ktor.performance.flush_interval_ms" to "60000"
    ) {
        val page = createPage(registerAndLogin("a@x.io"))
        val two = """{"pageViews":[${viewJson(page)},${viewJson(page)}]}"""
        assertEquals(HttpStatusCode.ServiceUnavailable, call(HttpMethod.Post, "/views", json = two).status)
    }

    @Test
    fun viewsAreRateLimitedPerClient() = withServer("ktor.performance.views_rate_limit" to "2") {
        val page = createPage(registerAndLogin("a@x.io"))
        val body = """{"pageViews":[${viewJson(page)}]}"""
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = body).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = body).status)
        assertEquals(HttpStatusCode.TooManyRequests, call(HttpMethod.Post, "/views", json = body).status)
    }

    @Test
    fun largeResponsesAreCompressed() = withServer {
        val alice = registerAndLogin("a@x.io")
        repeat(30) { createPage(alice, "page-with-a-long-enough-name-$it") }
        val response = client.request("/stats/json") {
            this.method = HttpMethod.Get
            headers.append(io.ktor.http.HttpHeaders.Cookie, alice)
            headers.append(io.ktor.http.HttpHeaders.AcceptEncoding, "gzip")
        }
        assertEquals("gzip", response.headers[io.ktor.http.HttpHeaders.ContentEncoding])
    }
}
