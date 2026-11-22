package com.ass.controller

import com.ass.auth.currentUser
import com.ass.auth.startSession
import com.ass.registrationOpen
import com.ass.dao.createUser
import com.ass.dao.fetchUser
import com.ass.dao.userNameValid
import com.ass.model.User
import io.ktor.server.application.log
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Register routes for user registration in the [Register] route (/register)
 */
fun Route.register() {
    /**
     * A POST request to the [Register] route, will try to create a new user.
     *
     * - If the user is already logged, it redirects to the [UserPage] page.
     * - If not specified the userId, password, displayName or email, it will redirect to the [Register] form page.
     * - Then it will validate the specified parameters, redirecting displaying an error to the [Register] page.
     * - On success, it generates a new [User]. But instead of storing the password plain text,
     *   it stores a hash of the password.
     */
    route("/register") {
        post {
            // get current from session data if any
            if (!call.application.registrationOpen) {
                return@post call.respond(HttpStatusCode.Forbidden, "Registration is closed")
            }
            val user = call.currentUser()
            // user already logged in, redirect to user page.
            if (user != null) return@post call.respond("Logged")

            // receive post data
            // TODO: use conneg when it's ready and `call.receive<Register>()`
            val registration = call.receiveParameters()
            val password = registration["password"] ?: ""
            val displayName = registration["displayName"] ?: ""
            val email = registration["email"] ?: ""

            when {
                password.length < 6 -> call.respond(HttpStatusCode.BadRequest, "Password should be at least 6 characters long")
                email.length < 4 -> call.respond(HttpStatusCode.BadRequest, "Login should be at least 4 characters long")
                !userNameValid(email) -> call.respond(HttpStatusCode.BadRequest, "Login should be a valid email address")
                fetchUser(email) != null -> call.respond(HttpStatusCode.Conflict, "User with the following login is already registered")
                else -> {
                    try {
                        createUser(email, displayName, password)
                    } catch (e: Throwable) {
                        when {
                            // NOTE: This is security issue that allows to enumerate/verify registered users. Do not do this in real app :)
                            fetchUser(email) != null -> call.respond(HttpStatusCode.Conflict, "User with the following login is already registered")
                            else -> {
                                call.application.log.error("Failed to register user", e)
                                call.respond(HttpStatusCode.InternalServerError, "Failed to register")
                            }
                        }
                        return@post
                    }

                    call.startSession(email)
                    call.respond(HttpStatusCode.Created, "User $email created")
                }
            }
        }

        /**
         * A GET request would show the registration form (with an error if specified by the URL in the case there was an error in the form processing)
         * If the user is already logged, it redirects the client to the [UserPage] instead.
         */
        get {
            val user = call.currentUser()
            if (user != null) {
                call.respond("User ${user.email}")
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Not logged")
            }
        }
    }
}
