package com.ass.dao

import com.ass.model.User
import org.jetbrains.exposed.sql.transactions.transaction

fun fetchUser(userId: String, hash: String? = null) = transaction {
    User.find { Users.email eq userId }.first() // TODO VERIF password hash
}

//TODO for dev purpose
fun createUser(){
    transaction {
        User.new {
            email = "walig@clewig.fr"
            displayName = "Gilaw"
            passwordHash = "test"
        }
    }
}