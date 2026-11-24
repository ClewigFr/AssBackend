package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Pending email verification links; only a SHA-256 of the token is stored (see [PasswordResets]). */
object EmailVerifications : Table("emailverifications") {
    val tokenHash = varchar("token_hash", 64)
    val user = reference("user", Users, onDelete = ReferenceOption.CASCADE).index()
    /** Epoch ms. */
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(tokenHash)
}
