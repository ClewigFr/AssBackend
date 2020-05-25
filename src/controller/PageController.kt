package com.ass.controller

import com.ass.model.Page
import com.ass.model.PageView
import com.ass.model.toPageViewsJson
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.route
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Page feature routes
 */
fun Routing.pages() {
    route("/page/new") {
        get {
            val page = transaction {
                Page.new { name = "test" }
            }
            val message = "Page :${page.name}, id: ${page.id}"
            call.respondText(message, contentType = ContentType.Text.Plain)
        }
    }

    route("/page/stats") {
        get {
            fetchAllStats().apply {
                call.respond(synchronized(this) {
                    val stringBuilder = StringBuilder()
                    val groupViews = this.groupBy { pv ->
                        pv.page
                    }.values

                    //Count view per pages
                    groupViews.forEach {
                        stringBuilder.appendln("Page :${it.first().page} has been seen : ${it.count()} times")
                    }

                    //Count sessions
                    val nbSession = this.groupBy { pv ->
                        pv.sessionId
                    }.size

                    stringBuilder.appendln("Number of session :${nbSession}")

                    //Count view per session

                    val group = this.groupingBy { it.sessionId }
                    group.eachCount().forEach { (session, count) ->
                        stringBuilder.appendln("session :${session} has seen : $count pages")
                    }
                    stringBuilder.toString()
                })
            }

        }
    }
}

/**
 * Fetch all Stats
 */
fun fetchAllStats() = transaction { PageView.all().map { it.toPageViewsJson() } }

