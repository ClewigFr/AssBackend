package com.ass.controller

import com.ass.ASSSession
import com.ass.dao.createUser
import com.ass.dao.fetchUser
import com.ass.dao.userNameValid
import com.ass.model.User
import io.ktor.application.application
import io.ktor.application.call
import io.ktor.application.log
import io.ktor.http.Parameters
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import io.ktor.sessions.set

/**
 * Register routes for user registration in the [Register] route (/register)
 */
fun Route.register(hashFunction: (String) -> String) {
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
            val user = call.sessions.get<ASSSession>()?.let { fetchUser(it.userId) }
            // user already logged in, redirect to user page.
            if (user != null) return@post call.respond("Logged")

            // receive post data
            // TODO: use conneg when it's ready and `call.receive<Register>()`
            val registration = call.receive<Parameters>()
            val password = registration["password"] ?: ""
            val displayName = registration["displayName"] ?: ""
            val email = registration["email"] ?: ""

            when {
                password.length < 6 ->call.respond("Password should be at least 6 characters long")
                email.length < 4 -> call.respond("Login should be at least 4 characters long")
                !userNameValid(email) -> call.respond("Login should only contains digits, letters, dots or underscores")
                fetchUser(email) != null ->call.respond("User with the following login is already registered")
                else -> {
                    val hash = hashFunction(password)

                    try {
                        createUser(email, displayName, hash)
                    } catch (e: Throwable) {
                        when {
                            // NOTE: This is security issue that allows to enumerate/verify registered users. Do not do this in real app :)
                            fetchUser(email) != null -> call.respond("User with the following login is already registered")
                            else -> {
                                application.log.error("Failed to register user", e)
                                call.respond("Failed to register")
                            }
                        }
                    }

                    call.sessions.set(ASSSession(email))
                    call.respond("User $email created")
                }
            }
        }

        /**
         * A GET request would show the registration form (with an error if specified by the URL in the case there was an error in the form processing)
         * If the user is already logged, it redirects the client to the [UserPage] instead.
         */
        get {
            val user = call.sessions.get<ASSSession>()?.let { fetchUser(it.userId) }
            if (user != null) {
                call.respond("User ${user.email}")
            } else {
                call.respond("Not logged")
            }
        }
    }
}
