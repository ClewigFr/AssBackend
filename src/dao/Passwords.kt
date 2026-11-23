package com.ass.dao

import io.ktor.util.hex
import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.jdbc.select
import org.mindrot.jbcrypt.BCrypt
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hash a password with bcrypt (salted, slow by design).
 */
fun hashPassword(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt(12))

fun isBcryptHash(hash: String) = hash.startsWith("$2")

/**
 * Check a password against a stored hash. Supports the legacy HMAC-SHA1 hashes so existing
 * users can still log in; [verifyUser] upgrades them to bcrypt on their next login.
 */
fun checkPassword(password: String, storedHash: String): Boolean =
    if (isBcryptHash(storedHash)) BCrypt.checkpw(password, storedHash)
    else java.security.MessageDigest.isEqual(
        legacyHash(password).toByteArray(), storedHash.toByteArray()
    )

/** Legacy scheme, kept only to verify passwords stored before bcrypt. Never use it for new hashes. */
private val legacyKey = SecretKeySpec(hex("6819b57a326945c1968f45236589"), "HmacSHA1")

private fun legacyHash(password: String): String {
    val hmac = Mac.getInstance("HmacSHA1")
    hmac.init(legacyKey)
    return hex(hmac.doFinal(password.toByteArray(Charsets.UTF_8)))
}

/**
 * Accounts still on the legacy HMAC-SHA1 hash (unsalted, key in the source) are upgraded at their
 * next login. Logged at start so the admin can see when none are left, or reset the others.
 */
fun warnAboutLegacyHashes(db: com.ass.db.Db) {
    val count = org.jetbrains.exposed.v1.jdbc.transactions.transaction(db.database) {
        Users.select(Users.id).where { Users.passwordHash notLike "\$2%" }.count()
    }
    if (count > 0) org.slf4j.LoggerFactory.getLogger("Passwords").warn(
        "$count account(s) still use the legacy password hash: they are upgraded at their next login. " +
            "To get rid of them, reset their password (or delete the accounts)."
    )
}
