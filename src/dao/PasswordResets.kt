package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Pending password resets. Only a SHA-256 of the token is stored: the token itself is only in
 * the email, so reading the database doesn't let anyone reset a password.
 */
object PasswordResets : Table("passwordresets") {
    val tokenHash = varchar("token_hash", 64)
    val user = reference("user", Users, onDelete = ReferenceOption.CASCADE).index()
    /** Epoch ms. */
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(tokenHash)
}
