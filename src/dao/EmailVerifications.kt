package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Pending email verification links; only a SHA-256 of the token is stored (see [PasswordResets]). */
object EmailVerifications : Table("emailverifications") {
    val tokenHash = varchar("token_hash", 64)
    val user = reference("user", Users, onDelete = ReferenceOption.CASCADE).index()
    /** Epoch ms. */
    val expiresAt = long("expires_at")
    /**
     * bcrypt hash of the password chosen at the sign-up that sent this link, applied when it is
     * opened; null for links resent at login (the password is already the account's).
     */
    val passwordHash = varchar("password_hash", 64).nullable()

    override val primaryKey = PrimaryKey(tokenHash)
}
