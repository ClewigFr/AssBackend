package com.ass.controller

import com.ass.ASSSession
import com.ass.dao.fetchUser
import com.ass.model.Page
import com.ass.model.PageJson
import com.ass.model.toPageJson
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Page feature routes
 */
fun Routing.pages() {
    route("/pages") {
        get {
            fetchAllPages().apply {
                call.respond(mapOf("pages" to synchronized(this) {
                    this.toList()
                }))
            }
        }

        // example :
        // {
        //   "name":"pageName"
        // }
        post {
            val user = call.sessions.get<ASSSession>()?.let { fetchUser(it.userId) }

            if (user == null) {
                call.respondText("not logged", contentType = ContentType.Text.Plain)
            } else {
                val post = call.receive<PageJson>()
                val page = transaction {
                    Page.new { name = post.name }
                }
                val message = "Page : ${page.name}, id: ${page.id}"
                call.respondText(message, contentType = ContentType.Text.Plain)
            }
        }
    }
}

/**
 * Fetch all pages
 */
fun fetchAllPages() = transaction { Page.all().map { it.toPageJson() } }