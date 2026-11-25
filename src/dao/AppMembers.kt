package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Accounts an application is shared with, and their role ("read" or "manage", see
 * [com.ass.auth.AppRole]). The owner has no row here: ownership stays [Apps.owner], which is
 * also what quotas count. Rows disappear with the application or the account.
 */
object AppMembers : Table("app_members") {
    val app = reference("app", Apps, onDelete = ReferenceOption.CASCADE)
    val user = reference("account", Users, onDelete = ReferenceOption.CASCADE).index()
    val role = varchar("role", 16)
    override val primaryKey = PrimaryKey(app, user)
}
