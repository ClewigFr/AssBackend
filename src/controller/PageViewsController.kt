package com.ass.controller

import com.ass.ingest.PageCache
import com.ass.ingest.ViewIngestor
import com.ass.ingest.ViewRow
import com.ass.model.PostPageViews
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** Largest batch accepted, to bound memory per request (the Android client sends ~20). */
const val MAX_VIEWS_PER_REQUEST = 5000

/** Oldest view accepted, as ms elapsed: the client may keep failed batches on disk for a while. */
const val MAX_VIEW_AGE_MS = 365L * 24 * 60 * 60 * 1000

val VIEWS_RATE_LIMIT = RateLimitName("views")

/**
 * POST /views {"pageViews":[{"page","sessionId","timestamp"}]}, timestamp = ms elapsed since the view.
 * 200: all views accepted, 206: some rejected, 400: none accepted, 429: rate limited,
 * 503: write buffer full. The Android client retries on anything but 200/206/400.
 */
fun Route.pageViews(pageCache: PageCache, ingestor: ViewIngestor, rateLimited: Boolean) {
    if (rateLimited) {
        rateLimit(VIEWS_RATE_LIMIT) { viewsRoute(pageCache, ingestor) }
    } else {
        viewsRoute(pageCache, ingestor)
    }
}

private fun Route.viewsRoute(pageCache: PageCache, ingestor: ViewIngestor) {
    post("/views") {
        val hits = call.receiveNullable<PostPageViews>()?.pageViews.orEmpty()
        if (hits.isEmpty() || hits.size > MAX_VIEWS_PER_REQUEST) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        val now = System.currentTimeMillis()
        val pages = pageCache.filterExisting(hits.mapNotNull { it?.page }.toSet(), now)
        val rows = hits.mapNotNull { hit ->
            val page = hit?.page
            val session = hit?.sessionId
            val elapsed = hit?.timestamp
            if (page != null && page in pages && session != null && elapsed != null && elapsed in 0..MAX_VIEW_AGE_MS) {
                ViewRow(page, session, now - elapsed)
            } else null
        }
        val status = when {
            rows.isEmpty() -> HttpStatusCode.BadRequest
            !ingestor.offer(rows) -> HttpStatusCode.ServiceUnavailable
            rows.size == hits.size -> HttpStatusCode.OK
            else -> HttpStatusCode.PartialContent
        }
        call.respond(status)
    }
}
