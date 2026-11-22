package com.ass

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class PagesTest {

    @Test
    fun creatingAPageNeedsLoginAndAName() = withServer {
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/pages", json = """{"name":"home"}""").status)
        val cookie = registerAndLogin("a@x.io")
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/pages", cookie, json = """{"name":"  "}""").status)
        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/pages", cookie, json = """{"name":"home"}""").status)
    }

    @Test
    fun onlyTheOwnerCanDeleteAPage() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val bob = registerAndLogin("bob@x.io")
        val page = createPage(alice)
        val body = """{"id":"$page","name":""}"""
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/pages", bob, json = body).status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/pages", alice, json = body).status)
    }

    @Test
    fun deletingAPageRemovesItsViews() = withServer {
        val alice = registerAndLogin("alice@x.io")
        val page = createPage(alice)
        call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(page)}]}""")
        call(HttpMethod.Delete, "/pages", alice, json = """{"id":"$page","name":""}""")
        assertEquals("""{"stats":[],"pages":[]}""", call(HttpMethod.Get, "/stats/json", alice).bodyAsText())
    }
}
