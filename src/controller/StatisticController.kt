package com.ass.controller

import com.ass.auth.currentUser
import com.ass.db.Db
import com.ass.stats.MAX_TZ_OFFSET_MS
import com.ass.stats.StatsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /stats/json?tz=<minutes east of UTC> — aggregated stats for the dashboard.
 * GET /stats — the same as plain text.
 */
fun Route.stats(db: Db, statsService: StatsService) {
    get("/stats/json") {
        if (call.currentUser(db) == null) return@get call.respond(HttpStatusCode.Unauthorized, "not logged")
        val tzMs = (call.request.queryParameters["tz"]?.toLongOrNull() ?: 0) * 60_000
        if (tzMs !in -MAX_TZ_OFFSET_MS..MAX_TZ_OFFSET_MS) return@get call.respond(HttpStatusCode.BadRequest, "invalid tz")
        call.respond(statsService.compute(tzMs))
    }
    get("/stats") {
        if (call.currentUser(db) == null) return@get call.respond(HttpStatusCode.Unauthorized, "not logged")
        val stats = statsService.compute()
        val text = buildString {
            stats.pages.forEach { appendLine("Page: ${it.name} (${it.id}) has been seen: ${it.views} times") }
            appendLine("Number of session: ${stats.sessions}")
            appendLine("Number of page views: ${stats.events}")
        }
        call.respondText(text)
    }
}
