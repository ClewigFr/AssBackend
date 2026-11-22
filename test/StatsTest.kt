package com.ass

import com.ass.model.StatsOut
import com.ass.stats.DAY_MS
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsTest {

    @Test
    fun statsAreAggregatedPerPageAndDay() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val home = createPage(alice, "home")
        val settings = createPage(alice, "settings")
        createPage(alice, "unused")
        val s1 = "11111111-1111-4111-8111-111111111111"
        val s2 = "22222222-2222-4222-8222-222222222222"
        val views = listOf(
            viewJson(home, elapsed = 1_000, session = s1),
            viewJson(settings, elapsed = 2_000, session = s1),
            viewJson(home, elapsed = DAY_MS + 1_000, session = s2)
        ).joinToString(",")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"pageViews":[$views]}""").status)
        awaitIngested()

        val stats = Json.decodeFromString<StatsOut>(call(HttpMethod.Get, "/stats/json", alice).bodyAsText())
        assertEquals(3, stats.events)
        assertEquals(2, stats.sessions)
        assertEquals(mapOf("home" to 2L, "settings" to 1L, "unused" to 0L), stats.pages.associate { it.name to it.views })
        // Yesterday: 1 view / 1 session, today: 2 views / 1 session (unless the test runs right at midnight)
        assertEquals(stats.events, stats.days.sumOf { it.events })
        assertEquals(stats.days.sortedBy { it.day }, stats.days)
    }

    @Test
    fun timeZoneShiftsDays() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val home = createPage(alice)
        call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(home, elapsed = 1_000)}]}""")
        awaitIngested()
        val now = System.currentTimeMillis()
        val utc = Json.decodeFromString<StatsOut>(call(HttpMethod.Get, "/stats/json?tz=0", alice).bodyAsText())
        val plus14 = Json.decodeFromString<StatsOut>(call(HttpMethod.Get, "/stats/json?tz=840", alice).bodyAsText())
        assertEquals((now - 1_000) / DAY_MS, utc.days.single().day)
        assertEquals((now - 1_000 + 14 * 3_600_000) / DAY_MS, plus14.days.single().day)
    }

    @Test
    fun invalidTimeZoneIsRejected() = withServer {
        val alice = registerAndLogin("alice@x.io")
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/stats/json?tz=100000", alice).status)
    }
}

class StatsCacheTest {
    @Test
    fun statsAreCachedBriefly() = withServer("ktor.performance.stats_cache_ms" to "60000") {
        val alice = registerAndLogin("alice@x.io")
        val home = createPage(alice)
        val first = Json.decodeFromString<StatsOut>(call(HttpMethod.Get, "/stats/json", alice).bodyAsText())
        call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(home)}]}""")
        awaitIngested()
        val cached = Json.decodeFromString<StatsOut>(call(HttpMethod.Get, "/stats/json", alice).bodyAsText())
        assertEquals(first, cached)
        // Creating a page invalidates the cache
        createPage(alice, "other")
        val fresh = Json.decodeFromString<StatsOut>(call(HttpMethod.Get, "/stats/json", alice).bodyAsText())
        assertEquals(1, fresh.events)
    }
}
