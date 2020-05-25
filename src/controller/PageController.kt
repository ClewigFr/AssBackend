package com.ass.controller

import com.ass.model.Page
import com.ass.model.PostPageViews
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.request.receive
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
}