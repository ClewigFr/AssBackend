package com.ass

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class GzipViewsTest {

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postViews(body: ByteArray, encoding: String?) =
        client.post("/views") {
            contentType(ContentType.Application.Json)
            encoding?.let { header(HttpHeaders.ContentEncoding, it) }
            setBody(body)
        }

    @Test
    fun gzippedViewsAreAccepted() = withServer {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        val views = (1..20).joinToString(",") { viewJson(page) }
        val response = postViews(gzip("""{"pageViews":[$views]}""".toByteArray()), "gzip")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.AcceptEncoding])
    }

    @Test
    fun plainViewsAdvertiseGzip() = withServer {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        val response = postViews("""{"pageViews":[${viewJson(page)}]}""".toByteArray(), null)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.AcceptEncoding])
    }

    @Test
    fun unknownEncodingIs415() = withServer {
        assertEquals(HttpStatusCode.UnsupportedMediaType, postViews("{}".toByteArray(), "br").status)
    }

    @Test
    fun corruptGzipIs400() = withServer {
        assertEquals(HttpStatusCode.BadRequest, postViews("not gzip".toByteArray(), "gzip").status)
    }

    /** 10 MB of spaces compress to ~10 KB: rejected once the decompressed cap is reached. */
    @Test
    fun gzipBombIs400() = withServer {
        val bomb = gzip(ByteArray(10 shl 20) { ' '.code.toByte() })
        assertEquals(HttpStatusCode.BadRequest, postViews(bomb, "gzip").status)
    }

    @Test
    fun budget429AndFullBuffer503SayWhenToRetry() {
        withServer("ktor.performance.views_per_minute" to "1") {
            val page = appWithPage(registerAndLogin("a@x.io")).second
            val two = """{"pageViews":[${viewJson(page)},${viewJson(page)}]}"""
            val response = call(HttpMethod.Post, "/views", json = two)
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("60", response.headers[HttpHeaders.RetryAfter])
        }
        withServer("ktor.performance.ingest_capacity" to "1", "ktor.performance.flush_interval_ms" to "60000") {
            val page = appWithPage(registerAndLogin("a@x.io")).second
            val two = """{"pageViews":[${viewJson(page)},${viewJson(page)}]}"""
            val response = call(HttpMethod.Post, "/views", json = two)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("10", response.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun requestRateLimit429SaysWhenToRetry() = withServer("ktor.performance.views_rate_limit" to "1") {
        val body = """{"pageViews":[${viewJson(java.util.UUID.randomUUID().toString())}]}"""
        call(HttpMethod.Post, "/views", json = body)
        val response = call(HttpMethod.Post, "/views", json = body)
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assert(response.headers[HttpHeaders.RetryAfter] != null) { "no Retry-After" }
    }
}
