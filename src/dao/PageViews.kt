package com.ass.dao

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object PagesViews : IntIdTable() {
    val page = reference("page", Pages, onDelete = ReferenceOption.CASCADE)
    val sessionId = uuid("sessionId")
    val timestamp = long("timestamp")
}
