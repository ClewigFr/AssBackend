package com.ass.account

import com.ass.ASSSession
import com.ass.auth.currentUser
import com.ass.auth.startSession
import com.ass.controller.BCRYPT_MAX_BYTES
import com.ass.controller.MIN_PASSWORD_LENGTH
import com.ass.controller.limitedTo
import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesDaily
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.dao.checkPassword
import com.ass.dao.fetchUserById
import com.ass.dao.hashPassword
import com.ass.db.Db
import com.ass.ingest.PageCache
import com.ass.model.AccountOut
import com.ass.model.AccountSettingsIn
import com.ass.model.PasswordChangeIn
import com.ass.model.PasswordIn
import com.ass.model.QuotasOut
import com.ass.model.UsageOut
import com.ass.model.User
import com.ass.stats.StatsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The logged-in user's account:
 *
 * GET    /account                             -> {email, displayName, quotas, usage, monthlyReport}
 * PATCH  /account          {monthlyReport}    -> 204: opt in or out of the monthly summary email
 * POST   /account/password {current, new}     -> 204; signs out every other session
 * DELETE /account          {password}         -> 204; deletes the applications, pages and views too
 *
 * A wrong password answers 403 (not 401, which means "not logged").
 */
fun Route.account(db: Db, quotas: Quotas, pageCache: PageCache, statsService: StatsService, rateLimited: Boolean) {
    route("/account") {
        get {
            val user = call.currentUser(db) ?: return@get call.respond(HttpStatusCode.Unauthorized, "not logged")
            val from = monthStart()
            val usage = db.query {
                UsageOut(
                    apps = Apps.select(Apps.id).where { Apps.owner eq user.id }.count(),
                    monthViews = viewsSince(user.id, from),
                    monthStart = from
                )
            }
            call.respond(AccountOut(user.email, user.displayName,
                QuotasOut(quotas.maxApps, quotas.maxPagesPerApp, quotas.maxMonthlyViews), usage, user.monthlyReport))
        }

        // Settings of the account emails: {"monthlyReport": true|false}
        patch {
            val user = call.currentUser(db) ?: return@patch call.respond(HttpStatusCode.Unauthorized, "not logged")
            val monthly = call.receiveNullable<AccountSettingsIn>()?.monthlyReport
                ?: return@patch call.respond(HttpStatusCode.BadRequest, "monthlyReport is required")
            db.query { Users.update({ Users.id eq user.id }) { it[monthlyReport] = monthly } }
            call.respond(HttpStatusCode.NoContent)
        }

        // Each costs a bcrypt check, like /login
        limitedTo(rateLimited) {
            post("/password") {
                val user = call.currentUser(db) ?: return@post call.respond(HttpStatusCode.Unauthorized, "not logged")
                val body = call.receiveNullable<PasswordChangeIn>()
                val new = body?.new ?: ""
                when {
                    new.length < MIN_PASSWORD_LENGTH ->
                        return@post call.respond(HttpStatusCode.BadRequest, "Password should be at least $MIN_PASSWORD_LENGTH characters long")
                    new.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_BYTES ->
                        return@post call.respond(HttpStatusCode.BadRequest, "Password should be at most $BCRYPT_MAX_BYTES bytes long")
                    !passwordMatches(user, body?.current) ->
                        return@post call.respond(HttpStatusCode.Forbidden, "wrong password")
                }
                val hash = withContext(Dispatchers.Default) { hashPassword(new) }
                db.query {
                    Users.update({ Users.id eq user.id }) {
                        it[passwordHash] = hash
                        it[sessionVersion] = sessionVersion + 1
                    }
                }
                // Other devices are signed out; this one gets a session with the new version
                fetchUserById(db, user.id.value)?.let { call.startSession(it) }
                call.respond(HttpStatusCode.NoContent)
            }

            delete {
                val user = call.currentUser(db) ?: return@delete call.respond(HttpStatusCode.Unauthorized, "not logged")
                if (!passwordMatches(user, call.receiveNullable<PasswordIn>()?.password)) {
                    return@delete call.respond(HttpStatusCode.Forbidden, "wrong password")
                }
                val apps = db.query {
                    val ids = Apps.select(Apps.id).where { Apps.owner eq user.id }.map { it[Apps.id] }
                    if (ids.isNotEmpty()) {
                        PagesViews.deleteWhere { PagesViews.app inList ids }
                        PagesDaily.deleteWhere { PagesDaily.app inList ids }
                        Pages.deleteWhere { Pages.app inList ids }
                        Apps.deleteWhere { Apps.id inList ids }
                    }
                    // Legacy column without cascade
                    Pages.update({ Pages.owner eq user.id }) { it[owner] = null }
                    Users.deleteWhere { Users.id eq user.id }
                    ids.map { it.value }
                }
                apps.forEach { pageCache.removedApp(it); statsService.invalidate(it) }
                call.sessions.clear<ASSSession>()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend fun passwordMatches(user: User, password: String?): Boolean =
    !password.isNullOrEmpty() && password.length <= 256 &&
        withContext(Dispatchers.Default) { checkPassword(password, user.passwordHash) }
