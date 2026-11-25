package com.ass

import com.ass.model.AppsOut
import com.ass.model.MembersOut
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppMembersTest {

    private suspend fun ApplicationTestBuilder.share(owner: String, app: String, email: String, role: String) =
        call(HttpMethod.Post, "/apps/$app/members", owner, json = """{"email":"$email","role":"$role"}""")

    private suspend fun ApplicationTestBuilder.members(cookie: String, app: String) =
        Json.decodeFromString<MembersOut>(call(HttpMethod.Get, "/apps/$app/members", cookie).bodyAsText()).members

    private suspend fun ApplicationTestBuilder.apps(cookie: String) =
        Json.decodeFromString<AppsOut>(call(HttpMethod.Get, "/apps", cookie).bodyAsText()).apps

    @Test
    fun ownerSharesListsAndRemoves() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val app = createApp(alice, "Shop")

        assertEquals(HttpStatusCode.OK, share(alice, app, " Bob@X.io ", "read").status)
        val member = members(alice, app).single()
        assertEquals("bob@x.io" to "read", member.email to member.role)

        // Sharing again changes the role, never duplicates
        share(alice, app, "bob@x.io", "manage")
        assertEquals(listOf("manage"), members(alice, app).map { it.role })

        val shared = apps(bob).single()
        assertEquals("Shop" to "manage", shared.name to shared.role)
        assertEquals("alice@x.io", shared.owner)
        val own = apps(alice).single()
        assertEquals("owner", own.role)
        assertNull(own.owner)

        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$app/members/${member.userId}", alice).status)
        assertEquals(0, apps(bob).size)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app", bob).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/apps/$app/members/${member.userId}", alice).status)
    }

    @Test
    fun invalidShares() = withServer {
        val alice = registerAndLogin("alice@x.io")
        registerAndLogin("bob@x.io")
        val app = createApp(alice)
        assertEquals(HttpStatusCode.NotFound, share(alice, app, "nobody@x.io", "read").status)
        assertEquals(HttpStatusCode.BadRequest, share(alice, app, "bob@x.io", "owner").status)
        assertEquals(HttpStatusCode.BadRequest, share(alice, app, "", "read").status)
        assertEquals(HttpStatusCode.Conflict, share(alice, app, "alice@x.io", "read").status)
        assertEquals(0, members(alice, app).size)
    }

    @Test
    fun unverifiedAccountsCantBeSharedWith() = withServer {
        val alice = registerAndLogin("alice@x.io")
        registerAndLogin("bob@x.io")
        val app = createApp(alice)
        database().query {
            com.ass.dao.Users.update({ com.ass.dao.Users.email eq "bob@x.io" }) { it[emailVerified] = false }
        }
        assertEquals(HttpStatusCode.NotFound, share(alice, app, "bob@x.io", "read").status)
    }

    @Test
    fun readerOnlyReads() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val (app, page) = appWithPage(alice)
        share(alice, app, "bob@x.io", "read")

        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/apps/$app", bob).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/apps/$app/stats", bob).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/apps/$app/pages", bob, json = """{"name":"x"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/apps/$app/pages/batch", bob, json = """{"names":["x"]}""").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/apps/$app/pages/$page", bob).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/apps/$app/views?from=0&to=1", bob).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/apps/$app", bob).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/apps/$app/members", bob).status)
        assertEquals(HttpStatusCode.Forbidden, share(bob, app, "alice@x.io", "read").status)
    }

    @Test
    fun managerHandlesPagesButNotTheApp() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val carol = registerAndLogin("carol@x.io")
        val (app, page) = appWithPage(alice)
        share(alice, app, "bob@x.io", "manage")

        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/apps/$app/pages", bob, json = """{"name":"cart"}""").status)
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$app/pages/$page", bob).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/apps/$app/views?from=0&to=1", bob).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/apps/$app", bob).status)
        assertEquals(HttpStatusCode.Forbidden, share(bob, app, "carol@x.io", "read").status)
        // A third account still gets 404 everywhere
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app", carol).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app/members", carol).status)
    }

    @Test
    fun memberCanLeaveButNotRemoveOthers() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val carolCookie = registerAndLogin("carol@x.io")
        val app = createApp(alice)
        share(alice, app, "bob@x.io", "read")
        share(alice, app, "carol@x.io", "read")
        val ids = members(alice, app).associate { it.email to it.userId }

        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/apps/$app/members/${ids["carol@x.io"]}", bob).status)
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$app/members/${ids["bob@x.io"]}", bob).status)
        assertEquals(listOf("carol@x.io"), members(alice, app).map { it.email })
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$app/members/me", carolCookie).status)
        assertEquals(0, members(alice, app).size)
        // The owner isn't a member: nothing to leave
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/apps/$app/members/me", alice).status)
    }

    @Test
    fun sharedAppsCountForTheOwnerQuota() = withServer("ktor.quotas.max_apps" to "1") {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val app = createApp(alice)
        share(alice, app, "bob@x.io", "manage")
        // Bob's own quota is untouched by the shared application
        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/apps", bob, json = """{"name":"Mine"}""").status)
        assertEquals(2, apps(bob).size)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/apps", alice, json = """{"name":"Two"}""").status)
    }

    @Test
    fun sharesDisappearWithTheAccountOrTheApp() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val kept = createApp(alice, "Kept")
        val dropped = createApp(alice, "Dropped")
        share(alice, kept, "bob@x.io", "read")
        share(alice, dropped, "bob@x.io", "read")
        val bobsApp = createApp(bob, "Bob's")
        share(bob, bobsApp, "alice@x.io", "read")

        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$dropped", alice).status)
        assertEquals(listOf("Bob's", "Kept"), apps(bob).map { it.name })

        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/account", bob, json = """{"password":"secret123"}""").status)
        assertEquals(0, members(alice, kept).size)
        assertEquals(listOf("Kept"), apps(alice).map { it.name })
    }
}
