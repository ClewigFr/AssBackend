package com.ass.routing

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.root() {
    get("/") {
        call.respondText("Clewig vous souhaite la bienvenue sur Anonymous Simple Stats !", contentType = ContentType.Text.Plain)
    }
}
