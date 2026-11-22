package com.ass.auth

import com.ass.ASSSession
import com.ass.dao.fetchUser
import com.ass.model.User
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.util.concurrent.ConcurrentHashMap

/** How long a session stays valid after login, checked on the server (the cookie itself is only signed). */
const val SESSION_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/** Start a session for [email]. */
fun ApplicationCall.startSession(email: String) {
    sessions.set(ASSSession(userId = email, issuedAt = System.currentTimeMillis()))
}

/**
 * The logged-in user, or null if there is no session, it has expired (or predates expiry
 * support), or the user no longer exists.
 */
fun ApplicationCall.currentUser(now: Long = System.currentTimeMillis()): User? {
    val session = sessions.get<ASSSession>() ?: return null
    if (!isSessionValid(session, now)) return null
    return fetchUser(session.userId)
}

fun isSessionValid(session: ASSSession, now: Long = System.currentTimeMillis()): Boolean =
    session.issuedAt in 1..now && now - session.issuedAt <= SESSION_MAX_AGE_MS

/**
 * In-memory brute-force protection for login: after [MAX_FAILURES] failed attempts on an
 * account, further attempts are refused for [LOCK_MS]. Resets on a successful login.
 * Per instance only; good enough for a single dyno.
 */
object LoginThrottle {
    const val MAX_FAILURES = 5
    const val LOCK_MS = 15L * 60 * 1000

    private class Entry(var failures: Int, var lockedUntil: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(email: String) = email.trim().lowercase()

    fun isLocked(email: String, now: Long = System.currentTimeMillis()): Boolean =
        (entries[key(email)]?.lockedUntil ?: 0) > now

    fun recordFailure(email: String, now: Long = System.currentTimeMillis()) {
        entries.compute(key(email)) { _, e ->
            val entry = if (e == null || (e.lockedUntil in 1..now)) Entry(0, 0) else e
            entry.failures++
            if (entry.failures >= MAX_FAILURES) entry.lockedUntil = now + LOCK_MS
            entry
        }
    }

    fun recordSuccess(email: String) {
        entries.remove(key(email))
    }

    /** For tests. */
    fun reset() = entries.clear()
}
