package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

/** An application tracked by a user. Its UUID is what the Android library is configured with. */
object Apps : UUIDTable() {
    val name = varchar("name", 255)
    val owner = reference("owner", Users, onDelete = ReferenceOption.CASCADE).index()
}
