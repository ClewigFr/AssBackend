package com.ass

import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.db.DEFAULT_APP_NAME
import com.ass.db.migrateToApplications
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DataMigrationTest {

    @Test
    fun legacyPagesAndViewsMoveToADefaultAppPerOwner() {
        val db = Database.connect("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "org.h2.Driver")
        transaction(db) {
            SchemaUtils.create(Users, Apps, Pages, PagesViews)
            fun user(email: String) = Users.insertAndGetId {
                it[Users.email] = email; it[displayName] = ""; it[passwordHash] = "x"
            }
            val alice = user("alice@x.io")
            val bob = user("bob@x.io")
            // State before applications existed: pages with an owner and no app, views with no app
            fun legacyPage(owner: EntityID<Int>?) = Pages.insertAndGetId { it[name] = "p"; it[Pages.owner] = owner }
            val a1 = legacyPage(alice)
            legacyPage(alice)
            val b1 = legacyPage(bob)
            val orphan = legacyPage(null)
            listOf(a1, b1).forEach { page ->
                PagesViews.insert {
                    it[PagesViews.page] = page; it[sessionId] = UUID.randomUUID(); it[timestamp] = 1_000_000
                }
            }

            migrateToApplications()
            migrateToApplications() // idempotent

            val apps = Apps.selectAll().toList()
            assertEquals(2, apps.size)
            assertEquals(setOf(DEFAULT_APP_NAME), apps.map { it[Apps.name] }.toSet())
            val appOf = Pages.selectAll().associate { it[Pages.id] to it[Pages.app] }
            val aliceApp = apps.single { it[Apps.owner] == alice }[Apps.id]
            assertEquals(2, appOf.values.count { it == aliceApp })
            assertEquals(apps.single { it[Apps.owner] == bob }[Apps.id], appOf[b1])
            assertEquals(null, appOf[orphan])
            // Views carry their page's application
            PagesViews.selectAll().forEach { assertEquals(appOf[it[PagesViews.page]], it[PagesViews.app]) }
        }
    }
}
