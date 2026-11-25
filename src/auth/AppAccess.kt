package com.ass.auth

import com.ass.dao.AppMembers
import com.ass.dao.Apps
import com.ass.model.User
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

/**
 * What an account may do on an application, each level including the ones before:
 * READ sees the pages and stats, MANAGE also creates and deletes pages and deletes views,
 * OWNER also deletes the application and shares it.
 */
enum class AppRole(val wire: String) {
    READ("read"), MANAGE("manage"), OWNER("owner");

    companion object {
        /** A role that can be given to a member (never OWNER). */
        fun memberRole(wire: String?): AppRole? = entries.firstOrNull { it.wire == wire && it != OWNER }
    }
}

/** The role of [user] on [app], or null when they have no access (or the app doesn't exist). */
fun JdbcTransaction.roleOn(app: UUID, user: User): AppRole? {
    val owner = Apps.select(Apps.owner).where { Apps.id eq app }.firstOrNull()?.get(Apps.owner) ?: return null
    if (owner == user.id) return AppRole.OWNER
    val wire = AppMembers.select(AppMembers.role)
        .where { (AppMembers.app eq app) and (AppMembers.user eq user.id) }
        .firstOrNull()?.get(AppMembers.role)
    return AppRole.entries.firstOrNull { it.wire == wire && it != AppRole.OWNER }
}
