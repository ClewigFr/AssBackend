package com.ass

import com.ass.model.AppPagesOut
import com.ass.model.BatchPagesOut
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BatchPagesTest {

    @Test
    fun createsNewPagesAndKeepsExistingOnes() = withServer {
        val alice = registerAndLogin("a@x.io", "secret123")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")

        val response = call(HttpMethod.Post, "/apps/$app/pages/batch", alice,
            json = """{"names":["settings"," home ","profile","settings",""]}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val result = Json.decodeFromString<BatchPagesOut>(response.bodyAsText())
        assertEquals(listOf("settings", "profile"), result.created.map { it.name })
        assertEquals(listOf(home), result.existing.map { it.id.toString() })

        val detail = Json.decodeFromString<AppPagesOut>(call(HttpMethod.Get, "/apps/$app", alice).bodyAsText())
        assertEquals(listOf("home", "profile", "settings"), detail.pages.map { it.name })

        // Importing the same list again creates nothing
        val again = Json.decodeFromString<BatchPagesOut>(
            call(HttpMethod.Post, "/apps/$app/pages/batch", alice, json = """{"names":["home","settings","profile"]}""").bodyAsText())
        assertEquals(0, again.created.size)
        assertEquals(3, again.existing.size)
    }

    @Test
    fun createdPagesAcceptViewsAtOnce() = withServer {
        val alice = registerAndLogin("a@x.io", "secret123")
        val app = createApp(alice)
        val page = Json.decodeFromString<BatchPagesOut>(
            call(HttpMethod.Post, "/apps/$app/pages/batch", alice, json = """{"names":["home"]}""").bodyAsText()).created.single()
        val views = call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${viewJson(page.id.toString())}]}""")
        assertEquals(HttpStatusCode.OK, views.status)
    }

    @Test
    fun rejectsBadInputAndOtherUsers() = withServer {
        val alice = registerAndLogin("a@x.io", "secret123")
        val bob = registerAndLogin("b@x.io", "secret123")
        val app = createApp(alice)
        val path = "/apps/$app/pages/batch"
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, path, alice, json = """{"names":[]}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, path, alice, json = """{"names":["${"x".repeat(256)}"]}""").status)
        val tooMany = (1..1001).joinToString(",") { "\"p$it\"" }
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, path, alice, json = """{"names":[$tooMany]}""").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, path, bob, json = """{"names":["x"]}""").status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, path, json = """{"names":["x"]}""").status)
    }
}
