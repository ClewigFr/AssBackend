package com.ass

import com.ass.model.StatsOut
import com.ass.stats.DAY_MS
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

private suspend fun ApplicationTestBuilder.stats(cookie: String, app: String, query: String = ""): StatsOut =
    Json.decodeFromString(call(HttpMethod.Get, "/apps/$app/stats$query", cookie).bodyAsText())

class StatsTest {

    @Test
    fun statsAreAggregatedPerPageAndDay() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")
        val settings = createPage(alice, app, "settings")
        createPage(alice, app, "unused")
        val s1 = "11111111-1111-4111-8111-111111111111"
        val s2 = "22222222-2222-4222-8222-222222222222"
        val views = listOf(
            viewJson(home, elapsed = 1_000, session = s1),
            viewJson(settings, elapsed = 2_000, session = s1),
            viewJson(home, elapsed = DAY_MS + 1_000, session = s2)
        ).joinToString(",")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[$views]}""").status)
        awaitIngested()

        val stats = stats(alice, app)
        assertEquals(3, stats.events)
        assertEquals(2, stats.sessions)
        assertEquals(mapOf("home" to 2L, "settings" to 1L, "unused" to 0L), stats.pages.associate { it.name to it.views })
        assertEquals(stats.events, stats.days.sumOf { it.events })
        assertEquals(stats.days.sortedBy { it.day }, stats.days)
    }

    @Test
    fun statsOnlyCountTheirOwnApplication() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val (app1, page1) = appWithPage(alice)
        val (app2, page2) = appWithPage(alice)
        call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(page1)},${viewJson(page1)},${viewJson(page2)}]}""")
        awaitIngested()
        assertEquals(2, stats(alice, app1).events)
        assertEquals(1, stats(alice, app2).events)
    }

    @Test
    fun timeZoneShiftsDays() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val (app, home) = appWithPage(alice)
        call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(home, elapsed = 1_000)}]}""")
        awaitIngested()
        val now = System.currentTimeMillis()
        assertEquals((now - 1_000) / DAY_MS, stats(alice, app, "?tz=0").days.single().day)
        assertEquals((now - 1_000 + 14 * 3_600_000) / DAY_MS, stats(alice, app, "?tz=840").days.single().day)
    }

    @Test
    fun invalidTimeZoneIsRejected() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val app = createApp(alice)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/apps/$app/stats?tz=100000", alice).status)
    }
}

class StatsCacheTest {
    @Test
    fun statsAreCachedBriefly() = withServer("ktor.performance.stats_cache_ms" to "60000") {
        val alice = registerAndLogin("alice@x.io")
        val (app, home) = appWithPage(alice)
        val first = stats(alice, app)
        call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(home)}]}""")
        awaitIngested()
        assertEquals(first, stats(alice, app))
        // Adding a page invalidates that application's cache
        createPage(alice, app, "other")
        assertEquals(1, stats(alice, app).events)
    }
}
