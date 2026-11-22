package com.ass.controller

import com.ass.ASSSession
import com.ass.auth.LoginThrottle
import com.ass.auth.currentUser
import com.ass.auth.startSession
import com.ass.dao.userNameValid
import com.ass.dao.verifyUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions

/**
 * Registers the [Login] and [Logout] routes '/login' and '/logout'.
 */
fun Route.login() {
    /**
     * A GET request to the [Login], would respond with the login page
     */
    route("/login") {
        get {
            val user = call.currentUser()

            if (user != null) {
                call.respond("Logged")
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Not logged")
            }
        }

        /**
         * A POST request to the [Login] actually processes the [Parameters] to validate them, if valid it sets the session.
         */
        post {
            val post = call.receiveParameters()
            val userId = post["userId"] ?: ""
            val password = post["password"] ?: ""

            if (LoginThrottle.isLocked(userId)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, "Too many failed attempts, try again later")
            }

            val login = when {
                userId.length < 4 -> null
                password.length < 6 -> null
                !userNameValid(userId) -> null
                else -> verifyUser(userId, password)
            }

            if (login == null) {
                LoginThrottle.recordFailure(userId)
                call.respond(HttpStatusCode.Unauthorized, "Invalid username or password")
            } else {
                LoginThrottle.recordSuccess(userId)
                call.startSession(login.email)
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