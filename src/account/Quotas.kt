package com.ass.account

import com.ass.dao.Apps
import com.ass.dao.PagesDaily
import com.ass.dao.PagesViews
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import java.time.ZoneOffset

/**
 * Limits of every account. Applications and pages are hard limits (creation answers 403);
 * monthly views are a soft one: views keep being recorded, the dashboard only shows an alert.
 * 0 means no limit.
 */
data class Quotas(val maxApps: Int = 5, val maxPagesPerApp: Int = 30, val maxMonthlyViews: Long = 100_000) {
    fun appsFull(apps: Long) = maxApps > 0 && apps >= maxApps
    /** Pages that can still be added to an application holding [pages]. */
    fun pagesLeft(pages: Long) = if (maxPagesPerApp > 0) (maxPagesPerApp - pages).coerceAtLeast(0) else Long.MAX_VALUE
}

/** Start of the current calendar month, in UTC (epoch ms). */
fun monthStart(now: Long = System.currentTimeMillis()): Long =
    Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * Views received by all the applications of [owner] since [from]: raw views, plus the daily
 * counts of days already rolled up by the retention job (never both for the same view).
 */
fun JdbcTransaction.viewsSince(owner: EntityID<Int>, from: Long): Long {
    val ownedApps = Apps.select(Apps.id).where { Apps.owner eq owner }
    val raw = PagesViews.select(PagesViews.id).where {
        (PagesViews.app inSubQuery ownedApps) and (PagesViews.timestamp greaterEq from)
    }.count()
    val total = PagesDaily.views.sum()
    val daily = PagesDaily.select(total).where {
        (PagesDaily.app inSubQuery ownedApps) and (PagesDaily.day greaterEq from)
    }.single()[total] ?: 0
    return raw + daily
}
