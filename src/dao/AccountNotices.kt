package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Account emails already sent, so each is sent once: kind "quota80" or "quota100" for the month
 * starting at [month], "summary" for the monthly summary of that month (epoch ms, UTC).
 */
object AccountNotices : Table("accountnotices") {
    val user = reference("user", Users, onDelete = ReferenceOption.CASCADE)
    val month = long("month")
    val kind = varchar("kind", 16)

    override val primaryKey = PrimaryKey(user, month, kind)
}
