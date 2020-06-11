package com.ass.controller

import com.ass.model.Page
import com.ass.model.PageView
import com.ass.model.PostPageViews
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Routing
import io.ktor.routing.post
import io.ktor.routing.route
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Page feature routes
 */
fun Routing.pageViews() {
    route("/views") {
        // example :
        // {
        //    "pageViews": [
        //        {
        //            "page": "d9ced6a4-4151-4bd2-b9d9-457bf83d03a9",
        //            "sessionId": "e6c8a676-b9b2-a108-b9b2-7b7871951ef6",
        //            "timestamp": 1
        //        },
        //         {
        //            "page": "d9ced6a4-4151-4bd2-b9d9-457bf83d03a9",
        //            "sessionId": "e6c8a676-b9b2-a108-b9b2-7b7871951ef6",
        //            "timestamp": 1
        //        }
        //    ]
        //}
        post {
            call.receive<PostPageViews>().run {
                addPageViews(this)
            }.also {
                call.respond(it)
            }
        }
    }
}

/**
 * Save page views hits received in post request
 *
 * @param post the post request containing page view hits
 *
 * @return request HttpStatusCode
 */
fun addPageViews(post: PostPageViews): HttpStatusCode {
    var statusCode = HttpStatusCode.Continue
    transaction {
        post.pageViews.forEach { hit ->
            val pageDb = Page.findById(hit.page)
            if (pageDb != null) {
                PageView.new {
                    page = pageDb
                    sessionId = hit.sessionId
                    timestamp = System.currentTimeMillis() - hit.timestamp
                }
                // Handle successfull request
                statusCode = statusCode.handleAddPageViewsStatusCode(true)
            } else {
                // Handle page not found
                statusCode = statusCode.handleAddPageViewsStatusCode(false)
            }
        }
    }
    // Handle bad request
    if (statusCode == HttpStatusCode.Continue) {
        statusCode = statusCode.handleAddPageViewsStatusCode(false)
    }
    return statusCode
}

fun HttpStatusCode.handleAddPageViewsStatusCode(success: Boolean) = when (this) {
    HttpStatusCode.Continue -> if (success) HttpStatusCode.OK else HttpStatusCode.BadRequest
    HttpStatusCode.OK -> if (success) HttpStatusCode.OK else HttpStatusCode.PartialContent
    HttpStatusCode.BadRequest -> if (success) HttpStatusCode.PartialContent else HttpStatusCode.BadRequest
    else -> HttpStatusCode.PartialContent
}