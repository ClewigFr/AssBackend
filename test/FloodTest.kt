package com.ass

import com.ass.ingest.ViewGuard
import com.ass.ingest.ViewRow
import com.ass.model.DeletedOut
import com.ass.model.PeriodStats
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals

class FloodTest {

    private fun views(page: String, count: Int, session: String = UUID.randomUUID().toString()) =
        (1..count).joinToString(",") { viewJson(page, 1000, session) }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.weekViews(cookie: String, app: String) =
        Json.decodeFromString<PeriodStats>(call(HttpMethod.Get, "/apps/$app/stats?period=week", cookie).bodyAsText()).current.views

    @Test
    fun dropsViewsOverTheSessionLimitWithoutAskingToRetry() = withServer("ktor.performance.views_per_session" to "5") {
        val alice = registerAndLogin("a@x.io")
        val (app, page) = appWithPage(alice)
        val session = UUID.randomUUID().toString()
        val first = call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${views(page, 8, session)}]}""")
        assertEquals(HttpStatusCode.PartialContent, first.status)
        // Nothing left for this session today: nothing accepted, but no 429 either
        assertEquals(HttpStatusCode.BadRequest,
            call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${views(page, 2, session)}]}""").status)
        // Another session is fine
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${views(page, 3)}]}""").status)
        awaitIngested()
        assertEquals(8, weekViews(alice, app))
    }

    @Test
    fun dropsViewsOverTheIpLimitPerApplication() = withServer("ktor.performance.views_per_ip_app_hour" to "10") {
        val alice = registerAndLogin("a@x.io")
        val (app, page) = appWithPage(alice)
        val (other, otherPage) = appWithPage(alice)
        repeat(3) { call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${views(page, 4)}]}""") }
        // The limit is per application: another one still gets its views
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"app":"$other","pageViews":[${views(otherPage, 4)}]}""").status)
        awaitIngested()
        assertEquals(10, weekViews(alice, app))
        assertEquals(4, weekViews(alice, other))
    }

    @Test
    fun countsDroppedViewsPerApplicationAndHour() {
        val guard = ViewGuard(perIpAppHour = 0, perSession = 2)
        val app = UUID.randomUUID()
        val session = UUID.randomUUID()
        // Identical rows, as a flood sends them
        val rows = List(5) { ViewRow(app, app, session, 0) }
        assertEquals(2, guard.filter("1.2.3.4", rows, now = 7_200_500).size)
        assertEquals(mapOf((app to 7_200_000L) to 3L), guard.takeDropped())
        assertEquals(emptyMap(), guard.takeDropped())
    }

    @Test
    fun savesTheDroppedViews() = withServer("ktor.performance.views_per_session" to "2") {
        val alice = registerAndLogin("a@x.io")
        val (app, page) = appWithPage(alice)
        val session = UUID.randomUUID().toString()
        repeat(2) {
            call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${views(page, 4, session)}]}""")
            // Saved twice: the second time adds to the same row
            com.ass.ingest.saveDropped(database(), guard())
        }
        val saved = database().query {
            com.ass.dao.ViewsDropped.selectAll().single()[com.ass.dao.ViewsDropped.views]
        }
        assertEquals(6, saved)
    }

    @Test
    fun deletesTheViewsOfAPeriod() = withServer {
        val alice = registerAndLogin("a@x.io")
        val bob = registerAndLogin("b@x.io")
        val (app, page) = appWithPage(alice)
        val old = (1..3).joinToString(",") { viewJson(page, 3 * 3_600_000L) }
        call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[$old,${views(page, 4)}]}""")
        awaitIngested()
        val now = System.currentTimeMillis()

        // The last hour only: the 4 recent views
        val path = "/apps/$app/views?from=${now - 3_600_000}&to=${now + 60_000}"
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, path, bob).status)
        val response = call(HttpMethod.Delete, path, alice)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(4, Json.decodeFromString<DeletedOut>(response.bodyAsText()).deleted)
        assertEquals(3, weekViews(alice, app))
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Delete, "/apps/$app/views?from=10&to=5", alice).status)
    }
}
