package com.ass

import com.ass.auth.LoginThrottle
import com.ass.dao.userNameValid
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditFixesTest {

    @Test
    fun aFullThrottleTableKeepsActiveLocks() {
        LoginThrottle.reset()
        repeat(LoginThrottle.MAX_FAILURES) { LoginThrottle.recordFailure("victim@x.io", "6.6.6.6", 1000) }
        assertTrue(LoginThrottle.isLocked("victim@x.io", "6.6.6.6", 1001))
        // Flood the table with other accounts: the lock must survive
        repeat(LoginThrottle.MAX_ENTRIES * 2) { LoginThrottle.recordFailure("u$it@x.io", "7.7.7.7", 1002) }
        assertTrue(LoginThrottle.size <= LoginThrottle.MAX_ENTRIES)
        assertTrue(LoginThrottle.isLocked("victim@x.io", "6.6.6.6", 1003))
        LoginThrottle.reset()
    }

    @Test
    fun emailValidationRefusesAddressListsAndControlCharacters() {
        listOf("a@x.io", "1st.name+tag@sub.example.fr", "a_b-c@x.co").forEach { assertTrue(userNameValid(it), it) }
        listOf(
            "a@x.io, b@y.io", "a b@x.io", "<a@x.io>", "a@x.io\r\nBcc: b@y.io", "a@@x.io",
            "a@xio", "@x.io", "a@x.", "a\u0000@x.io", "\"a\"@x.io"
        ).forEach { assertFalse(userNameValid(it), it) }
    }

    @Test
    fun anUnknownResetTokenIsRefused() = withServer {
        assertEquals(HttpStatusCode.BadRequest,
            call(HttpMethod.Post, "/password/reset", json = """{"token":"${"y".repeat(43)}","new":"newsecret1"}""").status)
    }

    @Test
    fun viewsWithoutAppAreRefusedUnlessLegacyIsOn() = withServer("ktor.security.legacy_views" to "false") {
        val alice = registerAndLogin("a@x.io")
        val (app, page) = appWithPage(alice)
        assertEquals(HttpStatusCode.BadRequest,
            call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(page)}]}""").status)
        assertEquals(HttpStatusCode.OK,
            call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${viewJson(page)}]}""").status)
    }

    @Test
    fun legacyViewsStillWorkWhenAllowed() = withServer("ktor.security.legacy_views" to "true") {
        val alice = registerAndLogin("a@x.io")
        val (_, page) = appWithPage(alice)
        assertEquals(HttpStatusCode.OK,
            call(HttpMethod.Post, "/views", json = """{"pageViews":[${viewJson(page)}]}""").status)
    }
}
