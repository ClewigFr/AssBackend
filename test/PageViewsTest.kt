package com.ass

import com.ass.controller.MAX_VIEW_AGE_MS
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PageViewsTest {

    private suspend fun ApplicationTestBuilder.postViews(body: String) =
        call(HttpMethod.Post, "/views", json = body).status

    @Test
    fun allValidViewsReturn200() = withServer {
        val page = createPage(registerAndLogin("a@x.io"))
        assertEquals(HttpStatusCode.OK, postViews("""{"pageViews":[${viewJson(page)},${viewJson(page)}]}"""))
    }

    @Test
    fun unknownPageMixedWithValidReturns206() = withServer {
        val page = createPage(registerAndLogin("a@x.io"))
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
        val page = createPage(registerAndLogin("a@x.io"))
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[{"page":"$page"}]}"""))
        assertEquals(HttpStatusCode.BadRequest, postViews("""{}"""))
    }

    @Test
    fun outOfRangeTimestampIsRejected() = withServer {
        val page = createPage(registerAndLogin("a@x.io"))
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[${viewJson(page, elapsed = -1)}]}"""))
        assertEquals(HttpStatusCode.BadRequest, postViews("""{"pageViews":[${viewJson(page, elapsed = MAX_VIEW_AGE_MS + 1)}]}"""))
    }
}
