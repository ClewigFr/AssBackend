package com.ass

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTest {

    @Test
    fun registerThenDuplicateIsConflict() = withServer {
        assertEquals(HttpStatusCode.Created, postForm("/register", "email" to "a@x.io", "password" to "secret123").status)
        assertEquals(HttpStatusCode.Conflict, postForm("/register", "email" to "a@x.io", "password" to "secret123").status)
    }

    @Test
    fun registerValidatesInput() = withServer {
        assertEquals(HttpStatusCode.BadRequest, postForm("/register", "email" to "a@x.io", "password" to "123").status)
        assertEquals(HttpStatusCode.BadRequest, postForm("/register", "email" to "nope", "password" to "secret123").status)
    }

    @Test
    fun loginChecksPassword() = withServer {
        registerAndLogin("a@x.io", "secret123")
        val wrong = postForm("/login", "userId" to "a@x.io", "password" to "wrongpw1")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertNull(sessionCookie(wrong))
        val ok = postForm("/login", "userId" to "a@x.io", "password" to "secret123")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("Logged", ok.bodyAsText())
    }

    @Test
    fun sessionCheckIsNeverAnError() = withServer {
        val anonymous = call(HttpMethod.Get, "/login")
        assertEquals(HttpStatusCode.OK, anonymous.status)
        assertEquals("Not logged", anonymous.bodyAsText())
        val logged = call(HttpMethod.Get, "/login", cookie = registerAndLogin("a@x.io"))
        assertEquals("Logged", logged.bodyAsText())
    }

    @Test
    fun statsNeedAValidSession() = withServer {
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/apps").status)
        val cookie = registerAndLogin("a@x.io")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/apps", cookie = cookie).status)
    }

    @Test
    fun forgedSessionCookieIsRejected() = withServer {
        registerAndLogin("a@x.io")
        // Unsigned cookie claiming to be user 1
        val forged = """MY_SESSION={"userId":"1","issuedAt":${System.currentTimeMillis()}}"""
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/apps", cookie = forged).status)
    }

    @Test
    fun logoutEndsTheSession() = withServer {
        val cookie = registerAndLogin("a@x.io")
        val logout = call(HttpMethod.Post, "/logout", cookie = cookie)
        val cleared = logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("MY_SESSION=") }
        assertTrue(cleared.startsWith("MY_SESSION=;"), cleared)
        // A copy of the cookie kept by someone else is revoked too
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/apps", cookie = cookie).status)
        // Logging in again works
        val again = sessionCookie(postForm("/login", "userId" to "a@x.io", "password" to "secret123"))!!
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/apps", cookie = again).status)
    }

    @Test
    fun logoutIsNotAGet() = withServer {
        val cookie = registerAndLogin("a@x.io")
        call(HttpMethod.Get, "/logout", cookie = cookie)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/apps", cookie = cookie).status)
    }

    @Test
    fun emailsAreCaseInsensitive() = withServer {
        registerAndLogin("Alice@X.io")
        assertEquals(HttpStatusCode.Conflict, postForm("/register", "email" to "alice@x.io", "password" to "secret123").status)
        assertEquals(HttpStatusCode.OK, postForm("/login", "userId" to "ALICE@x.io", "password" to "secret123").status)
    }

    @Test
    fun registerChecksLengths() = withServer {
        assertEquals(HttpStatusCode.BadRequest, postForm("/register", "email" to "a@x.io", "password" to "short12").status)
        assertEquals(HttpStatusCode.BadRequest, postForm("/register", "email" to "a@x.io", "password" to "p".repeat(73)).status)
        assertEquals(HttpStatusCode.BadRequest, postForm("/register", "email" to "a".repeat(130) + "@x.io", "password" to "secret123").status)
        assertEquals(HttpStatusCode.BadRequest,
            postForm("/register", "email" to "a@x.io", "password" to "secret123", "displayName" to "n".repeat(257)).status)
        assertEquals(HttpStatusCode.Created, postForm("/register", "email" to "a@x.io", "password" to "secret123").status)
    }

    @Test
    fun stateChangesFromAnotherSiteAreRefused() = withServer {
        val cookie = registerAndLogin("a@x.io")
        assertEquals(HttpStatusCode.Forbidden,
            call(HttpMethod.Post, "/apps", cookie = cookie, origin = "https://evil.example", json = """{"name":"x"}""").status)
        assertEquals(HttpStatusCode.Forbidden,
            call(HttpMethod.Post, "/login", origin = "https://evil.example", form = listOf("userId" to "a@x.io", "password" to "secret123")).status)
        assertEquals(HttpStatusCode.Created,
            call(HttpMethod.Post, "/apps", cookie = cookie, origin = DASHBOARD, json = """{"name":"x"}""").status)
    }

    @Test
    fun hugeBodiesAreRefused() = withServer {
        val cookie = registerAndLogin("a@x.io")
        val big = """{"name":"${"x".repeat(2 shl 20)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, call(HttpMethod.Post, "/apps", cookie = cookie, json = big).status)
    }

    @Test
    fun corsOnlyAllowsTheDashboard() = withServer {
        val allowed = call(HttpMethod.Get, "/", origin = DASHBOARD)
        assertEquals(DASHBOARD, allowed.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/", origin = "https://evil.example").status)
    }
}
