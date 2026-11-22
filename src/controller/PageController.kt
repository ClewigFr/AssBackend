package com.ass.controller

import com.ass.auth.currentUser
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.model.Page
import com.ass.model.PageJson
import com.ass.model.toPageJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Page feature routes
 */
fun Route.pages() {
    route("/pages") {
        get {
            fetchAllPages().apply {
                call.respond(mapOf("pages" to this))
            }
        }

        // example :
        // {
        //   "name":"pageName"
        // }
        post {
            val user = call.currentUser()

            if (user == null) {
                call.respondText("not logged", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            } else {
                val post = call.receiveNullable<PageJson>()
                if (post == null || (post.name as String?).isNullOrBlank()) {
                    return@post call.respondText("name is required", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                }
                val page = transaction {
                    Page.new {
                        name = post.name.trim()
                        owner = user
                    }
                }
                val message = "Page : ${page.name}, id: ${page.id}"
                call.respondText(message, ContentType.Text.Plain, HttpStatusCode.Created)
            }
        }

        delete {
            val user = call.currentUser()

            if (user == null) {
                call.respondText("not logged", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            } else {
                val post = call.receiveNullable<PageJson>()
                val pageId = post?.id as java.util.UUID?
                    ?: return@delete call.respondText("id is required", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                val deleted = transaction {
                    val page = Page.findById(pageId)
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
                    call.respondText("remove $pageId", contentType = ContentType.Text.Plain)
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