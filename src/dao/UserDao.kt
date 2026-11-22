package com.ass.dao

import com.ass.db.Db
import com.ass.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

suspend fun fetchUser(db: Db, email: String): User? = db.query {
    User.find { Users.email eq email }.firstOrNull()
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

suspend fun createUser(db: Db, email: String, displayName: String, password: String) {
    val hash = withContext(Dispatchers.Default) { hashPassword(password) }
    db.query {
        User.new {
            this.email = email
            this.displayName = displayName
            passwordHash = hash
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
