package com.ass.controller

import com.ass.ingest.PageCache
import com.ass.ingest.ViewBudget
import com.ass.ingest.ViewGuard
import com.ass.ingest.ViewIngestor
import com.ass.ingest.ViewRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Largest batch accepted, to bound memory and abuse per request. The Android client sends ~20,
 * and at most its whole retry file (capped at 1000 views) when retrying: never go below that,
 * a 400 makes the client drop the batch.
 */
const val MAX_VIEWS_PER_REQUEST = 1000

/** Oldest view accepted, as ms elapsed: the client may keep failed batches on disk for a while. */
const val MAX_VIEW_AGE_MS = 365L * 24 * 60 * 60 * 1000

val VIEWS_RATE_LIMIT = RateLimitName("views")

/** Retry-After of a 429 from the views-per-minute budget: its window is one minute. */
const val BUDGET_RETRY_AFTER_S = 60

/** Retry-After of a 503: the write buffer drains every FLUSH_INTERVAL_MS, a few seconds is plenty. */
const val BUFFER_FULL_RETRY_AFTER_S = 10

/**
 * POST /views {"app","pageViews":[{"page","sessionId","timestamp"}]}, timestamp = ms elapsed since the view.
 * When "app" is given, only pages of that application are accepted. Clients that don't send it
 * (older library versions) are still accepted for any existing page.
 * Views over the flood limits (see ViewGuard) are silently dropped, like invalid ones.
 * 200: all views accepted, 206: some rejected, 400: none accepted, 429: rate limited (requests or views per minute),
 * 503: write buffer full, 415: unsupported Content-Encoding. 429 and 503 carry Retry-After.
 * The body may be gzipped (Content-Encoding: gzip); every response says so with Accept-Encoding.
 * The Android client retries on anything but 200/206/400.
 */
fun Route.pageViews(pageCache: PageCache, ingestor: ViewIngestor, budget: ViewBudget, guard: ViewGuard, rateLimited: Boolean) {
    if (rateLimited) {
        rateLimit(VIEWS_RATE_LIMIT) { viewsRoute(pageCache, ingestor, budget, guard) }
    } else {
        viewsRoute(pageCache, ingestor, budget, guard)
    }
}

private fun Route.viewsRoute(pageCache: PageCache, ingestor: ViewIngestor, budget: ViewBudget, guard: ViewGuard) {
    post("/views") {
        // Tell clients they may gzip their next batches (RFC 7694)
        call.response.header(HttpHeaders.AcceptEncoding, "gzip")
        val body = when (val read = readViewsBody(call)) {
            is ViewsBody.Read -> read.views
            ViewsBody.UnsupportedEncoding -> return@post call.respond(HttpStatusCode.UnsupportedMediaType)
            ViewsBody.Invalid -> return@post call.respond(HttpStatusCode.BadRequest)
        }
        val hits = body?.pageViews.orEmpty()
        if (hits.isEmpty() || hits.size > MAX_VIEWS_PER_REQUEST) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        val now = System.currentTimeMillis()
        val appOfPage = pageCache.appsOf(hits.mapNotNull { it?.page }.toSet(), now)
        val valid = hits.mapNotNull { hit ->
            val page = hit?.page
            val session = hit?.sessionId
            val elapsed = hit?.timestamp
            val pageKnown = page != null && appOfPage.containsKey(page)
            val pageApp = page?.let { appOfPage[it] }
            val appMatches = body?.app == null || body.app == pageApp
            if (pageKnown && appMatches && session != null && elapsed != null && elapsed in 0..MAX_VIEW_AGE_MS) {
                ViewRow(page!!, pageApp, session, now - elapsed)
            } else null
        }
        // Views over the flood limits are dropped like invalid ones: the client must not retry them
        val rows = if (valid.isEmpty()) valid else guard.filter(call.request.origin.remoteHost, valid, now)
        val status = when {
            rows.isEmpty() -> HttpStatusCode.BadRequest
            !budget.take(call.request.origin.remoteHost, rows.size, now) -> {
                call.response.header(HttpHeaders.RetryAfter, BUDGET_RETRY_AFTER_S.toString())
                HttpStatusCode.TooManyRequests
            }
            !ingestor.offer(rows) -> {
                call.response.header(HttpHeaders.RetryAfter, BUFFER_FULL_RETRY_AFTER_S.toString())
                HttpStatusCode.ServiceUnavailable
            }
            rows.size == hits.size -> HttpStatusCode.OK
            else -> HttpStatusCode.PartialContent
        }
        call.respond(status)
    }
}
