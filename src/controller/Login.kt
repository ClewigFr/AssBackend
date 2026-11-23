package com.ass.controller

import com.ass.ASSSession
import com.ass.auth.LoginThrottle
import com.ass.auth.currentUser
import com.ass.auth.startSession
import com.ass.dao.revokeSessions
import com.ass.dao.userNameValid
import com.ass.dao.verifyUser
import com.ass.db.Db
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.rateLimit
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
fun Route.login(db: Db, rateLimited: Boolean) {
    /**
     * A GET request to the [Login], would respond with the login page
     */
    route("/login") {
        get {
            val user = call.currentUser(db)

            if (user != null) {
                call.respond("Logged")
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Not logged")
            }
        }

        /**
         * A POST request to the [Login] actually processes the [Parameters] to validate them, if valid it sets the session.
         */
        limitedTo(rateLimited) { post {
            val client = call.request.origin.remoteHost
            val post = call.receiveParameters()
            val userId = post["userId"] ?: ""
            val password = post["password"] ?: ""

            if (LoginThrottle.isLocked(userId, client)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, "Too many failed attempts, try again later")
            }

            val login = when {
                userId.length < 4 -> null
                password.length !in 6..MAX_PASSWORD_LENGTH -> null
                !userNameValid(userId) -> null
                else -> verifyUser(db, userId, password)
            }

            if (login == null) {
                LoginThrottle.recordFailure(userId, client)
                call.respond(HttpStatusCode.Unauthorized, "Invalid username or password")
            } else {
                LoginThrottle.recordSuccess(userId, client)
                call.startSession(login)
                call.respond("Logged")
            }
        } }
    }

    route("/logout") {
        /**
         * POST, not GET, so another site can't log the user out with an <img>. Revokes every
         * session of the user (all devices), so a copied cookie stops working too.
         */
        post {
            call.currentUser(db)?.let { revokeSessions(db, it.id.value) }
            call.sessions.clear<ASSSession>()
            call.respond("Logout")
        }
    }
}

/** POST /login and /register cost a bcrypt hash each: limited per client IP (see AUTH_RATE_LIMIT). */
internal fun Route.limitedTo(rateLimited: Boolean, build: Route.() -> Unit) {
    if (rateLimited) rateLimit(AUTH_RATE_LIMIT, build) else build()
}

val AUTH_RATE_LIMIT = io.ktor.server.plugins.ratelimit.RateLimitName("auth")
