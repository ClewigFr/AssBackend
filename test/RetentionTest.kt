package com.ass

import com.ass.model.PeriodStats
import com.ass.stats.DAY_MS
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetentionTest {
    private suspend fun ApplicationTestBuilder.yearStats(cookie: String, app: String): PeriodStats =
        Json.decodeFromString(call(HttpMethod.Get, "/apps/$app/stats?period=year", cookie).bodyAsText())

    private suspend fun ApplicationTestBuilder.send(app: String, vararg views: String) {
        val status = call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${views.joinToString(",")}]}""").status
        assertEquals(HttpStatusCode.OK, status)
        awaitIngested()
    }

    @Test
    fun oldViewsBecomeDailyCountsAndStillShowInStats() = withServer {
        val alice = registerAndLogin("a@x.io")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")
        val cart = createPage(alice, app, "cart")
        val old = 200 * DAY_MS
        val s1 = UUID.randomUUID().toString()
        val s2 = UUID.randomUUID().toString()
        send(app,
            viewJson(home, old, s1), viewJson(cart, old - 60_000, s1), viewJson(home, old, s2),   // ~200 days ago
            viewJson(home, 10 * DAY_MS), viewJson(cart, 10 * DAY_MS))                             // 10 days ago, 2 sessions
        val before = yearStats(alice, app)
        assertEquals(5, before.current.views)
        assertEquals(4, before.current.sessions)

        assertEquals(1, retention().run())
        val after = yearStats(alice, app)
        // Views are kept, sessions only for the last 90 days
        assertEquals(5, after.current.views)
        assertEquals(2, after.current.sessions)
        assertEquals(1.0, after.current.pagesPerSession)
        assertEquals(5, after.buckets.sumOf { it.views })
        assertEquals(mapOf("home" to 3L, "cart" to 2L), after.pages.associate { it.name to it.views })
        val detailFrom = assertNotNull(after.detailFrom)
        assertTrue(System.currentTimeMillis() - detailFrom in 90 * DAY_MS..91 * DAY_MS)

        // Nothing left to move; a late upload of an old view is merged into the existing day
        assertEquals(0, retention().run())
        send(app, viewJson(home, old))
        assertEquals(1, retention().run())
        assertEquals(6, yearStats(alice, app).current.views)
        assertEquals(4L, yearStats(alice, app).pages.single { it.name == "home" }.views)

        // Deleting a page deletes its daily counts too
        call(HttpMethod.Delete, "/apps/$app/pages/$home", alice)
        assertEquals(2, yearStats(alice, app).current.views)
    }

    @Test
    fun oldDaysShowViewsInTheirDayAndMonthSlots() = withServer {
        val alice = registerAndLogin("a@x.io")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")
        send(app, viewJson(home, 100 * DAY_MS), viewJson(home, 100 * DAY_MS))
        retention().run()

        // The month (30 days) containing that day, in UTC and in UTC-10 (the day must stay on its date)
        for (tz in listOf(0, -600)) {
            val stats: PeriodStats = Json.decodeFromString(
                call(HttpMethod.Get, "/apps/$app/stats?period=month&offset=-3&tz=$tz", alice).bodyAsText())
            val day = (System.currentTimeMillis() - 100 * DAY_MS) / DAY_MS * DAY_MS
            assertEquals(2, stats.current.views, "tz=$tz")
            val bucket = stats.buckets.single { it.views > 0 }
            assertEquals(day - tz * 60_000L, bucket.start, "tz=$tz")
            assertEquals(0, stats.current.sessions)
        }
    }

    @Test
    fun retentionCanBeDisabled() = withServer("ktor.performance.retention_days" to "0") {
        val alice = registerAndLogin("a@x.io")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")
        send(app, viewJson(home, 200 * DAY_MS))
        assertEquals(0, retention().run())
        val stats = yearStats(alice, app)
        assertEquals(1, stats.current.sessions)
        assertNull(stats.detailFrom)
    }
}
