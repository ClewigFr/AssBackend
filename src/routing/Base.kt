package com.ass.routing

import com.ass.db.Db
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.root() {
    get("/") {
        call.respondText("Clewig vous souhaite la bienvenue sur Anonymous Simple Stats !", contentType = ContentType.Text.Plain)
    }
}

/** For container health checks: 200 when the database answers, 503 otherwise. */
fun Route.health(db: Db) {
    get("/health") {
        val ok = runCatching { db.query { exec("SELECT 1") { } } }.isSuccess
        if (ok) call.respondText("OK")
        else call.respondText("DB unavailable", status = HttpStatusCode.ServiceUnavailable)
    }
}
