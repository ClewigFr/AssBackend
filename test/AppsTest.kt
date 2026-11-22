package com.ass

import com.ass.model.AppPagesOut
import com.ass.model.AppsOut
import com.ass.model.StatsOut
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppsTest {

    @Test
    fun createAppsAndPages() = withServer {
        val alice = registerAndLogin("alice@x.io")
        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/apps", alice, json = """{"name":"Shop"}""").status)
        val shop = Json.decodeFromString<AppsOut>(call(HttpMethod.Get, "/apps", alice).bodyAsText()).apps.single()
        assertEquals("Shop", shop.name)
        UUID.fromString(shop.id.toString())

        createPage(alice, shop.id.toString(), "home")
        createPage(alice, shop.id.toString(), "cart")
        val detail = Json.decodeFromString<AppPagesOut>(call(HttpMethod.Get, "/apps/${shop.id}", alice).bodyAsText())
        assertEquals(listOf("cart", "home"), detail.pages.map { it.name })
        assertEquals(2, detail.app.pages)
        assertEquals(2, Json.decodeFromString<AppsOut>(call(HttpMethod.Get, "/apps", alice).bodyAsText()).apps.single().pages)
    }

    @Test
    fun namesAreRequired() = withServer {
        val alice = registerAndLogin("alice@x.io")
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/apps", alice, json = """{"name":"  "}""").status)
        val app = createApp(alice)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/apps/$app/pages", alice, json = """{}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/apps", alice, json = """{"name":"${"x".repeat(256)}"}""").status)
    }

    @Test
    fun everythingNeedsLogin() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val app = createApp(alice)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/apps").status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/apps", json = """{"name":"x"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/apps/$app/stats").status)
    }

    @Test
    fun usersOnlySeeTheirOwnApplications() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val (app, page) = appWithPage(alice)

        assertEquals(0, Json.decodeFromString<AppsOut>(call(HttpMethod.Get, "/apps", bob).bodyAsText()).apps.size)
        // Bob gets 404 everywhere, as if the application didn't exist
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app", bob).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app/stats", bob).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/apps/$app/pages", bob, json = """{"name":"x"}""").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/apps/$app/pages/$page", bob).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/apps/$app", bob).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/not-a-uuid", bob).status)
        // Alice's app is untouched
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/apps/$app", alice).status)
    }

    @Test
    fun deletingAPageRemovesItsViews() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")
        val cart = createPage(alice, app, "cart")
        call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${viewJson(home)},${viewJson(cart)}]}""")
        awaitIngested()
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$app/pages/$home", alice).status)
        val stats = Json.decodeFromString<StatsOut>(call(HttpMethod.Get, "/apps/$app/stats", alice).bodyAsText())
        assertEquals(1, stats.events)
        assertEquals(listOf("cart"), stats.pages.map { it.name })
        // Views for the deleted page are now rejected
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(home)}]}""").status)
    }

    @Test
    fun deletingAnAppRemovesItsPagesAndViews() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val (app, page) = appWithPage(alice)
        call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(page)}]}""")
        awaitIngested()
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$app", alice).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app", alice).status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(page)}]}""").status)
        assertTrue(Json.decodeFromString<AppsOut>(call(HttpMethod.Get, "/apps", alice).bodyAsText()).apps.isEmpty())
    }
}

class ViewsWithAppTest {

    @Test
    fun pagesMustBelongToTheConfiguredApp() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val (app1, page1) = appWithPage(alice)
        val (_, page2) = appWithPage(alice)
        val mixed = """{"app":"$app1","pageViews":[${viewJson(page1)},${viewJson(page2)}]}"""
        assertEquals(HttpStatusCode.PartialContent, call(HttpMethod.Post, "/views", json = mixed).status)
        val wrongApp = """{"app":"$app1","pageViews":[${viewJson(page2)}]}"""
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/views", json = wrongApp).status)
        val unknownApp = """{"app":"${UUID.randomUUID()}","pageViews":[${viewJson(page1)}]}"""
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/views", json = unknownApp).status)
    }

    @Test
    fun clientsWithoutAppIdStillWork() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val (app, page) = appWithPage(alice)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(page)}]}""").status)
        awaitIngested()
        // The view is still attributed to the page's application
        assertEquals(1, Json.decodeFromString<StatsOut>(call(HttpMethod.Get, "/apps/$app/stats", alice).bodyAsText()).events)
    }
}
