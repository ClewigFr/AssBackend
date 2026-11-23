package com.ass

import com.ass.auth.LoginThrottle
import com.ass.auth.SESSION_MAX_AGE_MS
import com.ass.auth.isSessionValid
import com.ass.controller.MAX_VIEWS_PER_REQUEST
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityTest {

    @Test
    fun loginIsLockedAfterRepeatedFailures() = withServer {
        registerAndLogin("a@x.io", "secret123")
        repeat(LoginThrottle.MAX_FAILURES) {
            assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "a@x.io", "password" to "wrongpw1").status)
        }
        // Even the right password is refused while locked
        assertEquals(HttpStatusCode.TooManyRequests, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
        // Other accounts are not affected
        registerAndLogin("b@x.io", "secret123")
        assertEquals(HttpStatusCode.OK, postForm("/login", "userId" to "b@x.io", "password" to "secret123").status)
    }

    @Test
    fun lockExpires() {
        LoginThrottle.reset()
        val t0 = 1_000_000L
        repeat(LoginThrottle.MAX_FAILURES) { LoginThrottle.recordFailure("A@x.io ", "1.1.1.1", t0) }
        assertTrue(LoginThrottle.isLocked("a@x.io", "1.1.1.1", t0 + 1))
        assertFalse(LoginThrottle.isLocked("a@x.io", "1.1.1.1", t0 + LoginThrottle.LOCK_MS + 1))
    }

    @Test
    fun aStrangerCannotLockTheRealUserOut() {
        LoginThrottle.reset()
        repeat(LoginThrottle.MAX_FAILURES) { LoginThrottle.recordFailure("a@x.io", "6.6.6.6", 1000) }
        assertTrue(LoginThrottle.isLocked("a@x.io", "6.6.6.6", 1001))
        assertFalse(LoginThrottle.isLocked("a@x.io", "1.1.1.1", 1001))
    }

    @Test
    fun throttleMemoryIsBounded() {
        LoginThrottle.reset()
        repeat(LoginThrottle.MAX_ENTRIES * 2) { LoginThrottle.recordFailure("u$it@x.io", "6.6.6.6", 1000) }
        assertTrue(LoginThrottle.size <= LoginThrottle.MAX_ENTRIES)
    }

    @Test
    fun loginIsRateLimitedPerClient() = withServer("ktor.performance.auth_rate_limit" to "3") {
        repeat(3) {
            assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "u$it@x.io", "password" to "wrongpw1").status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, postForm("/login", "userId" to "z@x.io", "password" to "wrongpw1").status)
        assertEquals(HttpStatusCode.TooManyRequests, postForm("/register", "email" to "z@x.io", "password" to "secret123").status)
    }

    @Test
    fun registrationCanBeClosed() = withServer("ktor.security.registration_open" to "false") {
        assertEquals(HttpStatusCode.Forbidden, postForm("/register", "email" to "a@x.io", "password" to "secret123").status)
    }

    @Test
    fun registrationIsClosedByDefaultOutsideDev() = withServer(
        "ktor.environment" to "prod",
        "ktor.security.session_sign_key" to "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    ) {
        assertEquals(HttpStatusCode.Forbidden, postForm("/register", "email" to "a@x.io", "password" to "secret123").status)
    }

    @Test
    fun sessionsExpireOnTheServer() {
        val now = 10L * SESSION_MAX_AGE_MS
        assertTrue(isSessionValid(ASSSession("a@x.io", now - 1000), now))
        assertFalse(isSessionValid(ASSSession("a@x.io", now - SESSION_MAX_AGE_MS - 1), now))
        // Cookies issued before expiry existed carry issuedAt = 0
        assertFalse(isSessionValid(ASSSession("a@x.io"), now))
        // Issued in the future
        assertFalse(isSessionValid(ASSSession("a@x.io", now + 60_000), now))
    }

    @Test
    fun oversizedBatchIsRejected() = withServer {
        val page = appWithPage(registerAndLogin("a@x.io")).second
        val views = (0..MAX_VIEWS_PER_REQUEST).joinToString(",") { viewJson(page) }
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/views", json = """{"pageViews":[$views]}""").status)
    }
}
