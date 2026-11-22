package com.ass.controller

import com.ass.model.Page
import com.ass.model.PageView
import com.ass.model.PageViewsJson
import com.ass.model.PostPageViews
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveOrNull
import io.ktor.response.respond
import io.ktor.routing.Routing
import io.ktor.routing.post
import io.ktor.routing.route
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

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
            // A body that can't be parsed is the client's fault: answer 400 so it drops the
            // batch instead of retrying it forever (the Android client retries on 5xx).
            val post = call.receiveOrNull<PostPageViews>()
            if (post == null) {
                call.respond(HttpStatusCode.BadRequest)
            } else {
                call.respond(addPageViews(post))
            }
        }
    }
}

/** Oldest view accepted, as ms elapsed: the client may keep failed batches on disk for a while. */
const val MAX_VIEW_AGE_MS = 365L * 24 * 60 * 60 * 1000

/**
 * Save page views hits received in post request
 *
 * @param post the post request containing page view hits
 *
 * @return 200 if every hit was stored, 206 if only some were, 400 if none were
 */
fun addPageViews(post: PostPageViews): HttpStatusCode {
    var statusCode = HttpStatusCode.Continue
    val now = System.currentTimeMillis()
    transaction {
        // Gson ignores Kotlin nullability, so fields may really be null
        val hits = (post.pageViews as List<PageViewsJson?>?).orEmpty()
        val pages = hits.mapNotNull { it?.page as UUID? }.distinct()
            .mapNotNull { Page.findById(it) }.associateBy { it.id.value }
        hits.forEach { hit ->
            val pageDb = (hit?.page as UUID?)?.let { pages[it] }
            val sessionId = hit?.sessionId as UUID?
            val elapsed = hit?.timestamp
            val valid = pageDb != null && sessionId != null && elapsed != null && elapsed in 0..MAX_VIEW_AGE_MS
            if (valid) {
                PageView.new {
                    page = pageDb!!
                    this.sessionId = sessionId!!
                    timestamp = now - elapsed!!
                }
            }
            statusCode = statusCode.handleAddPageViewsStatusCode(valid)
        }
    }
    // Empty batch
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