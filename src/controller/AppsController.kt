package com.ass.controller

import com.ass.auth.currentUser
import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.db.Db
import com.ass.ingest.PageCache
import com.ass.model.AppOut
import com.ass.model.AppPagesOut
import com.ass.model.AppsOut
import com.ass.model.BatchPagesOut
import com.ass.model.NameIn
import com.ass.model.NamesIn
import com.ass.model.PageOut
import com.ass.model.User
import com.ass.stats.MAX_PERIOD_OFFSET
import com.ass.stats.MAX_TZ_OFFSET_MS
import com.ass.stats.Period
import com.ass.stats.StatsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

const val MAX_NAME_LENGTH = 255
const val MAX_PAGES_PER_BATCH = 1000

/**
 * Applications and their pages, all scoped to the logged-in user:
 *
 * GET    /apps                          -> {"apps":[{id,name,pages}]}
 * POST   /apps            {"name"}      -> 201 {id,name,pages}
 * GET    /apps/{app}                    -> {"app":{...},"pages":[{id,name}]}
 * DELETE /apps/{app}                    -> 204 (its pages and views too)
 * POST   /apps/{app}/pages {"name"}     -> 201 {id,name}
 * POST   /apps/{app}/pages/batch {"names":[...]} -> 200 {"created":[{id,name}],"existing":[{id,name}]}
 * DELETE /apps/{app}/pages/{page}       -> 204 (its views too)
 * GET    /apps/{app}/stats?period=day|week|month|year&offset=0&tz=<minutes> -> stats of a period
 *
 * Another user's application answers 404, exactly like a missing one.
 */
