package com.ass

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.handleRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthTest {

    @Test
    fun registerThenDuplicateIsConflict() = withServer {
        val first = handleRequest(HttpMethod.Post, "/register") { form("email" to "a@x.io", "password" to "secret123") }
        assertEquals(HttpStatusCode.Created, first.response.status())
        val again = handleRequest(HttpMethod.Post, "/register") { form("email" to "a@x.io", "password" to "secret123") }
        assertEquals(HttpStatusCode.Conflict, again.response.status())
    }

    @Test
    fun registerValidatesInput() = withServer {
        val shortPw = handleRequest(HttpMethod.Post, "/register") { form("email" to "a@x.io", "password" to "123") }
        assertEquals(HttpStatusCode.BadRequest, shortPw.response.status())
        val badEmail = handleRequest(HttpMethod.Post, "/register") { form("email" to "nope", "password" to "secret123") }
        assertEquals(HttpStatusCode.BadRequest, badEmail.response.status())
    }

    @Test
    fun loginChecksPassword() = withServer {
        registerAndLogin("a@x.io", "secret123")
        val wrong = handleRequest(HttpMethod.Post, "/login") { form("userId" to "a@x.io", "password" to "wrongpw1") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.response.status())
        assertNull(wrong.response.headers[HttpHeaders.SetCookie])
        val ok = handleRequest(HttpMethod.Post, "/login") { form("userId" to "a@x.io", "password" to "secret123") }
        assertEquals(HttpStatusCode.OK, ok.response.status())
        assertEquals("Logged", ok.response.content)
    }

    @Test
    fun statsNeedAValidSession() = withServer {
        assertEquals(HttpStatusCode.Unauthorized, handleRequest(HttpMethod.Get, "/stats/json").response.status())
        val cookie = registerAndLogin("a@x.io")
        val logged = handleRequest(HttpMethod.Get, "/stats/json") { addHeader(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, logged.response.status())
    }

    @Test
    fun forgedSessionCookieIsRejected() = withServer {
        registerAndLogin("a@x.io")
        // Unsigned cookie claiming to be a@x.io
        val forged = handleRequest(HttpMethod.Get, "/stats/json") {
            addHeader(HttpHeaders.Cookie, "MY_SESSION=userId%3D%2523sa%40x.io")
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.response.status())
    }

    @Test
    fun logoutEndsTheSession() = withServer {
        val cookie = registerAndLogin("a@x.io")
        val logout = handleRequest(HttpMethod.Get, "/logout") { addHeader(HttpHeaders.Cookie, cookie) }
        val cleared = logout.response.headers.values(HttpHeaders.SetCookie).first { it.startsWith("MY_SESSION=") }
        assert(cleared.startsWith("MY_SESSION=;"))
    }

    @Test
    fun corsOnlyAllowsTheDashboard() = withServer {
        val allowed = handleRequest(HttpMethod.Get, "/") { addHeader(HttpHeaders.Origin, DASHBOARD) }
        assertEquals(DASHBOARD, allowed.response.headers[HttpHeaders.AccessControlAllowOrigin])
        val evil = handleRequest(HttpMethod.Get, "/") { addHeader(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, evil.response.status())
    }
}
