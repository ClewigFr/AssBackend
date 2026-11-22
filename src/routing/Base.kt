package com.ass.routing

import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get

fun Routing.root() {
    get("/") {
        call.respondText("Clewig vous souhaite la bienvenue sur Anonymous Simple Stats !", contentType = ContentType.Text.Plain)
    }
}
