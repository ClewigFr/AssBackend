package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Views per page and per day, for data older than the retention period: raw views (with their
 * session ids) are rolled up here, then deleted. [day] is the start of the day in UTC (epoch ms).
 */
object PagesDaily : Table("pagesdaily") {
    val page = reference("page", Pages, onDelete = ReferenceOption.CASCADE)
    val app = reference("app", Apps, onDelete = ReferenceOption.CASCADE)
    val day = long("day")
    val views = long("views")

    override val primaryKey = PrimaryKey(page, day)

    init {
        index(false, app, day)
    }
}
