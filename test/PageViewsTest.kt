package com.ass

import com.ass.controller.MAX_VIEW_AGE_MS
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.UUID
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals

class PageViewsTest {

    private suspend fun ApplicationTestBuilder.postViews(body: String) =
        call(HttpMethod.Post, "/views", json = body).status

    @Test
    fun allValidViewsReturn200() = withServer {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        assertEquals(HttpStatusCode.OK, postViews("""{"pageViews":[${viewJson(page)},${viewJson(page)}]}"""))
    }

    @Test
    fun unknownPageMixedWithValidReturns206() = withServer {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        val body = """{"pageViews":[${viewJson(page)},${viewJson(UUID.randomUUID().toString())}]}"""
        assertEquals(HttpStatusCode.PartialContent, postViews(body))
    }

    @Test
    fun onlyUnknownPagesReturn400() = withServer {
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[${viewJson(UUID.randomUUID().toString())}]}"""))
    }

    @Test
    fun emptyBatchReturns400() = withServer {
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[]}"""))
    }

    @Test
    fun malformedBodyReturns400NotServerError() = withServer {
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[{"page":"not-a-uuid"}]}"""))
        assertEquals(HttpStatusCode.BadRequest, postViews("""not json"""))
    }

    @Test
    fun missingFieldsAreRejected() = withServer {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[{"page":"$page"}]}"""))
        assertEquals(HttpStatusCode.BadRequest, postViews("""{}"""))
    }

    @Test
    fun outOfRangeTimestampIsRejected() = withServer {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[${viewJson(page, elapsed = -1)}]}"""))
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[${viewJson(page, elapsed = MAX_VIEW_AGE_MS + 1)}]}"""))
    }

    @Test
    fun viewsAreKeptWhileTheDatabaseFails() = withServer {
        val (app, page) = appWithPage(registerAndLogin("a@x.io"))
        val db = database()
        val table = com.ass.dao.PagesViews.nameInDatabaseCase()
        // Writes fail while the table is renamed, as during a database outage
        db.query { exec("ALTER TABLE $table RENAME TO pv_away") }
        assertEquals(HttpStatusCode.OK, postViews("""{"app":"$app","pageViews":[${viewJson(page)},${viewJson(page)}]}"""))
        kotlinx.coroutines.delay(500)
        db.query { exec("ALTER TABLE pv_away RENAME TO $table") }
        awaitIngested()
        assertEquals(2L, db.query { com.ass.dao.PagesViews.selectAll().count() })
    }
}
