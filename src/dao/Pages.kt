package com.ass.dao

import org.jetbrains.exposed.dao.id.UUIDTable

object Pages : UUIDTable() {
    val name = varchar("name", 255)
}
