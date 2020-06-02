package com.ass.routing

import com.ass.ASSSession
import com.ass.MustacheUser
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.mustache.MustacheContent
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import io.ktor.sessions.set


fun Routing.root() {
    get("/") {
        call.respondText("Clewig vous souhaite la bienvenue sur Anonymous Simples Stats !", contentType = ContentType.Text.Plain)
    }

    get("/html-mustache") {
        call.respond(MustacheContent("index.hbs", mapOf("user" to MustacheUser(1, "user1"))))
    }

    // Static feature. Try to access `/static/ktor_logo.svg`
    static("/static") {
        resources("static")
    }

    get("/session/increment") {
        val session = call.sessions.get<ASSSession>() ?: ASSSession()
        call.sessions.set(session.copy(count = session.count + 1))
        call.respondText("Counter is ${session.count}. Refresh to increment.")
    }

    get("/json/gson") {
        call.respond(mapOf("hello" to "world"))
    }
}