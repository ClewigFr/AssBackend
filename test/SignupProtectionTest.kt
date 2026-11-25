package com.ass

import com.ass.auth.clientKeyOf
import com.ass.signup.SignupGuard
import com.ass.signup.TurnstileVerifier
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignupProtectionTest {

    private suspend fun ApplicationTestBuilder.formToken() =
        Regex("\"formToken\":\"([^\"]+)\"").find(call(HttpMethod.Get, "/register/form").bodyAsText())!!.groupValues[1]

    private suspend fun ApplicationTestBuilder.mailToken(count: Int = 1): String {
        repeat(100) {
            if (sentMails().size >= count) return Regex("token=([A-Za-z0-9_-]+)").find(sentMails().last().body)!!.groupValues[1]
            delay(20)
        }
        error("no email sent")
    }

    @Test
    fun formTokenTooFastOrHoneypotGetAFakeSuccess() = withServer("ktor.security.signup_min_seconds" to "60") {
        val token = formToken()
        val tooFast = postForm("/register", "email" to "a@x.io", "password" to "secret123", "formToken" to token)
        assertEquals(HttpStatusCode.Created, tooFast.status)
        assertNull(sessionCookie(tooFast))
        assertEquals(HttpStatusCode.BadRequest, postForm("/register", "email" to "a@x.io", "password" to "secret123").status)
        assertEquals(HttpStatusCode.BadRequest, postForm("/register", "email" to "a@x.io", "password" to "secret123", "formToken" to "1.abc").status)
        // Nothing was created
        assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
    }

    @Test
    fun honeypotIsCheckedEvenWithoutTheDelay() = withServer {
        val bot = postForm("/register", "email" to "a@x.io", "password" to "secret123", "website" to "http://spam")
        assertEquals(HttpStatusCode.Created, bot.status)
        assertNull(sessionCookie(bot))
        assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
    }

    @Test
    fun guardChecksAgeAndSignature() {
        val guard = SignupGuard(minSeconds = 3, maxPerHour = 2)
        val token = guard.formToken(now = 1_000_000)
        assertEquals(SignupGuard.Check.BOT, guard.check(token, null, now = 1_001_000))
        assertEquals(SignupGuard.Check.OK, guard.check(token, null, now = 1_004_000))
        assertEquals(SignupGuard.Check.INVALID, guard.check(token, null, now = 1_000_000 + SignupGuard.MAX_FORM_AGE_MS + 1))
        assertEquals(SignupGuard.Check.INVALID, guard.check("1000000.deadbeef", null, now = 1_004_000))
        assertEquals(SignupGuard.Check.INVALID, SignupGuard(3, 0).check(token, null, now = 1_004_000))
        assertEquals(SignupGuard.Check.BOT, guard.check(token, "x", now = 1_004_000))
        assertTrue(guard.takeSignup(0)); assertTrue(guard.takeSignup(1)); assertFalse(guard.takeSignup(2))
        assertTrue(guard.takeSignup(3_600_000))
    }

    @Test
    fun hourlySignupCap() = withServer("ktor.security.max_signups_per_hour" to "1") {
        registerAndLogin("a@x.io")
        assertEquals(HttpStatusCode.TooManyRequests, postForm("/register", "email" to "b@x.io", "password" to "secret123").status)
    }

    @Test
    fun turnstileTokenIsRequiredWhenConfigured() = withServer("ktor.security.turnstile_secret" to "s", "ktor.security.turnstile_site_key" to "site") {
        assertTrue(call(HttpMethod.Get, "/register/form").bodyAsText().contains("\"turnstileSiteKey\":\"site\""))
        // No token: refused without calling Cloudflare
        assertEquals(HttpStatusCode.BadRequest, postForm("/register", "email" to "a@x.io", "password" to "secret123").status)
        assertTrue(TurnstileVerifier.parseSuccess("""{"success":true,"error-codes":[]}"""))
        assertFalse(TurnstileVerifier.parseSuccess("""{"success":false}"""))
        assertFalse(TurnstileVerifier.parseSuccess("nope"))
    }

    @Test
    fun newAccountsMustVerifyTheirEmail() = withServer("ktor.security.email_verification" to "true") {
        val created = postForm("/register", "email" to "a@x.io", "password" to "secret123")
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("Check your email", created.bodyAsText())
        assertNull(sessionCookie(created))
        val token = mailToken()
        assertTrue(sentMails().last().body.contains("$DASHBOARD/#/verify?token=$token"))

        // Can't sign in yet; the right password sends the link again
        assertEquals(HttpStatusCode.Forbidden, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
        val again = mailToken(2)
        val verified = call(HttpMethod.Post, "/register/verify", json = """{"token":"$again"}""")
        assertEquals(HttpStatusCode.OK, verified.status)
        // Opening one link cancels the other pending ones
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/register/verify", json = """{"token":"$token"}""").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/account", sessionCookie(verified)).status)
        assertEquals(HttpStatusCode.OK, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
    }

    @Test
    fun duplicateSignupIsNotRevealedWithVerification() = withServer("ktor.security.email_verification" to "true") {
        postForm("/register", "email" to "a@x.io", "password" to "secret123")
        val dup = postForm("/register", "email" to "A@x.io", "password" to "other1234")
        assertEquals(HttpStatusCode.Created, dup.status)
        assertEquals("Check your email", dup.bodyAsText())
    }

    @Test
    fun signupBeforeTheOwnerCantKeepItsPassword() = withServer("ktor.security.email_verification" to "true") {
        // Someone registers the address first with their own password, then its owner signs up
        postForm("/register", "email" to "a@x.io", "password" to "attacker1")
        mailToken()
        assertEquals(HttpStatusCode.Created, postForm("/register", "email" to "a@x.io", "password" to "owner1234").status)
        val ownerLink = mailToken(2)

        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/register/verify", json = """{"token":"$ownerLink"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "a@x.io", "password" to "attacker1").status)
        assertEquals(HttpStatusCode.OK, postForm("/login", "userId" to "a@x.io", "password" to "owner1234").status)
    }

    @Test
    fun unverifiedAccountsArePurgedAfterADay() = withServer("ktor.security.email_verification" to "true") {
        postForm("/register", "email" to "a@x.io", "password" to "secret123")
        mailToken()
        emailVerification().purge(now = System.currentTimeMillis() + 25 * 3_600_000)
        assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
    }

    @Test
    fun ipv6IsKeyedByItsSlash64() {
        assertEquals("1.2.3.4", clientKeyOf("1.2.3.4"))
        assertEquals("2001:db8:0:1::/64", clientKeyOf("2001:0db8:0000:0001:aaaa:bbbb:cccc:dddd"))
        assertEquals("2001:db8:0:1::/64", clientKeyOf("[2001:db8:0:1::42]"))
        assertEquals("0:0:0:0::/64", clientKeyOf("::1"))
        assertEquals("fe80:0:0:0::/64", clientKeyOf("fe80::1%eth0"))
        assertEquals("not-an-ip", clientKeyOf("not-an-ip"))
    }
}
