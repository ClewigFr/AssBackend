package com.ass.db

import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
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

    // Only views whose page has an application (orphan pages' views would be rewritten on every start)
    val pagesWithApp = Pages.select(Pages.id).where { Pages.app.isNotNull() }
    val filled = PagesViews.update({ PagesViews.app.isNull() and (PagesViews.page inSubQuery pagesWithApp) }) {
        it[app] = wrapAsExpression(Pages.select(Pages.app).where { Pages.id eq PagesViews.page })
    }
    if (filled > 0) log.info("Linked $filled page views to their application")
}

/**
 * Databases created before view ids were 64-bit have an INTEGER id (and sequence), which
 * overflows at 2.1 billion views. Exposed's automatic migration bundles this change with a
 * DROP DEFAULT, which [com.ass.databaseInitialization] filters out, so it's done explicitly.
 * PostgreSQL only; runs once (rewrites the table, so it can take a while on a large one).
 */
fun JdbcTransaction.widenViewIds() {
    if (db.dialect !is PostgreSQLDialect) return
    val log = LoggerFactory.getLogger("DataMigrations")
    val table = PagesViews.nameInDatabaseCase()
    val type = exec(
        "SELECT data_type FROM information_schema.columns " +
            "WHERE table_schema = current_schema() AND table_name = '$table' AND column_name = 'id'"
    ) { rs -> if (rs.next()) rs.getString(1) else null }
    if (type != "integer") return

    log.info("Widening $table.id to BIGINT (one-time table rewrite)")
    exec("ALTER TABLE ${identity(PagesViews)} ALTER COLUMN id TYPE BIGINT")
    val sequence = exec("SELECT pg_get_serial_sequence('$table', 'id')") { rs -> if (rs.next()) rs.getString(1) else null }
    if (sequence != null) exec("ALTER SEQUENCE $sequence AS BIGINT")
}
