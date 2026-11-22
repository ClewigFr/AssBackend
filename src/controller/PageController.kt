package com.ass.controller

import com.ass.auth.currentUser
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.db.Db
import com.ass.ingest.PageCache
import com.ass.model.Page
import com.ass.model.PageIn
import com.ass.model.PageOut
import com.ass.model.PagesOut
import com.ass.stats.StatsService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Page feature routes
 */
fun Route.pages(db: Db, pageCache: PageCache, statsService: StatsService) {
    route("/pages") {
        get {
            val pages = db.query { Page.all().map { PageOut(it.id.value, it.name) } }
            call.respond(PagesOut(pages))
        }

        // {"name":"pageName"}
        post {
            val user = call.currentUser(db)
                ?: return@post call.respondText("not logged", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            val name = call.receiveNullable<PageIn>()?.name?.trim()
            if (name.isNullOrEmpty()) {
                return@post call.respondText("name is required", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }
            val page = db.query {
                Page.new {
                    this.name = name
                    owner = user
                }.let { PageOut(it.id.value, it.name) }
            }
            pageCache.added(page.id)
            statsService.invalidate()
            call.respondText("Page : ${page.name}, id: ${page.id}", ContentType.Text.Plain, HttpStatusCode.Created)
        }

        delete {
            val user = call.currentUser(db)
                ?: return@delete call.respondText("not logged", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            val pageId = call.receiveNullable<PageIn>()?.id
                ?: return@delete call.respondText("id is required", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            val deleted = db.query {
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
                pageCache.removed(pageId)
                statsService.invalidate()
                call.respondText("remove $pageId", ContentType.Text.Plain)
            } else {
                call.respondText("page not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            }
        }
    }
}
