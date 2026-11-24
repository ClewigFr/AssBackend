package com.ass

import com.ass.account.monthStart
import com.ass.model.AccountOut
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AccountTest {

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.account(cookie: String) =
        Json.decodeFromString<AccountOut>(call(HttpMethod.Get, "/account", cookie).bodyAsText())

    @Test
    fun showsQuotasAndUsage() = withServer {
        val alice = registerAndLogin("a@x.io")
        val (app, page) = appWithPage(alice)
        createApp(alice, "Second")
        call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${viewJson(page)},${viewJson(page)}]}""")
        awaitIngested()

        val account = account(alice)
        assertEquals("a@x.io", account.email)
        assertEquals(5, account.quotas.maxApps)
        assertEquals(30, account.quotas.maxPagesPerApp)
        assertEquals(100_000, account.quotas.maxMonthlyViews)
        assertEquals(2, account.usage.apps)
        assertEquals(2, account.usage.monthViews)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/account").status)
    }

    @Test
    fun publishesTheLimitsWithoutLogin() = withServer("ktor.quotas.max_apps" to "7", "ktor.performance.retention_days" to "30") {
        val limits = Json.decodeFromString<com.ass.model.LimitsOut>(call(HttpMethod.Get, "/limits").bodyAsText())
        assertEquals(com.ass.model.LimitsOut(7, 30, 100_000, 30), limits)
    }

    @Test
    fun monthStartIsTheFirstDayInUtc() {
        assertEquals(1_793_491_200_000, monthStart(1_794_000_000_000)) // 2026-11-01, from 2026-11-06
    }

    @Test
    fun limitsApplicationsAndPages() = withServer("ktor.quotas.max_apps" to "2", "ktor.quotas.max_pages_per_app" to "3") {
        val alice = registerAndLogin("a@x.io")
        val app = createApp(alice)
        createApp(alice)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/apps", alice, json = """{"name":"third"}""").status)

        createPage(alice, app, "one")
        // The batch is refused as a whole when it doesn't fit, but names already there don't count
        assertEquals(HttpStatusCode.Forbidden,
            call(HttpMethod.Post, "/apps/$app/pages/batch", alice, json = """{"names":["one","two","three","four"]}""").status)
        assertEquals(HttpStatusCode.OK,
            call(HttpMethod.Post, "/apps/$app/pages/batch", alice, json = """{"names":["one","two","three"]}""").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/apps/$app/pages", alice, json = """{"name":"four"}""").status)

        // Another user has their own quota
        val bob = registerAndLogin("b@x.io")
        createApp(bob)
        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/apps", bob, json = """{"name":"b2"}""").status)
    }

    @Test
    fun monthlyViewsAreASoftLimit() = withServer("ktor.quotas.max_monthly_views" to "1") {
        val alice = registerAndLogin("a@x.io")
        val (app, page) = appWithPage(alice)
        val body = """{"app":"$app","pageViews":[${viewJson(page)},${viewJson(page)},${viewJson(page)}]}"""
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = body).status)
        awaitIngested()
        assertEquals(3, account(alice).usage.monthViews)
    }

    @Test
    fun changesThePasswordAndSignsOutOtherSessions() = withServer {
        val phone = registerAndLogin("a@x.io", "secret123")
        val laptop = sessionCookie(postForm("/login", "userId" to "a@x.io", "password" to "secret123"))!!
        val path = "/account/password"
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, path, laptop, json = """{"current":"wrong-one","new":"newsecret1"}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, path, laptop, json = """{"current":"secret123","new":"short"}""").status)

        val changed = call(HttpMethod.Post, path, laptop, json = """{"current":"secret123","new":"newsecret1"}""")
        assertEquals(HttpStatusCode.NoContent, changed.status)
        val renewed = assertNotNull(sessionCookie(changed))
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/account", renewed).status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/account", phone).status)

        assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
        assertEquals(HttpStatusCode.OK, postForm("/login", "userId" to "a@x.io", "password" to "newsecret1").status)
    }

    @Test
    fun deletesTheAccountAndItsData() = withServer {
        val alice = registerAndLogin("a@x.io", "secret123")
        val (app, page) = appWithPage(alice)
        call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${viewJson(page)}]}""")
        awaitIngested()

        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/account", alice, json = """{"password":"nope-nope"}""").status)
        val deleted = call(HttpMethod.Delete, "/account", alice, json = """{"password":"secret123"}""")
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/account", alice).status)
        assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
        // The application is gone: its views are refused
        val views = call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${viewJson(page)}]}""")
        assertEquals(HttpStatusCode.BadRequest, views.status)
        // And the email can be registered again
        assertEquals(HttpStatusCode.Created, postForm("/register", "email" to "a@x.io", "password" to "secret123", "displayName" to "A").status)
    }
}
