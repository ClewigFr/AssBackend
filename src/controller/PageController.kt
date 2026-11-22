package com.ass.controller

import com.ass.ASSSession
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.dao.fetchUser
import com.ass.model.Page
import com.ass.model.PageJson
import com.ass.model.toPageJson
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
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
                    Page.new {
                        name = post.name
                        owner = user
                    }
                }
                val message = "Page : ${page.name}, id: ${page.id}"
                call.respondText(message, contentType = ContentType.Text.Plain)
            }
        }

        delete {
            val user = call.sessions.get<ASSSession>()?.let { fetchUser(it.userId) }

            if (user == null) {
                call.respondText("not logged", contentType = ContentType.Text.Plain)
            } else {
                val post = call.receive<PageJson>()
                val deleted = transaction {
                    val page = Page.findById(post.id)
                    // Only the page's owner may delete it (and its views).
                    if (page == null || page.owner?.id != user.id) {
                        false
                    } else {
                        PagesViews.deleteWhere { PagesViews.page eq page.id }
                        Pages.deleteWhere { Pages.id eq page.id }
                        true
                    }
                }
                if (deleted) {
                    call.respondText("remove ${post.id}", contentType = ContentType.Text.Plain)
                } else {
                    call.respondText(
                        "page not found",
                        contentType = ContentType.Text.Plain,
                        status = HttpStatusCode.NotFound
                    )
                }
            }
        }
    }
}

/**
 * Fetch all pages
 */
fun fetchAllPages() = transaction { Page.all().map { it.toPageJson() } }