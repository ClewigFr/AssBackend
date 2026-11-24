package com.ass.account

import com.ass.controller.BCRYPT_MAX_BYTES
import com.ass.controller.MIN_PASSWORD_LENGTH
import com.ass.controller.limitedTo
import com.ass.dao.PasswordResets
import com.ass.dao.Users
import com.ass.dao.fetchUser
import com.ass.dao.hashPassword
import com.ass.db.Db
import com.ass.mail.Mailer
import com.ass.model.ForgotIn
import com.ass.model.ResetIn
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

const val RESET_TOKEN_TTL_MS = 60L * 60 * 1000
/** Reset links that can be pending at once for one account (older ones are dropped). */
const val MAX_PENDING_RESETS = 3

/**
 * Forgotten password:
 *
 * POST /password/forgot {"email"} -> 204, always (whether the account exists isn't revealed).
 *      If it exists, emails a link to <dashboard>/#/reset?token=…, valid for 1 hour, usable once.
 * POST /password/reset  {"token","new"} -> 204: sets the password, signs out every session and
 *      cancels the other links. 400 if the token is unknown, used or expired, or the password invalid.
 */
fun Route.passwordReset(db: Db, mailer: Mailer, dashboardUrl: String, rateLimited: Boolean) {
    route("/password") {
        limitedTo(rateLimited) {
            post("/forgot") {
                val email = call.receiveNullable<ForgotIn>()?.email?.trim()
                if (email.isNullOrEmpty() || email.length > 128) return@post call.respond(HttpStatusCode.BadRequest, "email is required")
                // In the background: the answer takes the same time whether or not the account exists
                application.launch {
                    try {
                        val user = fetchUser(db, email) ?: return@launch
                        val token = newToken()
                        val now = System.currentTimeMillis()
                        db.query {
                            PasswordResets.deleteWhere { PasswordResets.expiresAt lessEq now }
                            val pending = PasswordResets.selectAll().where { PasswordResets.user eq user.id }
                                .orderBy(PasswordResets.expiresAt).map { it[PasswordResets.tokenHash] }
                            pending.dropLast(MAX_PENDING_RESETS - 1).forEach { old -> PasswordResets.deleteWhere { tokenHash eq old } }
                            PasswordResets.insert {
                                it[tokenHash] = sha256(token)
                                it[PasswordResets.user] = user.id
                                it[expiresAt] = now + RESET_TOKEN_TTL_MS
                            }
                        }
                        mailer.send(user.email, "Réinitialiser votre mot de passe Anonymous Simple Stats", resetEmail("$dashboardUrl/#/reset?token=$token"))
                    } catch (e: Exception) {
                        application.log.error("Password reset email failed", e)
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }

            post("/reset") {
                val body = call.receiveNullable<ResetIn>()
                val token = body?.token ?: ""
                val new = body?.new ?: ""
                when {
                    new.length < MIN_PASSWORD_LENGTH ->
                        return@post call.respond(HttpStatusCode.BadRequest, "Password should be at least $MIN_PASSWORD_LENGTH characters long")
                    new.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_BYTES ->
                        return@post call.respond(HttpStatusCode.BadRequest, "Password should be at most $BCRYPT_MAX_BYTES bytes long")
                    token.length !in 20..100 -> return@post call.respond(HttpStatusCode.BadRequest, "invalid or expired link")
                }
                val hash = withContext(Dispatchers.Default) { hashPassword(new) }
                val now = System.currentTimeMillis()
                val done = db.query {
                    val user = PasswordResets.select(PasswordResets.user)
                        .where { (PasswordResets.tokenHash eq sha256(token)) and (PasswordResets.expiresAt greater now) }
                        .firstOrNull()?.get(PasswordResets.user) ?: return@query false
                    Users.update({ Users.id eq user }) {
                        it[passwordHash] = hash
                        it[sessionVersion] = sessionVersion + 1
                    }
                    PasswordResets.deleteWhere { PasswordResets.user eq user }
                    true
                }
                if (!done) return@post call.respond(HttpStatusCode.BadRequest, "invalid or expired link")
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private val random = SecureRandom()

private fun newToken(): String = ByteArray(32).also { random.nextBytes(it) }
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private fun sha256(token: String): String = hex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

private fun resetEmail(link: String) = """
    Bonjour,

    Quelqu'un (vous, sans doute) a demandé à réinitialiser le mot de passe de votre compte Anonymous Simple Stats.

    Pour choisir un nouveau mot de passe, ouvrez ce lien dans l'heure :
    $link

    Le lien ne sert qu'une fois. Une fois le mot de passe changé, tous vos appareils sont déconnectés.

    Si vous n'avez rien demandé, ignorez ce message : votre mot de passe ne change pas.
""".trimIndent()

/** Mailer from the ktor.mail settings, or a [com.ass.mail.LogMailer] without SMTP_HOST. */
fun Application.mailerFromConfig(dev: Boolean): Mailer {
    fun prop(name: String) = environment.config.propertyOrNull("ktor.mail.$name")?.getString()?.takeIf { it.isNotBlank() }
    val host = prop("smtp_host") ?: return com.ass.mail.LogMailer(logBody = dev)
    val user = prop("smtp_user")
    return com.ass.mail.SmtpMailer(com.ass.mail.SmtpSettings(
        host = host,
        port = prop("smtp_port")?.toIntOrNull() ?: 465,
        tls = prop("smtp_tls") ?: "ssl",
        user = user,
        password = prop("smtp_password"),
        from = prop("from") ?: user ?: error("Set MAIL_FROM or SMTP_USER")
    ))
}
