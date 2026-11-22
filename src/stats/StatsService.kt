package com.ass.stats

import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.db.Db
import com.ass.model.DayStat
import com.ass.model.PageStat
import com.ass.model.StatsOut
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

const val DAY_MS = 24L * 60 * 60 * 1000

/** Views older than this are considered corrupt and left out of the per-day series. */
const val MIN_VALID_TIMESTAMP = 100_000L

/** Largest time zone offset accepted (UTC+14 / UTC-12). */
const val MAX_TZ_OFFSET_MS = 14L * 60 * 60 * 1000

/**
 * Computes statistics with SQL aggregates, so the cost doesn't depend on shipping every row.
 * Results are cached for [cacheMs] per time zone: dashboards don't need second-level freshness,
 * and this turns a full-table aggregate per request into one per [cacheMs].
 */
class StatsService(private val db: Db, private val cacheMs: Long = 5_000) {
    private class Cached(val at: Long, val value: StatsOut)
    private val cache = ConcurrentHashMap<Long, Cached>()
    private val computeLock = Mutex()

    suspend fun compute(tzOffsetMs: Long = 0, now: Long = System.currentTimeMillis()): StatsOut {
        if (cacheMs <= 0) return computeUncached(tzOffsetMs)
        cache[tzOffsetMs]?.takeIf { now - it.at < cacheMs }?.let { return it.value }
        // One computation at a time: concurrent requests wait for it instead of all hitting the database
        return computeLock.withLock {
            cache[tzOffsetMs]?.takeIf { System.currentTimeMillis() - it.at < cacheMs }?.value
                ?: computeUncached(tzOffsetMs).also {
                    if (cache.size > 64) cache.clear()
                    cache[tzOffsetMs] = Cached(System.currentTimeMillis(), it)
                }
        }
    }

    /** Forget cached results (after a page is created or deleted). */
    fun invalidate() = cache.clear()

    private suspend fun computeUncached(tzOffsetMs: Long): StatsOut = db.query {
        val events = PagesViews.id.count()
        val sessions = PagesViews.sessionId.countDistinct()
        val totals = PagesViews.select(events, sessions).single()

        val views = PagesViews.page.count()
        val viewsPerPage = PagesViews.select(PagesViews.page, views)
            .groupBy(PagesViews.page)
            .associate { it[PagesViews.page].value to it[views] }
        val pages = Pages.selectAll().orderBy(Pages.name).map {
            val id = it[Pages.id].value
            PageStat(id, it[Pages.name], viewsPerPage[id] ?: 0)
        }

        // Literals (not bind parameters) so the database sees SELECT and GROUP BY as the same expression
        val day = PagesViews.timestamp.plus(longLiteral(tzOffsetMs)).div(longLiteral(DAY_MS))
        val dayEvents = PagesViews.id.count()
        val daySessions = PagesViews.sessionId.countDistinct()
        val days = PagesViews.select(day, dayEvents, daySessions)
            .where { PagesViews.timestamp greater MIN_VALID_TIMESTAMP }
            .groupBy(day)
            .orderBy(day to SortOrder.ASC)
            .map { DayStat(it[day], it[dayEvents], it[daySessions]) }

        StatsOut(totals[events], totals[sessions], pages, days)
    }
}
