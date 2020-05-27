package com.ass.dao

import com.ass.model.User
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

fun fetchUser(userId: String, hash: String? = null) : User? = transaction {
    User.find { Users.email eq userId }.firstOrNull() // TODO VERIF password hash
}

fun verifUser(userId: String, hash: String) : User? = transaction {
    User.find { Users.email eq userId and (Users.passwordHash eq hash) }.firstOrNull() // TODO VERIF password hash
}

fun createUser(emailParam: String, displayNameParam: String, passwordParam: String) {
    transaction {
        User.new {
            email = emailParam
            displayName = displayNameParam
            passwordHash = passwordParam
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