package com.ass.controller

import com.ass.model.PageView
import com.ass.model.toPageViewsJson
import com.ass.ASSSession
import com.ass.dao.fetchUser
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.sessions.get
import io.ktor.sessions.sessions
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
                call.respond(synchronized(this) {
                    val stringBuilder = StringBuilder()
                    val groupViews = this.groupBy { pv ->
                        pv.page
                    }.values

                    // Count views per page
                    groupViews.forEach {
                        stringBuilder.appendln("Page: ${it.first().page} has been seen: ${it.count()} times")
                    }

                    // Count sessions
                    val nbSession = this.groupBy { pv ->
                        pv.sessionId
                    }.size

                    stringBuilder.appendln("Number of session: ${nbSession}")

                    // Count view per session
                    val group = this.groupingBy { it.sessionId }
                    group.eachCount().forEach { (session, count) ->
                        stringBuilder.appendln("session: $session has seen: $count pages")
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
                    "stats" to synchronized(this) {
                        fetchAllStats().toList()
                    },
                    "pages" to synchronized(this) {
                        fetchAllPages().toList()
                    }
                )
            )
        }
    }
}

private fun ApplicationCall.isLoggedIn() =
    sessions.get<ASSSession>()?.let { fetchUser(it.userId) } != null

/**
 * Fetch all Stats
 */
fun fetchAllStats() = transaction { PageView.all().map { it.toPageViewsJson() } }

