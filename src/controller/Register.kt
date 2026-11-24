package com.ass.controller

import com.ass.auth.currentUser
import com.ass.auth.startSession
import com.ass.registrationOpen
import com.ass.dao.createUser
import com.ass.dao.fetchUser
import com.ass.dao.normalizeEmail
import com.ass.dao.userNameValid
import com.ass.db.Db
import com.ass.dao.fetchUserById
import com.ass.model.User
import com.ass.signup.CaptchaVerifier
import com.ass.signup.EmailVerification
import com.ass.signup.SignupGuard
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveNullable
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** What protects sign-ups from bots; see [SignupGuard], [CaptchaVerifier], [EmailVerification]. */
class SignupProtection(
    val guard: SignupGuard,
    /** Null when Turnstile isn't configured (TURNSTILE_SECRET). */
    val captcha: CaptchaVerifier? = null,
    /** Public key the dashboard's Turnstile widget needs. */
    val captchaSiteKey: String? = null,
    /** Null when new accounts don't have to verify their email. */
    val verification: EmailVerification? = null
)

@Serializable
data class SignupFormOut(val formToken: String, val turnstileSiteKey: String? = null, val emailVerification: Boolean)

@Serializable
data class VerifyIn(val token: String? = null)

/**
 * Sign-up:
 *
 * GET  /register/form -> {formToken, turnstileSiteKey?, emailVerification}: load it with the form.
 * POST /register      form fields email, password, displayName, formToken, cf-turnstile-response
 *      (when Turnstile is on) and the empty honeypot [SignupGuard.HONEYPOT_FIELD].
 *      -> 201 "User … created" with a session, or, with email verification, 201 "Check your email"
 *      without one (also when the address is already registered: it isn't revealed).
 * POST /register/verify {token} -> 200 "Logged" with a session.
 */
fun Route.register(db: Db, rateLimited: Boolean, protection: SignupProtection) {
    val guard = protection.guard
    val verification = protection.verification
    route("/register") {
        get("/form") {
            call.respond(SignupFormOut(guard.formToken(), protection.captchaSiteKey, verification != null))
        }

        limitedTo(rateLimited) { post {
            if (!call.application.registrationOpen) {
                return@post call.respond(HttpStatusCode.Forbidden, "Registration is closed")
            }
            val user = call.currentUser(db)
            // user already logged in, redirect to user page.
            if (user != null) return@post call.respond("Logged")

            val registration = call.receiveParameters()
            val password = registration["password"] ?: ""
            val displayName = registration["displayName"]?.trim() ?: ""
            val email = normalizeEmail(registration["email"] ?: "")
            // Bots get the answer a person would get, and nothing is created
            val fakeSuccess = if (verification != null) CHECK_EMAIL else "User $email created"

            when (guard.check(registration["formToken"], registration[SignupGuard.HONEYPOT_FIELD])) {
                SignupGuard.Check.BOT -> return@post call.respond(HttpStatusCode.Created, fakeSuccess)
                SignupGuard.Check.INVALID -> return@post call.respond(HttpStatusCode.BadRequest, "Form expired, reload it")
                SignupGuard.Check.OK -> {}
            }

            when {
                password.length < MIN_PASSWORD_LENGTH -> return@post call.respond(HttpStatusCode.BadRequest, "Password should be at least $MIN_PASSWORD_LENGTH characters long")
                // bcrypt silently ignores everything after 72 bytes
                password.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_BYTES -> return@post call.respond(HttpStatusCode.BadRequest, "Password should be at most $BCRYPT_MAX_BYTES bytes long")
                email.length < 4 -> return@post call.respond(HttpStatusCode.BadRequest, "Login should be at least 4 characters long")
                email.length > MAX_EMAIL_LENGTH -> return@post call.respond(HttpStatusCode.BadRequest, "Login should be at most $MAX_EMAIL_LENGTH characters long")
                displayName.length > MAX_DISPLAY_NAME_LENGTH -> return@post call.respond(HttpStatusCode.BadRequest, "Display name should be at most $MAX_DISPLAY_NAME_LENGTH characters long")
                !userNameValid(email) -> return@post call.respond(HttpStatusCode.BadRequest, "Login should be a valid email address")
            }

            val captcha = protection.captcha
            if (captcha != null && !captcha.verify(registration[TURNSTILE_FIELD] ?: "", call.request.origin.remoteHost)) {
                return@post call.respond(HttpStatusCode.BadRequest, "Captcha failed, try again")
            }

            val existing = fetchUser(db, email)
            if (existing != null) {
                if (verification == null) return@post call.respond(HttpStatusCode.Conflict, "User with the following login is already registered")
                // Same answer as a new account; an unverified one gets a fresh link
                if (!existing.emailVerified) call.application.launch { sendQuietly(call.application, verification, existing) }
                return@post call.respond(HttpStatusCode.Created, CHECK_EMAIL)
            }
            if (!guard.takeSignup()) {
                call.application.log.warn("Sign-up refused: hourly cap reached")
                return@post call.respond(HttpStatusCode.TooManyRequests, "Too many sign-ups right now, try again later")
            }

            val created = try {
                createUser(db, email, displayName, password, verified = verification == null)
            } catch (e: Throwable) {
                when {
                    // Lost a race with the same email
                    fetchUser(db, email) != null -> call.respond(
                        if (verification == null) HttpStatusCode.Conflict else HttpStatusCode.Created,
                        if (verification == null) "User with the following login is already registered" else CHECK_EMAIL)
                    else -> {
                        call.application.log.error("Failed to register user", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to register")
                    }
                }
                return@post
            }

            if (verification != null) {
                call.application.launch { sendQuietly(call.application, verification, created) }
                call.respond(HttpStatusCode.Created, CHECK_EMAIL)
            } else {
                call.startSession(created)
                call.respond(HttpStatusCode.Created, "User $email created")
            }
        } }

        limitedTo(rateLimited) { post("/verify") {
            val id = verification?.verify(call.receiveNullable<VerifyIn>()?.token ?: "")
                ?: return@post call.respond(HttpStatusCode.BadRequest, "invalid or expired link")
            val user = fetchUserById(db, id) ?: return@post call.respond(HttpStatusCode.BadRequest, "invalid or expired link")
            call.startSession(user)
            call.respond("Logged")
        } }

        /**
         * A GET request would show the registration form (with an error if specified by the URL in the case there was an error in the form processing)
         * If the user is already logged, it redirects the client to the [UserPage] instead.
         */
        get {
            val user = call.currentUser(db)
            if (user != null) {
                call.respond("User ${user.email}")
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Not logged")
            }
        }
    }
}

internal suspend fun sendQuietly(app: Application, verification: EmailVerification, user: User) {
    try { verification.sendLink(user) } catch (e: Exception) { app.log.error("Verification email failed", e) }
}

const val CHECK_EMAIL = "Check your email"
const val TURNSTILE_FIELD = "cf-turnstile-response"

/** For new accounts; logins still accept the old 6-character minimum. */
const val MIN_PASSWORD_LENGTH = 8
const val BCRYPT_MAX_BYTES = 72
/** Login refuses longer passwords outright (they could never have been registered). */
const val MAX_PASSWORD_LENGTH = 256
const val MAX_EMAIL_LENGTH = 128
const val MAX_DISPLAY_NAME_LENGTH = 256
