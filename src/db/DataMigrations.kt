package com.ass.db

import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

const val DEFAULT_APP_NAME = "Application par défaut"

/**
 * One-time data migration to applications (idempotent, safe to run on every start):
 * 1. pages created before applications get a default application per owner;
 * 2. views get their page's application copied in.
 */
fun JdbcTransaction.migrateToApplications() {
    val log = LoggerFactory.getLogger("DataMigrations")

    val owners = Pages.select(Pages.owner)
        .where { Pages.app.isNull() and Pages.owner.isNotNull() }
        .withDistinct()
        .mapNotNull { it[Pages.owner] }
    owners.forEach { owner ->
        val appId = Apps.insertAndGetId {
            it[name] = DEFAULT_APP_NAME
            it[Apps.owner] = owner
        }
        val moved = Pages.update({ Pages.app.isNull() and (Pages.owner eq owner) }) { it[app] = appId }
        log.info("Moved $moved pages into a default application")
    }

    val filled = PagesViews.update({ PagesViews.app.isNull() }) {
        it[app] = wrapAsExpression(Pages.select(Pages.app).where { Pages.id eq PagesViews.page })
    }
    if (filled > 0) log.info("Linked $filled page views to their application")
}
