package com.ass.signup

import com.ass.account.newToken
import com.ass.account.sha256
import com.ass.dao.EmailVerifications
import com.ass.dao.Users
import com.ass.db.Db
import com.ass.mail.Mailer
import com.ass.model.User
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

const val VERIFY_TOKEN_TTL_MS = 24L * 60 * 60 * 1000
/** Verification links that can be pending at once for one account (older ones are dropped). */
const val MAX_PENDING_VERIFICATIONS = 3

/**
 * New accounts can't sign in until the link emailed to them is opened; unverified accounts are
 * deleted after [VERIFY_TOKEN_TTL_MS], so accounts made by bots with made-up addresses vanish.
 */
class EmailVerification(private val db: Db, private val mailer: Mailer, private val dashboardUrl: String) {

    /**
     * Emails a new link to [user]. With [passwordHash] (a sign-up over an unverified account),
     * opening this link sets that password: whoever registered an address first can't keep their
     * password once the owner of the address confirms it. Other pending links stay valid (each
     * applies its own sign-up's password), at most [MAX_PENDING_VERIFICATIONS].
     */
    suspend fun sendLink(user: User, passwordHash: String? = null, now: Long = System.currentTimeMillis()) {
        val token = newToken()
        val userId = user.id
        db.query {
            EmailVerifications.deleteWhere { expiresAt lessEq now }
            EmailVerifications.select(EmailVerifications.tokenHash).where { EmailVerifications.user eq userId }
                .orderBy(EmailVerifications.expiresAt).map { it[EmailVerifications.tokenHash] }
                .dropLast(MAX_PENDING_VERIFICATIONS - 1)
                .forEach { old -> EmailVerifications.deleteWhere { tokenHash eq old } }
            EmailVerifications.insert {
                it[tokenHash] = sha256(token)
                it[this.user] = userId
                it[expiresAt] = now + VERIFY_TOKEN_TTL_MS
                it[this.passwordHash] = passwordHash
            }
        }
        mailer.send(user.email, "Confirmez votre adresse Anonymous Simple Stats", verifyEmail("$dashboardUrl/#/verify?token=$token"))
    }

    /** Marks the account of [token] verified; its id, or null if the token is unknown or expired. */
    suspend fun verify(token: String, now: Long = System.currentTimeMillis()): Int? {
        if (token.length !in 20..100) return null
        return db.query {
            val row = EmailVerifications.select(EmailVerifications.user, EmailVerifications.passwordHash)
                .where { (EmailVerifications.tokenHash eq sha256(token)) and (EmailVerifications.expiresAt greater now) }
                .firstOrNull() ?: return@query null
            val user = row[EmailVerifications.user]
            val hash = row[EmailVerifications.passwordHash]
            Users.update({ Users.id eq user }) {
                it[emailVerified] = true
                // The password of the sign-up this link came from; other sessions are signed out
                if (hash != null) {
                    it[Users.passwordHash] = hash
                    it[sessionVersion] = sessionVersion + 1
                }
            }
            EmailVerifications.deleteWhere { EmailVerifications.user eq user }
            user.value
        }
    }

    /** Deletes expired links and the accounts never verified within a day. */
    suspend fun purge(now: Long = System.currentTimeMillis()) = db.query {
        EmailVerifications.deleteWhere { expiresAt lessEq now }
        Users.deleteWhere { (emailVerified eq false) and (createdAt less now - VERIFY_TOKEN_TTL_MS) }
    }
}

private fun verifyEmail(link: String) = """
    Bonjour,

    Pour activer votre compte Anonymous Simple Stats, ouvrez ce lien dans les 24 heures :
    $link

    Sans confirmation, le compte est supprimé au bout de 24 heures.

    Si vous n'avez pas créé de compte, ignorez ce message.
""".trimIndent()
