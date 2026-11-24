package com.ass

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordResetTest {

    private suspend fun ApplicationTestBuilder.forgot(email: String) =
        call(HttpMethod.Post, "/password/forgot", json = """{"email":"$email"}""")

    /** The token of the last reset email, once it's been sent (in the background). */
    private suspend fun ApplicationTestBuilder.lastToken(count: Int = 1): String {
        repeat(100) {
            if (sentMails().size >= count) return Regex("token=([A-Za-z0-9_-]+)").find(sentMails().last().body)!!.groupValues[1]
            delay(20)
        }
        error("no email sent")
    }

    @Test
    fun resetsThePasswordWithTheEmailedLink() = withServer {
        val session = registerAndLogin("a@x.io", "secret123")
        assertEquals(HttpStatusCode.NoContent, forgot("A@x.io").status)
        val token = lastToken()
        val mail = sentMails().single()
        assertEquals("a@x.io", mail.to)
        assertTrue(mail.body.contains("$DASHBOARD/#/reset?token=$token"))

        assertEquals(HttpStatusCode.BadRequest,
            call(HttpMethod.Post, "/password/reset", json = """{"token":"$token","new":"short"}""").status)
        assertEquals(HttpStatusCode.NoContent,
            call(HttpMethod.Post, "/password/reset", json = """{"token":"$token","new":"newsecret1"}""").status)

        // Signed out everywhere, new password only, link used up
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/account", session).status)
        assertEquals(HttpStatusCode.Unauthorized, postForm("/login", "userId" to "a@x.io", "password" to "secret123").status)
        assertEquals(HttpStatusCode.OK, postForm("/login", "userId" to "a@x.io", "password" to "newsecret1").status)
        assertEquals(HttpStatusCode.BadRequest,
            call(HttpMethod.Post, "/password/reset", json = """{"token":"$token","new":"another12"}""").status)
    }

    @Test
    fun doesNotRevealWhetherTheAccountExists() = withServer {
        registerAndLogin("a@x.io")
        assertEquals(HttpStatusCode.NoContent, forgot("nobody@x.io").status)
        delay(300)
        assertEquals(0, sentMails().size)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/password/forgot", json = "{}").status)
    }

    @Test
    fun refusesUnknownTokensAndCancelsOtherLinks() = withServer {
        registerAndLogin("a@x.io")
        assertEquals(HttpStatusCode.BadRequest,
            call(HttpMethod.Post, "/password/reset", json = """{"token":"${"x".repeat(43)}","new":"newsecret1"}""").status)

        forgot("a@x.io"); val first = lastToken(1)
        forgot("a@x.io"); val second = lastToken(2)
        assertEquals(HttpStatusCode.NoContent,
            call(HttpMethod.Post, "/password/reset", json = """{"token":"$second","new":"newsecret1"}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            call(HttpMethod.Post, "/password/reset", json = """{"token":"$first","new":"newsecret2"}""").status)
    }
}
