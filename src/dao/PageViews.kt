package com.ass.dao

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.java.javaUUID

object PagesViews : IntIdTable() {
    val page = reference("page", Pages, onDelete = ReferenceOption.CASCADE)
    val sessionId = javaUUID("sessionId")
    val timestamp = long("timestamp")
}
