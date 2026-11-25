package com.ass.controller

import com.ass.account.Quotas
import com.ass.auth.AppRole
import com.ass.auth.currentUser
import com.ass.auth.roleOn
import com.ass.dao.AppMembers
import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesDaily
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.dao.fetchUser
import com.ass.db.Db
import com.ass.ingest.PageCache
import com.ass.model.AppOut
import com.ass.model.AppPagesOut
import com.ass.model.AppsOut
import com.ass.model.BatchPagesOut
import com.ass.model.DeletedOut
import com.ass.model.MemberIn
import com.ass.model.MemberOut
import com.ass.model.MembersOut
import com.ass.model.NameIn
import com.ass.model.NamesIn
import com.ass.model.PageOut
import com.ass.model.User
import com.ass.stats.DAY_MS
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
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
 * DELETE /apps/{app}/views?from=<ms>&to=<ms> -> {"deleted":n}: views received in [from, to), e.g. after a flood
 * GET    /apps/{app}/stats?period=day|week|month|year&offset=0&tz=<minutes>[&page=<id>] -> stats of a period, of one page with page
 *
 * GET    /apps/{app}/members            -> {"members":[{userId,email,role}]} (owner only)
 * POST   /apps/{app}/members {"email","role":"read"|"manage"} -> {"members":[...]} (owner only; 404 if no account)
 * DELETE /apps/{app}/members/{userId}   -> 204 (owner, or the member leaving, also as /members/me)
 *
 * An application can be shared ([AppRole]): "read" sees pages and stats, "manage" also handles
 * pages and deletes views; deleting the application and sharing stay with the owner, and
 * quotas keep counting the owner's applications only. GET /apps lists shared applications too,
 * with the caller's role and the owner's email. An application without any access answers
 * 404, exactly like a missing one; one with too low a role answers 403. Going over [Quotas]
 * (applications per account, pages per application) answers 403 and creates nothing.
 */
