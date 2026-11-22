package com.ass.dao

import com.ass.model.User
import org.jetbrains.exposed.sql.transactions.transaction

fun fetchUser(userId: String): User? = transaction {
    User.find { Users.email eq userId }.firstOrNull()
}

/**
 * Return the user if [password] matches, upgrading a legacy hash to bcrypt on success.
 */
fun verifyUser(userId: String, password: String): User? = transaction {
    val user = User.find { Users.email eq userId }.firstOrNull() ?: return@transaction null
    if (!checkPassword(password, user.passwordHash)) return@transaction null
    if (!isBcryptHash(user.passwordHash)) user.passwordHash = hashPassword(password)
    user
}

fun createUser(emailParam: String, displayNameParam: String, password: String) {
    transaction {
        User.new {
            email = emailParam
            displayName = displayNameParam
            passwordHash = hashPassword(password)
        }
    }
}

/**
 * Pattern to validate an `userId`
 */
private val userIdPattern = "^[A-Za-z](.*)([@]{1})(.{1,})(\\.)(.{1,})".toRegex()

/**
 * Validates that an [userId] (that is also the user name) is a valid identifier.
 * Here we could add additional checks like the length of the user.
 * Or other things like a bad word filter.
 */
internal fun userNameValid(userId: String) = userId.matches(userIdPattern)