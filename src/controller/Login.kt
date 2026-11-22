package com.ass.controller

import com.ass.ASSSession
import com.ass.dao.fetchUser
import com.ass.dao.userNameValid
import com.ass.dao.verifyUser
import io.ktor.application.call
import io.ktor.http.Parameters
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.sessions.clear
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import io.ktor.sessions.set

/**
 * Registers the [Login] and [Logout] routes '/login' and '/logout'.
 */
fun Route.login() {
    /**
     * A GET request to the [Login], would respond with the login page
     */
    route("/login") {
        get {
            val user = call.sessions.get<ASSSession>()?.let { fetchUser(it.userId) }

            if (user != null) {
                call.respond("Logged")
            } else {
                call.respond("Log error")
            }
        }

        /**
         * A POST request to the [Login] actually processes the [Parameters] to validate them, if valid it sets the session.
         */
        post {
            val post = call.receive<Parameters>()
            val userId = post["userId"] ?: ""
            val password = post["password"] ?: ""

            val login = when {
                userId.length < 4 -> null
                password.length < 6 -> null
                !userNameValid(userId) -> null
                else -> verifyUser(userId, password)
            }

            if (login == null) {
                call.respond("Invalid username or password for $userId")
            } else {
                call.sessions.set(ASSSession(login.email))
                call.respond("Logged")
            }
        }
    }

    route("/logout") {
        /**
         * A GET request to the [Logout] page.
         */
        get {
            call.sessions.clear<ASSSession>()
            call.respond("Logout")
        }
    }
}