fun Route.apps(db: Db, pageCache: PageCache, statsService: StatsService, quotas: Quotas = Quotas()) {
    route("/apps") {
        get {
            val user = requireUser(db) ?: return@get
            val apps = db.query {
                val owned = Apps.selectAll().where { Apps.owner eq user.id }
                    .map { AppOut(it[Apps.id].value, it[Apps.name]) }
                val shared = AppMembers.join(Apps, JoinType.INNER, AppMembers.app, Apps.id)
                    .join(Users, JoinType.INNER, Apps.owner, Users.id)
                    .select(Apps.id, Apps.name, AppMembers.role, Users.email)
                    .where { AppMembers.user eq user.id }
                    .map { AppOut(it[Apps.id].value, it[Apps.name], role = it[AppMembers.role], owner = it[Users.email]) }
                val all = (owned + shared).sortedWith(compareBy({ it.name }, { it.id }))
                val ids = all.map { it.id }
                val count = Pages.id.count()
                val pageCounts = if (ids.isEmpty()) emptyMap() else
                    Pages.select(Pages.app, count).where { Pages.app inList ids }.groupBy(Pages.app)
                        .associate { it[Pages.app]!!.value to it[count] }
                all.map { it.copy(pages = pageCounts[it.id] ?: 0) }
            }
            call.respond(AppsOut(apps))
        }

        post {
            val user = requireUser(db) ?: return@post
            val name = readName() ?: return@post
            val id = db.query {
                if (quotas.appsFull(Apps.select(Apps.id).where { Apps.owner eq user.id }.count())) null
                else Apps.insertAndGetId {
                    it[Apps.name] = name
                    it[owner] = user.id
                }.value
            } ?: return@post call.respond(HttpStatusCode.Forbidden, "quota: at most ${quotas.maxApps} applications per account")
            call.respond(HttpStatusCode.Created, AppOut(id, name))
        }

        route("/{app}") {
            get {
                val user = requireUser(db) ?: return@get
                val app = appWithRole(db, user, AppRole.READ) ?: return@get
                val pages = db.query {
                    Pages.selectAll().where { Pages.app eq app.id }.orderBy(Pages.name)
                        .map { PageOut(it[Pages.id].value, it[Pages.name]) }
                }
                call.respond(AppPagesOut(app.copy(pages = pages.size.toLong()), pages))
            }

            delete {
                val user = requireUser(db) ?: return@delete
                val app = appWithRole(db, user, AppRole.OWNER) ?: return@delete
                db.query {
                    PagesViews.deleteWhere { PagesViews.app eq app.id }
                    PagesDaily.deleteWhere { PagesDaily.app eq app.id }
                    Pages.deleteWhere { Pages.app eq app.id }
                    AppMembers.deleteWhere { AppMembers.app eq app.id }
                    Apps.deleteWhere { Apps.id eq app.id }
                }
                pageCache.removedApp(app.id)
                statsService.invalidate(app.id)
                call.respond(HttpStatusCode.NoContent)
            }

            // Clean up after a flood: raw views in [from, to), and daily counts of days entirely inside it
            delete("/views") {
                val user = requireUser(db) ?: return@delete
                val app = appWithRole(db, user, AppRole.MANAGE) ?: return@delete
                val from = call.request.queryParameters["from"]?.toLongOrNull()
                val to = call.request.queryParameters["to"]?.toLongOrNull()
                if (from == null || to == null || from < 0 || to <= from) {
                    return@delete call.respond(HttpStatusCode.BadRequest, "from and to (epoch ms, from < to) are required")
                }
                val deleted = db.query {
                    val raw = PagesViews.deleteWhere {
                        (PagesViews.app eq app.id) and (PagesViews.timestamp greaterEq from) and (PagesViews.timestamp less to)
                    }
                    val days = PagesDaily.select(PagesDaily.views.sum()).where {
                        (PagesDaily.app eq app.id) and (PagesDaily.day greaterEq from) and (PagesDaily.day lessEq to - DAY_MS)
                    }.single()[PagesDaily.views.sum()] ?: 0
                    PagesDaily.deleteWhere {
                        (PagesDaily.app eq app.id) and (PagesDaily.day greaterEq from) and (PagesDaily.day lessEq to - DAY_MS)
                    }
                    raw + days
                }
                statsService.invalidate(app.id)
                call.respond(DeletedOut(deleted.toLong()))
            }

            get("/stats") {
                val user = requireUser(db) ?: return@get
                val app = appWithRole(db, user, AppRole.READ) ?: return@get
                val query = call.request.queryParameters
                val tzMs = (query["tz"]?.toLongOrNull() ?: 0) * 60_000
                val period = Period.parse(query["period"])
                val offset = query["offset"]?.toIntOrNull() ?: 0
                if (tzMs !in -MAX_TZ_OFFSET_MS..MAX_TZ_OFFSET_MS || period == null || offset !in -MAX_PERIOD_OFFSET..0) {
                    return@get call.respond(HttpStatusCode.BadRequest, "invalid tz, period (day|week|month|year) or offset (<= 0)")
                }
                // ?page=<id>: one page of the application only
                val page = query["page"]?.let { raw ->
                    val id = raw.toUuidOrNull()
                    val known = id != null && db.query { Pages.selectAll().where { (Pages.id eq id) and (Pages.app eq app.id) }.any() }
                    if (!known) return@get call.respond(HttpStatusCode.NotFound, "page not found")
                    id
                }
                call.respond(statsService.compute(app.id, period, offset, tzMs, page))
            }

            post("/pages") {
                val user = requireUser(db) ?: return@post
                val app = appWithRole(db, user, AppRole.MANAGE) ?: return@post
                val name = readName() ?: return@post
                val id = db.query {
                    if (quotas.pagesLeft(pageCount(app.id)) == 0L) null
                    else Pages.insertAndGetId {
                        it[Pages.name] = name
                        it[Pages.app] = app.id
                    }.value
                } ?: return@post call.respond(HttpStatusCode.Forbidden, "quota: at most ${quotas.maxPagesPerApp} pages per application")
                pageCache.added(id, app.id)
                statsService.invalidate(app.id)
                call.respond(HttpStatusCode.Created, PageOut(id, name))
            }

            // Creates the pages whose name isn't in the application yet, so importing the same list twice is harmless
            post("/pages/batch") {
                val user = requireUser(db) ?: return@post
                val app = appWithRole(db, user, AppRole.MANAGE) ?: return@post
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
                    // All or nothing: a partial import would be harder to understand than a refusal
                    if (created.size > quotas.pagesLeft(pageCount(app.id))) return@query null
                    Pages.batchInsert(created, shouldReturnGeneratedValues = false) { page ->
                        this[Pages.id] = page.id
                        this[Pages.name] = page.name
                        this[Pages.app] = app.id
                    }
                    BatchPagesOut(created, existing.sortedBy { names.indexOf(it.name) })
                } ?: return@post call.respond(HttpStatusCode.Forbidden, "quota: at most ${quotas.maxPagesPerApp} pages per application")
                result.created.forEach { pageCache.added(it.id, app.id) }
                if (result.created.isNotEmpty()) statsService.invalidate(app.id)
                call.respond(result)
            }

            delete("/pages/{page}") {
                val user = requireUser(db) ?: return@delete
                val app = appWithRole(db, user, AppRole.MANAGE) ?: return@delete
                val pageId = call.parameters["page"]?.toUuidOrNull()
                    ?: return@delete call.respond(HttpStatusCode.NotFound, "page not found")
                val deleted = db.query {
                    val exists = Pages.selectAll().where { (Pages.id eq pageId) and (Pages.app eq app.id) }.any()
                    if (exists) {
                        PagesViews.deleteWhere { PagesViews.page eq pageId }
                        PagesDaily.deleteWhere { PagesDaily.page eq pageId }
                        Pages.deleteWhere { Pages.id eq pageId }
                    }
                    exists
                }
                if (!deleted) return@delete call.respond(HttpStatusCode.NotFound, "page not found")
                pageCache.removed(pageId)
                statsService.invalidate(app.id)
                call.respond(HttpStatusCode.NoContent)
            }

            route("/members") {
                get {
                    val user = requireUser(db) ?: return@get
                    val app = appWithRole(db, user, AppRole.OWNER) ?: return@get
                    call.respond(MembersOut(db.query { members(app.id) }))
                }

                // Shares with an existing account, or changes its role when already a member
                post {
                    val user = requireUser(db) ?: return@post
                    val app = appWithRole(db, user, AppRole.OWNER) ?: return@post
                    val body = call.receiveNullable<MemberIn>()
                    val role = AppRole.memberRole(body?.role)
                    val email = body?.email?.trim()
                    if (email.isNullOrEmpty() || role == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, "email and role (read|manage) are required")
                    }
                    val member = fetchUser(db, email)
                        ?: return@post call.respond(HttpStatusCode.NotFound, "no account with this email")
                    if (member.id == user.id) {
                        return@post call.respond(HttpStatusCode.Conflict, "the owner already has every right")
                    }
                    val out = db.query {
                        val updated = AppMembers.update({ (AppMembers.app eq app.id) and (AppMembers.user eq member.id) }) {
                            it[AppMembers.role] = role.wire
                        }
                        if (updated == 0) AppMembers.insert {
                            it[AppMembers.app] = app.id
                            it[AppMembers.user] = member.id
                            it[AppMembers.role] = role.wire
                        }
                        members(app.id)
                    }
                    call.respond(MembersOut(out))
                }

                // The owner removes anyone; a member can also leave by removing themself ("me" or their id)
                delete("/{userId}") {
                    val user = requireUser(db) ?: return@delete
                    val raw = call.parameters["userId"]
                    val target = if (raw == "me") user.id.value else raw?.toIntOrNull()
                    val min = if (target == user.id.value) AppRole.READ else AppRole.OWNER
                    val app = appWithRole(db, user, min) ?: return@delete
                    val removed = target != null && db.query {
                        AppMembers.deleteWhere { (AppMembers.app eq app.id) and (AppMembers.user eq target) } > 0
                    }
                    if (!removed) return@delete call.respond(HttpStatusCode.NotFound, "member not found")
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun JdbcTransaction.members(app: UUID): List<MemberOut> =
    AppMembers.join(Users, JoinType.INNER, AppMembers.user, Users.id).select(Users.id, Users.email, AppMembers.role)
        .where { AppMembers.app eq app }.orderBy(Users.email)
        .map { MemberOut(it[Users.id].value, it[Users.email], it[AppMembers.role]) }

private fun pageCount(app: UUID) = Pages.select(Pages.id).where { Pages.app eq app }.count()

private fun String.toUuidOrNull(): UUID? = try { UUID.fromString(this) } catch (e: IllegalArgumentException) { null }

private suspend fun RoutingContext.requireUser(db: Db): User? =
    call.currentUser(db) ?: run {
        call.respond(HttpStatusCode.Unauthorized, "not logged")
        null
    }

/**
 * The {app} of the path if [user] has at least role [min] on it. Without any access it answers
 * 404 (as if it didn't exist), with a lower role 403; both return null.
 */
private suspend fun RoutingContext.appWithRole(db: Db, user: User, min: AppRole): AppOut? {
    val id = call.parameters["app"]?.toUuidOrNull()
    val found = id?.let {
        db.query {
            val role = roleOn(it, user) ?: return@query null
            val name = Apps.select(Apps.name).where { Apps.id eq it }.single()[Apps.name]
            AppOut(it, name, role = role.wire) to role
        }
    }
    if (found == null) {
        call.respond(HttpStatusCode.NotFound, "application not found")
        return null
    }
    if (found.second < min) {
        call.respond(HttpStatusCode.Forbidden, "role ${min.wire} required")
        return null
    }
    return found.first
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
