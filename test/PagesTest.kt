package com.ass

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.handleRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class PagesTest {

    @Test
    fun creatingAPageNeedsLoginAndAName() = withServer {
        val anonymous = handleRequest(HttpMethod.Post, "/pages") { json("""{"name":"home"}""") }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.response.status())
        val cookie = registerAndLogin("a@x.io")
        val blank = handleRequest(HttpMethod.Post, "/pages") {
            addHeader(HttpHeaders.Cookie, cookie); json("""{"name":"  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.response.status())
        val ok = handleRequest(HttpMethod.Post, "/pages") {
            addHeader(HttpHeaders.Cookie, cookie); json("""{"name":"home"}""")
        }
        assertEquals(HttpStatusCode.Created, ok.response.status())
    }

    @Test
    fun onlyTheOwnerCanDeleteAPage() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val page = createPage(alice)

        val byBob = handleRequest(HttpMethod.Delete, "/pages") {
            addHeader(HttpHeaders.Cookie, bob); json("""{"id":"$page","name":""}""")
        }
        assertEquals(HttpStatusCode.NotFound, byBob.response.status())

        val byAlice = handleRequest(HttpMethod.Delete, "/pages") {
            addHeader(HttpHeaders.Cookie, alice); json("""{"id":"$page","name":""}""")
        }
        assertEquals(HttpStatusCode.OK, byAlice.response.status())
    }

    @Test
    fun deletingAPageRemovesItsViews() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val page = createPage(alice)
        handleRequest(HttpMethod.Post, "/views") { json("""{"pageViews":[${viewJson(page)}]}""") }
        handleRequest(HttpMethod.Delete, "/pages") { addHeader(HttpHeaders.Cookie, alice); json("""{"id":"$page","name":""}""") }
        val stats = handleRequest(HttpMethod.Get, "/stats/json") { addHeader(HttpHeaders.Cookie, alice) }
        assertEquals("""{"stats":[],"pages":[]}""", stats.response.content)
    }
}
