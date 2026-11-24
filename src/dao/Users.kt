package com.ass.dao

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Represents the Users table using Exposed as DAO.
 */
object Users : IntIdTable() {
    val email = varchar("email", 128).uniqueIndex()
    val displayName = varchar("display_name", 256)
    val passwordHash = varchar("password_hash", 64)
    /** Bumped on logout: every session cookie issued before stops working. */
    val sessionVersion = integer("session_version").default(0)
    /** False until the emailed link is opened; accounts created before verification existed are verified. */
    val emailVerified = bool("email_verified").default(true)
    /** Epoch ms; 0 for accounts created before it was recorded. Unverified accounts are purged after a day. */
    val createdAt = long("created_at").default(0)
}
