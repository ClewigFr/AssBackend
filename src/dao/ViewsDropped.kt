package com.ass.dao

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Views dropped by the flood limits (ViewGuard), per application and hour (epoch ms, UTC). */
object ViewsDropped : Table("viewsdropped") {
    val app = reference("app", Apps, onDelete = ReferenceOption.CASCADE)
    val hour = long("hour")
    val views = long("views")

    override val primaryKey = PrimaryKey(app, hour)
}
