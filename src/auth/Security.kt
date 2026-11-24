package com.ass.auth

import com.ass.ASSSession
import com.ass.dao.fetchUserById
import com.ass.db.Db
import com.ass.model.User
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.util.concurrent.ConcurrentHashMap

/** How long a session stays valid after login, checked on the server (the cookie itself is only signed). */
const val SESSION_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/** Start a session for [user]. */
fun ApplicationCall.startSession(user: User) {
    sessions.set(ASSSession(userId = user.id.value.toString(), issuedAt = System.currentTimeMillis(), version = user.sessionVersion))
}

/**
 * The logged-in user, or null if there is no session, it has expired (or predates expiry
 * support), was revoked by a logout, or the user no longer exists.
 */
suspend fun ApplicationCall.currentUser(db: Db, now: Long = System.currentTimeMillis()): User? {
    val session = sessions.get<ASSSession>() ?: return null
    if (!isSessionValid(session, now)) return null
    val id = session.userId.toIntOrNull() ?: return null
    return fetchUserById(db, id)?.takeIf { it.sessionVersion == session.version }
}

fun isSessionValid(session: ASSSession, now: Long = System.currentTimeMillis()): Boolean =
    session.issuedAt in 1..now && now - session.issuedAt <= SESSION_MAX_AGE_MS

/**
 * In-memory brute-force protection for login: after [MAX_FAILURES] failed attempts on an
 * account from one client IP, further attempts from that IP are refused for [LOCK_MS].
 * Keyed by (account, IP) so a stranger can't lock the real user out; guessing from many
 * accounts or IPs is bounded by the per-IP rate limit on /login. Resets on a successful login.
 * Per instance only; good enough for a single server.
 */
object LoginThrottle {
    const val MAX_FAILURES = 5
    const val LOCK_MS = 15L * 60 * 1000
    /** Bounds memory when an attacker tries many accounts: stale entries go first. */
    const val MAX_ENTRIES = 10_000

    private class Entry(var failures: Int, var lockedUntil: Long, var lastFailure: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(email: String, client: String) = client + "|" + email.trim().lowercase()

    fun isLocked(email: String, client: String, now: Long = System.currentTimeMillis()): Boolean =
        (entries[key(email, client)]?.lockedUntil ?: 0) > now

    fun recordFailure(email: String, client: String, now: Long = System.currentTimeMillis()) {
        if (entries.size >= MAX_ENTRIES) {
            // Forget failures older than a lock period and expired locks, then everything if still full
            entries.entries.removeIf { it.value.lockedUntil <= now && now - it.value.lastFailure > LOCK_MS }
            if (entries.size >= MAX_ENTRIES) entries.clear()
        }
        entries.compute(key(email, client)) { _, e ->
            val entry = if (e == null || (e.lockedUntil in 1..now)) Entry(0, 0, now) else e
            entry.failures++
            entry.lastFailure = now
            if (entry.failures >= MAX_FAILURES) entry.lockedUntil = now + LOCK_MS
            entry
        }
    }

    fun recordSuccess(email: String, client: String) {
        entries.remove(key(email, client))
    }

    /** For tests. */
    val size get() = entries.size

    /** For tests. */
    fun reset() = entries.clear()
}
