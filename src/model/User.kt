package com.ass.model

import com.ass.dao.Users
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID

class User(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<User>(Users)
    var email by Users.email
    var displayName by Users.displayName
    var passwordHash by Users.passwordHash
    var sessionVersion by Users.sessionVersion
}
