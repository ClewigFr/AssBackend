package com.ass.dao

import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.jdbc.select
import org.mindrot.jbcrypt.BCrypt

/**
 * Hash a password with bcrypt (salted, slow by design).
 */
fun hashPassword(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt(12))

fun isBcryptHash(hash: String) = hash.startsWith("$2")

/**
 * Check a password against a stored bcrypt hash. Anything else (the former HMAC-SHA1 hashes,
 * whose key was in the source) never matches: those accounts get back in with a password reset.
 */
fun checkPassword(password: String, storedHash: String): Boolean {
    if (isBcryptHash(storedHash)) return BCrypt.checkpw(password, storedHash)
    // Same cost as a real check, so timing doesn't tell these accounts apart
    BCrypt.checkpw(password, DUMMY_HASH)
    return false
}

private val DUMMY_HASH: String by lazy { hashPassword("not-a-real-password") }

/**
 * Accounts still on the removed HMAC-SHA1 hash can't log in any more. Logged at start so the
 * admin can tell them to use "Mot de passe oublié", or delete the accounts.
 */
fun warnAboutLegacyHashes(db: com.ass.db.Db) {
    val count = org.jetbrains.exposed.v1.jdbc.transactions.transaction(db.database) {
        Users.select(Users.id).where { Users.passwordHash notLike "\$2%" }.count()
    }
    if (count > 0) org.slf4j.LoggerFactory.getLogger("Passwords").warn(
        "$count account(s) still have a legacy password hash, which is no longer accepted: " +
            "they must reset their password (\"forgot password\"), or be deleted."
    )
}
