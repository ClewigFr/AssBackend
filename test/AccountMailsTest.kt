package com.ass

import com.ass.account.monthStart
import com.ass.model.AccountOut
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountMailsTest {

    private suspend fun ApplicationTestBuilder.sendViews(app: String, page: String, count: Int) {
        call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${(1..count).joinToString(",") { viewJson(page) }}]}""")
        awaitIngested()
    }

    @Test
    fun warnsAt80And100PercentOfTheQuotaOnce() = withServer("ktor.quotas.max_monthly_views" to "10") {
        val alice = registerAndLogin("a@x.io")
        val (app, page) = appWithPage(alice)
        sentMails().clear()

        sendViews(app, page, 7)
        accountMails().run()
        assertEquals(0, sentMails().size)

        sendViews(app, page, 1)
        accountMails().run()
        accountMails().run()
        assertEquals(listOf("80 % de votre quota de vues ce mois-ci"), sentMails().map { it.subject })

        sendViews(app, page, 2)
        accountMails().run()
        accountMails().run()
        assertEquals("Quota de vues atteint ce mois-ci", sentMails().last().subject)
        assertEquals(2, sentMails().size)
        assertTrue(sentMails().last().body.contains("10 vues"))
    }

    @Test
    fun sendsTheMonthlySummaryOnlyWhenAskedFor() = withServer {
        val alice = registerAndLogin("a@x.io")
        val (app, page) = appWithPage(alice)
        sendViews(app, page, 3)
        sentMails().clear()
        val nextMonth = monthStart() + 32L * 24 * 3600 * 1000

        accountMails().run(nextMonth)
        assertEquals(0, sentMails().size)

        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Patch, "/account", alice, json = """{"monthlyReport":true}""").status)
        assertTrue(Json.decodeFromString<AccountOut>(call(HttpMethod.Get, "/account", alice).bodyAsText()).monthlyReport)
        accountMails().run(nextMonth)
        accountMails().run(nextMonth)
        val summary = sentMails().single()
        assertTrue(summary.subject.startsWith("Votre bilan de "))
        assertTrue(summary.body.contains("My app : 3 vues"), summary.body)
        assertTrue(summary.body.contains("  - home : 3"), summary.body)
    }
}
