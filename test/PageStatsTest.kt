package com.ass

import com.ass.model.PeriodStats
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PageStatsTest {

    @Test
    fun filtersTheStatsOnOnePage() = withServer {
        val alice = registerAndLogin("a@x.io")
        val bob = registerAndLogin("b@x.io")
        val app = createApp(alice)
        val home = createPage(alice, app, "home")
        val cart = createPage(alice, app, "cart")
        val session = UUID.randomUUID().toString()
        val views = listOf(viewJson(home, 1000, session), viewJson(home, 2000, session), viewJson(cart, 3000, session),
            viewJson(home, 8L * 24 * 3600 * 1000)).joinToString(",")
        call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[$views]}""")
        awaitIngested()

        val all = Json.decodeFromString<PeriodStats>(call(HttpMethod.Get, "/apps/$app/stats?period=week", alice).bodyAsText())
        assertEquals(3, all.current.views)
        val homeOnly = Json.decodeFromString<PeriodStats>(call(HttpMethod.Get, "/apps/$app/stats?period=week&page=$home", alice).bodyAsText())
        assertEquals(2, homeOnly.current.views)
        assertEquals(1, homeOnly.current.sessions)
        assertEquals(2, homeOnly.buckets.sumOf { it.views })
        // The view of 8 days ago is in the previous week, slot by slot
        assertEquals(homeOnly.buckets.size, homeOnly.previousBuckets.size)
        assertEquals(1, homeOnly.previousBuckets.sumOf { it.views })
        assertEquals(1, homeOnly.previous.views)

        val otherApp = createApp(bob)
        val bobPage = createPage(bob, otherApp)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app/stats?period=week&page=$bobPage", alice).status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/apps/$app/stats?period=week&page=nope", alice).status)
    }
}
