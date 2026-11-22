package com.ass.controller

import com.ass.model.PageView
import com.ass.model.toPageViewsJson
import com.ass.auth.currentUser
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.route
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Page feature routes
 */
fun Routing.stats() {
    route("/stats") {
        get {
            if (!call.isLoggedIn()) return@get call.respond(HttpStatusCode.Unauthorized, "not logged")
            fetchAllStats().apply {
                call.respond(run {
                    val stringBuilder = StringBuilder()
                    val groupViews = this.groupBy { pv ->
                        pv.page
                    }.values

                    // Count views per page
                    groupViews.forEach {
                        stringBuilder.appendLine("Page: ${it.first().page} has been seen: ${it.count()} times")
                    }

                    // Count sessions
                    val nbSession = this.groupBy { pv ->
                        pv.sessionId
                    }.size

                    stringBuilder.appendLine("Number of session: ${nbSession}")

                    // Count view per session
                    val group = this.groupingBy { it.sessionId }
                    group.eachCount().forEach { (session, count) ->
                        stringBuilder.appendLine("session: $session has seen: $count pages")
                    }
                    stringBuilder.toString()
                })
            }

        }
    }
    route("/stats/json") {
        get {
            if (!call.isLoggedIn()) return@get call.respond(HttpStatusCode.Unauthorized, "not logged")
            call.respond(
                mapOf(
                    "stats" to fetchAllStats(),
                    "pages" to fetchAllPages()
                )
            )
        }
    }
}

private fun ApplicationCall.isLoggedIn() = currentUser() != null

/**
 * Fetch all Stats
 */
fun fetchAllStats() = transaction { PageView.all().map { it.toPageViewsJson() } }

