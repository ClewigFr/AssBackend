package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object PagesViews : LongIdTable() {
    val page = reference("page", Pages, onDelete = ReferenceOption.CASCADE).index()
    val sessionId = javaUUID("sessionId")
    val timestamp = long("timestamp").index()
}
