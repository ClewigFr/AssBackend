package com.ass.dao

import io.ktor.util.hex
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
