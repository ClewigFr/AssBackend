package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object PagesViews : LongIdTable() {
    val page = reference("page", Pages, onDelete = ReferenceOption.CASCADE).index()
    /** Copied from the page so per-app stats read one index instead of joining Pages. */
    val app = reference("app", Apps, onDelete = ReferenceOption.CASCADE).nullable()
    val sessionId = javaUUID("sessionId")
    val timestamp = long("timestamp").index()

    init {
        index(false, app, timestamp)
    }
}