fun Route.apps(db: Db, pageCache: PageCache, statsService: StatsService) {
    route("/apps") {
        get {
            val user = requireUser(db) ?: return@get
            val apps = db.query {
                val rows = Apps.selectAll().where { Apps.owner eq user.id }.orderBy(Apps.name).toList()
                val ids = rows.map { it[Apps.id] }
                val count = Pages.id.count()
                val pageCounts = if (ids.isEmpty()) emptyMap() else
                    Pages.select(Pages.app, count).where { Pages.app inList ids }.groupBy(Pages.app)
                        .associate { it[Pages.app]!!.value to it[count] }
                rows.map { AppOut(it[Apps.id].value, it[Apps.name], pageCounts[it[Apps.id].value] ?: 0) }
            }
            call.respond(AppsOut(apps))
        }

        post {
            val user = requireUser(db) ?: return@post
            val name = readName() ?: return@post
            val id = db.query {
                Apps.insertAndGetId {
                    it[Apps.name] = name
                    it[owner] = user.id
                }.value
            }
            call.respond(HttpStatusCode.Created, AppOut(id, name))
        }

        route("/{app}") {
            get {
                val user = requireUser(db) ?: return@get
                val app = ownedApp(db, user) ?: return@get
                val pages = db.query {
                    Pages.selectAll().where { Pages.app eq app.id }.orderBy(Pages.name)
                        .map { PageOut(it[Pages.id].value, it[Pages.name]) }
                }
                call.respond(AppPagesOut(app.copy(pages = pages.size.toLong()), pages))
            }

            delete {
                val user = requireUser(db) ?: return@delete
                val app = ownedApp(db, user) ?: return@delete
                db.query {
                    PagesViews.deleteWhere { PagesViews.app eq app.id }
                    Pages.deleteWhere { Pages.app eq app.id }
                    Apps.deleteWhere { Apps.id eq app.id }
                }
                pageCache.removedApp(app.id)
                statsService.invalidate(app.id)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/stats") {
                val user = requireUser(db) ?: return@get
                val app = ownedApp(db, user) ?: return@get
                val query = call.request.queryParameters
                val tzMs = (query["tz"]?.toLongOrNull() ?: 0) * 60_000
                val period = Period.parse(query["period"])
                val offset = query["offset"]?.toIntOrNull() ?: 0
                if (tzMs !in -MAX_TZ_OFFSET_MS..MAX_TZ_OFFSET_MS || period == null || offset !in -MAX_PERIOD_OFFSET..0) {
                    return@get call.respond(HttpStatusCode.BadRequest, "invalid tz, period (day|week|month|year) or offset (<= 0)")
                }
                call.respond(statsService.compute(app.id, period, offset, tzMs))
            }

            post("/pages") {
                val user = requireUser(db) ?: return@post
                val app = ownedApp(db, user) ?: return@post
                val name = readName() ?: return@post
                val id = db.query {
                    Pages.insertAndGetId {
                        it[Pages.name] = name
                        it[Pages.app] = app.id
                    }.value
                }
                pageCache.added(id, app.id)
                statsService.invalidate(app.id)
                call.respond(HttpStatusCode.Created, PageOut(id, name))
            }

            // Creates the pages whose name isn't in the application yet, so importing the same list twice is harmless
            post("/pages/batch") {
                val user = requireUser(db) ?: return@post
                val app = ownedApp(db, user) ?: return@post
                val names = call.receiveNullable<NamesIn>()?.names?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                if (names.isNullOrEmpty() || names.size > MAX_PAGES_PER_BATCH || names.any { it.length > MAX_NAME_LENGTH }) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        "names: 1 to $MAX_PAGES_PER_BATCH names of at most $MAX_NAME_LENGTH characters")
                }
                val result = db.query {
                    val existing = Pages.selectAll().where { (Pages.app eq app.id) and (Pages.name inList names) }
                        .map { PageOut(it[Pages.id].value, it[Pages.name]) }
                    val known = existing.map { it.name }.toSet()
                    val created = names.filter { it !in known }.map { PageOut(UUID.randomUUID(), it) }
                    Pages.batchInsert(created, shouldReturnGeneratedValues = false) { page ->
                        this[Pages.id] = page.id
                        this[Pages.name] = page.name
                        this[Pages.app] = app.id
                    }
                    BatchPagesOut(created, existing.sortedBy { names.indexOf(it.name) })
                }
                result.created.forEach { pageCache.added(it.id, app.id) }
                if (result.created.isNotEmpty()) statsService.invalidate(app.id)
                call.respond(result)
            }

            delete("/pages/{page}") {
                val user = requireUser(db) ?: return@delete
                val app = ownedApp(db, user) ?: return@delete
                val pageId = call.parameters["page"]?.toUuidOrNull()
                    ?: return@delete call.respond(HttpStatusCode.NotFound, "page not found")
                val deleted = db.query {
                    val exists = Pages.selectAll().where { (Pages.id eq pageId) and (Pages.app eq app.id) }.any()
                    if (exists) {
                        PagesViews.deleteWhere { PagesViews.page eq pageId }
                        Pages.deleteWhere { Pages.id eq pageId }
                    }
                    exists
                }
                if (!deleted) return@delete call.respond(HttpStatusCode.NotFound, "page not found")
                pageCache.removed(pageId)
                statsService.invalidate(app.id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun String.toUuidOrNull(): UUID? = try { UUID.fromString(this) } catch (e: IllegalArgumentException) { null }

private suspend fun RoutingContext.requireUser(db: Db): User? =
    call.currentUser(db) ?: run {
        call.respond(HttpStatusCode.Unauthorized, "not logged")
        null
    }

/** The {app} of the path if it belongs to [user]; otherwise answers 404 and returns null. */
private suspend fun RoutingContext.ownedApp(db: Db, user: User): AppOut? {
    val id = call.parameters["app"]?.toUuidOrNull()
    val app = id?.let {
        db.query {
            Apps.selectAll().where { (Apps.id eq it) and (Apps.owner eq user.id) }
                .firstOrNull()?.let { row -> AppOut(row[Apps.id].value, row[Apps.name]) }
        }
    }
    if (app == null) call.respond(HttpStatusCode.NotFound, "application not found")
    return app
}

/** The trimmed "name" of the JSON body; answers 400 and returns null when missing or too long. */
private suspend fun RoutingContext.readName(): String? {
    val name = call.receiveNullable<NameIn>()?.name?.trim()
    if (name.isNullOrEmpty() || name.length > MAX_NAME_LENGTH) {
        call.respond(HttpStatusCode.BadRequest, "name is required (max $MAX_NAME_LENGTH characters)")
        return null
    }
    return name
}
