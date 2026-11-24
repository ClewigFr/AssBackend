package com.ass.dao

import com.ass.db.Db
import com.ass.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.update

/** Emails are compared case-insensitively ("A@x.fr" and "a@x.fr" are one account). */
fun normalizeEmail(email: String) = email.trim().lowercase()

suspend fun fetchUser(db: Db, email: String): User? = db.query {
    User.find { Users.email.lowerCase() eq normalizeEmail(email) }.firstOrNull()
}

suspend fun fetchUserById(db: Db, id: Int): User? = db.query { User.findById(id) }

/** Revokes every session of the user (see [Users.sessionVersion]). */
suspend fun revokeSessions(db: Db, id: Int) = db.query {
    Users.update({ Users.id eq id }) { it[sessionVersion] = sessionVersion + 1 }
}

/**
 * Return the user if [password] matches, upgrading a legacy hash to bcrypt on success.
 * bcrypt runs on the CPU dispatcher, not while holding a database connection.
 */
suspend fun verifyUser(db: Db, email: String, password: String): User? {
    val user = fetchUser(db, email)
    val ok = withContext(Dispatchers.Default) {
        // Same bcrypt cost when the email is unknown, so timing doesn't reveal which accounts exist
        checkPassword(password, user?.passwordHash ?: DUMMY_HASH)
    }
    if (user == null || !ok) return null
    if (!isBcryptHash(user.passwordHash)) {
        val upgraded = withContext(Dispatchers.Default) { hashPassword(password) }
        db.query { Users.update({ Users.id eq user.id }) { it[passwordHash] = upgraded } }
    }
    return user
}

private val DUMMY_HASH: String by lazy { hashPassword("not-a-real-password") }

suspend fun createUser(db: Db, email: String, displayName: String, password: String, verified: Boolean = true): User {
    val hash = withContext(Dispatchers.Default) { hashPassword(password) }
    return db.query {
        User.new {
            this.email = normalizeEmail(email)
            this.displayName = displayName
            passwordHash = hash
            emailVerified = verified
            createdAt = System.currentTimeMillis()
        }
    }
}

/**
 * Pattern to validate an `userId`
 */
private val userIdPattern = "^[A-Za-z](.*)([@]{1})(.{1,})(\\.)(.{1,})".toRegex()

/**
 * Validates that an [userId] (that is also the user name) is a valid identifier.
 */
internal fun userNameValid(userId: String) = userId.matches(userIdPattern)
