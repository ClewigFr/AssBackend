package com.ass.dao

import org.jetbrains.exposed.dao.id.UUIDTable

object Pages : UUIDTable() {
    val name = varchar("name", 255)
    /** Creator of the page; null for pages created before ownership existed. */
    val owner = reference("owner", Users).nullable()
}
