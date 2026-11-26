package com.ass

import com.ass.model.FlowStats
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowStatsTest {

    @Test
    fun listsTransitionsSeenInEnoughSessions() = withServer {
        val alice = registerAndLogin("a@x.io")
        val bob = registerAndLogin("b@x.io")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")
        val list = createPage(alice, app, "list")
        val cart = createPage(alice, app, "cart")
        // 5 sessions home -> list (10 s on home), 1 session home -> cart
        val views = (1..5).flatMap { val s = UUID.randomUUID().toString(); listOf(viewJson(home, 30_000, s), viewJson(list, 20_000, s)) } +
            UUID.randomUUID().toString().let { listOf(viewJson(home, 30_000, it), viewJson(cart, 25_000, it)) }
        call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${views.joinToString(",")}]}""")
        awaitIngested()

        val flows = Json.decodeFromString<FlowStats>(call(HttpMethod.Get, "/apps/$app/flows?period=week", alice).bodyAsText())
        assertEquals(6, flows.sessions)
        assertEquals(1, flows.transitions.size)
        val t = flows.transitions.single()
        assertEquals(UUID.fromString(home), t.from)
        assertEquals(UUID.fromString(list), t.to)
        assertEquals(5, t.sessions)
        assertTrue(t.avgMs in 9_000..11_000, "avg ${t.avgMs}")
        assertEquals(1, flows.hiddenSessions)
        assertEquals(6, flows.entries.single { it.page == UUID.fromString(home) }.sessions)
        assertEquals(5, flows.exits.single { it.page == UUID.fromString(list) }.sessions)

        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app/flows?period=week", bob).status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/apps/$app/flows?period=nope", alice).status)
    }
}
