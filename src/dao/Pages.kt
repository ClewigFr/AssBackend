package com.ass.dao

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object Pages : UUIDTable() {
    val name = varchar("name", 255)
    /** Creator of the page; null for pages created before ownership existed. */
    val owner = reference("owner", Users).nullable()
}
