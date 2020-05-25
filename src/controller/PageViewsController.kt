package com.ass.controller

import com.ass.model.Page
import com.ass.model.PageView
import com.ass.model.PostPageViews
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.post
import io.ktor.routing.route
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Page feature routes
 */
fun Routing.pageViews() {
    route("/pages") {
        post {
            val post = call.receive<PostPageViews>().run {
                addPageViews(this)
            }.also {
                call.respond(HttpStatusCode.Accepted)
            }
        }
    }
}

/**
 * Save Pageviews hits received in post request
 *
 * @param post the post request containing page views
 */
fun addPageViews(post: PostPageViews) {
    post.pageViews.forEach { hit ->
        transaction {
            Page.findById(hit.page)?.let {
                PageView.new {
                    page = it
                    sessionId = hit.sessionId
                    timestamp = System.currentTimeMillis() - hit.timestamp
                }
            }
        }
    }
}