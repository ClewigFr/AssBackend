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
    fun statsNeedAValidSession() = withServer {
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/apps").status)
        val cookie = registerAndLogin("a@x.io")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/apps", cookie = cookie).status)
    }

    @Test
    fun forgedSessionCookieIsRejected() = withServer {
        registerAndLogin("a@x.io")
        // Unsigned cookie claiming to be a@x.io
        val forged = """MY_SESSION={"userId":"a@x.io","issuedAt":${System.currentTimeMillis()}}"""
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/apps", cookie = forged).status)
    }

    @Test
    fun logoutEndsTheSession() = withServer {
        val cookie = registerAndLogin("a@x.io")
        val logout = call(HttpMethod.Get, "/logout", cookie = cookie)
        val cleared = logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("MY_SESSION=") }
        assertTrue(cleared.startsWith("MY_SESSION=;"), cleared)
    }

    @Test
    fun corsOnlyAllowsTheDashboard() = withServer {
        val allowed = call(HttpMethod.Get, "/", origin = DASHBOARD)
        assertEquals(DASHBOARD, allowed.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/", origin = "https://evil.example").status)
    }
}
