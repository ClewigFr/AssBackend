package com.ass.signup

import io.ktor.util.hex
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cheap bot filters for POST /register, invisible to people:
 *
 * - a form token, signed with a per-process key, carrying when the form was loaded
 *   (GET /register/form): a submission less than [minSeconds] after it is from a bot;
 * - a honeypot field ([HONEYPOT_FIELD]), hidden in the form, that only bots fill in;
 * - a global cap on accounts created per hour ([maxPerHour]), so a botnet with many IPs
 *   can't flood the users table or have the server email thousands of strangers.
 *
 * Bots caught by the first two get a fake success, so they don't learn what gave them away.
 */
class SignupGuard(val minSeconds: Int, private val maxPerHour: Int) {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var windowStart = 0L
    private var created = 0

    val enabled get() = minSeconds > 0

    fun formToken(now: Long = System.currentTimeMillis()): String = "$now.${sign(now)}"

    enum class Check { OK, INVALID, BOT }

    fun check(token: String?, honeypot: String?, now: Long = System.currentTimeMillis()): Check {
        if (!honeypot.isNullOrEmpty()) return Check.BOT
        if (!enabled) return Check.OK
        val issued = token?.substringBefore('.')?.toLongOrNull() ?: return Check.INVALID
        val signature = token.substringAfter('.')
        if (!MessageDigest.isEqual(signature.toByteArray(), sign(issued).toByteArray())) return Check.INVALID
        return when {
            now - issued > MAX_FORM_AGE_MS || issued > now -> Check.INVALID
            now - issued < minSeconds * 1000L -> Check.BOT
            else -> Check.OK
        }
    }

    /** Counts one account creation; false (not counted) when this hour's cap is reached. */
    @Synchronized
    fun takeSignup(now: Long = System.currentTimeMillis()): Boolean {
        if (maxPerHour <= 0) return true
        if (now - windowStart >= 3_600_000) { windowStart = now; created = 0 }
        if (created >= maxPerHour) return false
        created++
        return true
    }

    private fun sign(issued: Long): String = Mac.getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(key, "HmacSHA256")) }
        .doFinal(issued.toString().toByteArray()).let(::hex)

    companion object {
        const val HONEYPOT_FIELD = "website"
        /** A form left open longer must be reloaded (the dashboard does it on its own). */
        const val MAX_FORM_AGE_MS = 2L * 60 * 60 * 1000
    }
}
