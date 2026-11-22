package com.ass

import com.ass.model.PeriodStats
import com.ass.stats.DAY_MS
import com.ass.stats.HOUR_MS
import com.ass.stats.Period
import com.ass.stats.bucketIndex
import com.ass.stats.rangeOf
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun ApplicationTestBuilder.stats(cookie: String, app: String, query: String = ""): PeriodStats =
    Json.decodeFromString(call(HttpMethod.Get, "/apps/$app/stats$query", cookie).bodyAsText())

private val MINUTE = 60_000L

class PeriodRangeTest {
    // Sunday 22 November 2026, 15:30 in UTC+1
    private val tz = HOUR_MS
    private val now = LocalDateTime.of(2026, 11, 22, 15, 30).toInstant(ZoneOffset.ofHours(1)).toEpochMilli()
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.ofHours(1)).toEpochMilli()

    @Test
    fun dayIsTodayHourByHour() {
        val r = rangeOf(Period.DAY, 0, tz, now)
        assertEquals(at(2026, 11, 22), r.from)
        assertEquals(at(2026, 11, 23), r.to)
        assertEquals(24, r.bucketStarts.size)
        assertEquals(15, r.bucketIndex(now))
    }

    @Test
    fun weekIsTheLastSevenDays() {
        val r = rangeOf(Period.WEEK, 0, tz, now)
        assertEquals(at(2026, 11, 16), r.from)
        assertEquals(at(2026, 11, 23), r.to)
        assertEquals(7, r.bucketStarts.size)
        val previous = rangeOf(Period.WEEK, -1, tz, now)
        assertEquals(r.from, previous.to)
        assertEquals(at(2026, 11, 9), previous.from)
    }

    @Test
    fun monthIsTheLastThirtyDays() {
        val r = rangeOf(Period.MONTH, 0, tz, now)
        assertEquals(at(2026, 10, 24), r.from)
        assertEquals(30, r.bucketStarts.size)
    }

    @Test
    fun yearIsTheLastTwelveCalendarMonths() {
        val r = rangeOf(Period.YEAR, 0, tz, now)
        assertEquals(at(2025, 12, 1), r.from)
        assertEquals(at(2026, 12, 1), r.to)
        assertEquals(12, r.bucketStarts.size)
        assertEquals(at(2026, 2, 1), r.bucketStarts[2])
        assertEquals(11, r.bucketIndex(now))
        assertEquals(-1, r.bucketIndex(at(2025, 11, 30, 23)))
    }

    @Test
    fun parsePeriods() {
        assertEquals(Period.WEEK, Period.parse(null))
        assertEquals(Period.YEAR, Period.parse("Year"))
        assertEquals(null, Period.parse("decade"))
    }
}

class StatsTest {

    @Test
    fun sessionMetrics() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")
        val cart = createPage(alice, app, "cart")
        createPage(alice, app, "unused")
        val s1 = "11111111-1111-4111-8111-111111111111"
        val s2 = "22222222-2222-4222-8222-222222222222"
        // s1: 3 views over 10 minutes; s2: a single view (a bounce). All within the last 7 days.
        val views = listOf(
            viewJson(home, elapsed = 11 * MINUTE, session = s1),
            viewJson(cart, elapsed = 6 * MINUTE, session = s1),
            viewJson(home, elapsed = 1 * MINUTE, session = s1),
            viewJson(home, elapsed = 2 * MINUTE, session = s2)
        ).joinToString(",")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[$views]}""").status)
        awaitIngested()

        val week = stats(alice, app, "?period=week")
        assertEquals("week", week.period)
        assertEquals(4, week.current.views)
        assertEquals(2, week.current.sessions)
        assertEquals(2.0, week.current.pagesPerSession)
        assertEquals(0.5, week.current.bounceRate)
        // (10 min + 0) / 2 sessions, give or take request latency
        assertTrue(week.current.avgSessionMs in (5 * MINUTE - 2_000)..(5 * MINUTE + 2_000), "${week.current.avgSessionMs}")

        assertEquals(listOf("home" to (3L to 2L), "cart" to (1L to 1L), "unused" to (0L to 0L)),
            week.pages.map { it.name to (it.views to it.sessions) })
        assertEquals(7, week.buckets.size)
        assertEquals(4, week.buckets.sumOf { it.views })
        assertEquals(2, week.buckets.sumOf { it.sessions })
    }

    @Test
    fun previousPeriodIsComparedAndBucketed() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val (app, page) = appWithPage(alice)
        // Last week: 8.5 days ago is always within days 7..13 back
        val views = listOf(
            viewJson(page, elapsed = MINUTE),
            viewJson(page, elapsed = (8.5 * DAY_MS).toLong()),
            viewJson(page, elapsed = (8.5 * DAY_MS).toLong())
        ).joinToString(",")
        call(HttpMethod.Post, "/views", json = """{"pageViews":[$views]}""")
        awaitIngested()

        val thisWeek = stats(alice, app, "?period=week&tz=0")
        assertEquals(1, thisWeek.current.views)
        assertEquals(2, thisWeek.previous.views)
        val lastWeek = stats(alice, app, "?period=week&offset=-1&tz=0")
        assertEquals(2, lastWeek.current.views)
        assertEquals(thisWeek.from, lastWeek.to)

        val year = stats(alice, app, "?period=year&tz=0")
        assertEquals(12, year.buckets.size)
        assertEquals(3, year.current.views)
        assertEquals(3, year.buckets.sumOf { it.views })

        val day = stats(alice, app, "?period=day&tz=0")
        assertEquals(24, day.buckets.size)
        assertEquals("hour", day.granularity)
    }

    @Test
    fun statsOnlyCountTheirOwnApplication() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val (app1, page1) = appWithPage(alice)
        val (app2, page2) = appWithPage(alice)
        call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(page1)},${viewJson(page1)},${viewJson(page2)}]}""")
        awaitIngested()
        assertEquals(2, stats(alice, app1).current.views)
        assertEquals(1, stats(alice, app2).current.views)
    }

    @Test
    fun emptyAppHasZeroes() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val app = createApp(alice)
        val s = stats(alice, app, "?period=month")
        assertEquals(0, s.current.sessions)
        assertEquals(0.0, s.current.bounceRate)
        assertEquals(30, s.buckets.size)
        assertTrue(s.buckets.all { it.views == 0L && it.sessions == 0L })
    }

    @Test
    fun invalidParametersAreRejected() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val app = createApp(alice)
        for (q in listOf("?tz=100000", "?period=decade", "?offset=1", "?offset=-99999")) {
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/apps/$app/stats$q", alice).status, q)
        }
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
        assertEquals(1, stats(alice, app).current.views)
    }
}
