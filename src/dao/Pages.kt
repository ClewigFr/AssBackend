package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object Pages : UUIDTable() {
    val name = varchar("name", 255)
    /** The application this page belongs to; null only for very old pages that had no owner. */
    val app = reference("app", Apps, onDelete = ReferenceOption.CASCADE).nullable().index()
    /** Legacy: creator of the page before applications existed. Kept for the data migration. */
    val owner = reference("owner", Users).nullable()
}
