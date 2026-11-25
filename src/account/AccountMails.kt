package com.ass.account

import com.ass.dao.AccountNotices
import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesDaily
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.db.Db
import com.ass.mail.Mailer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * The emails sent to account owners, once each (recorded in [AccountNotices]):
 * - when the month's views reach 80 %, then 100 % of the quota (views keep being recorded);
 * - early in a month, a summary of the month before, for accounts that opted in.
 * [run] is called every hour; only verified accounts get emails.
 */
class AccountMails(private val db: Db, private val mailer: Mailer, private val quotas: Quotas, private val dashboardUrl: String) {
    private val log = LoggerFactory.getLogger("AccountMails")
    private val numbers: NumberFormat = NumberFormat.getIntegerInstance(Locale.FRANCE)

    private data class Account(val id: Int, val email: String, val createdAt: Long, val monthlyReport: Boolean)

    suspend fun run(now: Long = System.currentTimeMillis()) {
        val month = monthStart(now)
        val accounts = db.query {
            Users.selectAll().where { Users.emailVerified eq true }.map {
                Account(it[Users.id].value, it[Users.email], it[Users.createdAt], it[Users.monthlyReport])
            }
        }
        for (account in accounts) {
            try {
                quotaMail(account, month)
                if (account.monthlyReport) summaryMail(account, month)
            } catch (e: Exception) {
                log.error("Account email failed for account ${account.id}", e)
            }
        }
    }

    private suspend fun quotaMail(account: Account, month: Long) {
        val max = quotas.maxMonthlyViews
        if (max <= 0) return
        val views = db.query { viewsSince(org.jetbrains.exposed.v1.core.dao.id.EntityID(account.id, Users), month) }
        val kind = when {
            views >= max -> "quota100"
            views >= max * 8 / 10 -> "quota80"
            else -> return
        }
        // Reaching 100 % directly also marks 80 %: never an 80 % email after the 100 % one
        val kinds = if (kind == "quota100") listOf("quota80", "quota100") else listOf("quota80")
        if (!claim(account.id, month, kinds)) return
        val subject = if (kind == "quota100") "Quota de vues atteint ce mois-ci" else "80 % de votre quota de vues ce mois-ci"
        val body = """
            Bonjour,

            Vos applications ont reçu ${numbers.format(views)} vues depuis le début du mois, pour ${numbers.format(max)} incluses par mois.

            ${if (kind == "quota100") "Le quota est dépassé, mais rien n'est perdu : les vues continuent d'être enregistrées et comptées dans vos statistiques." else "Si le rythme continue, le quota sera dépassé avant la fin du mois. Les vues resteront enregistrées dans tous les cas."}

            Le compteur repart à zéro le 1er du mois (UTC). Le détail : $dashboardUrl/#/account
        """.trimIndent()
        mailer.send(account.email, subject, body)
    }

    private suspend fun summaryMail(account: Account, month: Long) {
        val from = monthStart(month - 1)
        // Only for months the account fully existed in
        if (account.createdAt >= from || !claim(account.id, from, listOf("summary"))) return
        val before = monthStart(from - 1)
        val apps = db.query {
            Apps.selectAll().where { Apps.owner eq account.id }.orderBy(Apps.name).map { it[Apps.id].value to it[Apps.name] }
                .map { (id, name) -> Triple(name, views(id, from, month), views(id, before, from)) to topPages(id, from, month) }
        }
        val monthName = Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).month.getDisplayName(TextStyle.FULL, Locale.FRANCE)
        val lines = if (apps.isEmpty()) listOf("Vous n'avez pas encore d'application.") else apps.flatMap { (totals, pages) ->
            val (name, views, previous) = totals
            val trend = when {
                previous == 0L -> ""
                else -> {
                    val change = (views - previous) * 100 / previous
                    " (${if (change >= 0) "+" else ""}$change % par rapport au mois précédent)"
                }
            }
            listOf("$name : ${numbers.format(views)} vues$trend") +
                pages.map { (page, n) -> "  - $page : ${numbers.format(n)}" } + ""
        }
        val body = buildString {
            appendLine("Bonjour,")
            appendLine()
            appendLine("Voici le bilan de $monthName pour vos applications.")
            appendLine()
            lines.forEach { appendLine(it) }
            appendLine("Toutes les statistiques : $dashboardUrl/#/apps")
            appendLine()
            append("Vous recevez ce bilan parce que vous l'avez demandé. Pour l'arrêter : $dashboardUrl/#/account")
        }
        mailer.send(account.email, "Votre bilan de $monthName", body)
    }

    /**
     * Records the notices; false when the last one was already recorded (its email was sent).
     * Only this hourly job writes them, one run at a time: no concurrent insert to guard against.
     */
    private suspend fun claim(user: Int, month: Long, kinds: List<String>): Boolean = db.query {
        val known = AccountNotices.select(AccountNotices.kind)
            .where { (AccountNotices.user eq user) and (AccountNotices.month eq month) }
            .map { it[AccountNotices.kind] }.toSet()
        if (kinds.last() in known) return@query false
        kinds.filter { it !in known }.forEach { kind ->
            AccountNotices.insert {
                it[AccountNotices.user] = user
                it[AccountNotices.month] = month
                it[AccountNotices.kind] = kind
            }
        }
        true
    }

    /** Views of [app] received in [from, to): raw views plus the daily counts already rolled up. */
    private fun JdbcTransaction.views(app: UUID, from: Long, to: Long): Long {
        val raw = PagesViews.select(PagesViews.id).where {
            (PagesViews.app eq app) and (PagesViews.timestamp greaterEq from) and (PagesViews.timestamp less to)
        }.count()
        val total = PagesDaily.views.sum()
        val daily = PagesDaily.select(total).where {
            (PagesDaily.app eq app) and (PagesDaily.day greaterEq from) and (PagesDaily.day less to)
        }.single()[total] ?: 0
        return raw + daily
    }

    /** The 5 most viewed pages of [app] in [from, to). */
    private fun JdbcTransaction.topPages(app: UUID, from: Long, to: Long): List<Pair<String, Long>> {
        val counts = HashMap<UUID, Long>()
        val n = PagesViews.id.count()
        PagesViews.select(PagesViews.page, n).where {
            (PagesViews.app eq app) and (PagesViews.timestamp greaterEq from) and (PagesViews.timestamp less to)
        }.groupBy(PagesViews.page).forEach { counts.merge(it[PagesViews.page].value, it[n], Long::plus) }
        val sum = PagesDaily.views.sum()
        PagesDaily.select(PagesDaily.page, sum).where {
            (PagesDaily.app eq app) and (PagesDaily.day greaterEq from) and (PagesDaily.day less to)
        }.groupBy(PagesDaily.page).forEach { counts.merge(it[PagesDaily.page].value, it[sum] ?: 0, Long::plus) }
        val top = counts.entries.sortedByDescending { it.value }.take(5)
        if (top.isEmpty()) return emptyList()
        val names = Pages.select(Pages.id, Pages.name).where { Pages.app eq app }.associate { it[Pages.id].value to it[Pages.name] }
        return top.map { (names[it.key] ?: "?") to it.value }
    }
}
