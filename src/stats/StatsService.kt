package com.ass.stats

import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.db.Db
import com.ass.model.Bucket
import com.ass.model.PageStat
import com.ass.model.PeriodStats
import com.ass.model.Summary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.avg
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val DAY_MS = 24L * 60 * 60 * 1000

/** Largest time zone offset accepted (UTC+14 / UTC-12). */
const val MAX_TZ_OFFSET_MS = 14L * 60 * 60 * 1000

/** How far back the dashboard can page (in periods). */
const val MAX_PERIOD_OFFSET = 1000

/**
 * Per-application statistics over a period, computed with SQL aggregates on the
 * (app, timestamp) index; nothing proportional to the number of views or sessions leaves the
 * database. Results are cached for [cacheMs] per (app, period, offset, time zone).
 */
class StatsService(private val db: Db, private val cacheMs: Long = 5_000) {
    private data class Key(val app: UUID, val period: Period, val offset: Int, val tz: Long)
    private class Cached(val at: Long, val value: PeriodStats)
    private val cache = ConcurrentHashMap<Key, Cached>()
    private val computeLock = Mutex()

    suspend fun compute(app: UUID, period: Period, offset: Int = 0, tzOffsetMs: Long = 0): PeriodStats {
        if (cacheMs <= 0) return computeUncached(app, period, offset, tzOffsetMs)
        val key = Key(app, period, offset, tzOffsetMs)
        fun fresh() = cache[key]?.takeIf { System.currentTimeMillis() - it.at < cacheMs }?.value
        fresh()?.let { return it }
        // One computation at a time: concurrent requests wait for it instead of all hitting the database
        return computeLock.withLock {
            fresh() ?: computeUncached(app, period, offset, tzOffsetMs).also {
                if (cache.size > 5_000) cache.clear()
                cache[key] = Cached(System.currentTimeMillis(), it)
            }
        }
    }

    /** Forget cached results for [app] (after its pages change). */
    fun invalidate(app: UUID) = cache.keys.removeIf { it.app == app }

    private suspend fun computeUncached(app: UUID, period: Period, offset: Int, tz: Long): PeriodStats {
        val now = System.currentTimeMillis()
        val range = rangeOf(period, offset, tz, now)
        val previousRange = rangeOf(period, offset - 1, tz, now)
        return db.query {
            PeriodStats(
                period = period.name.lowercase(),
                granularity = period.granularity.name.lowercase(),
                offset = offset,
                from = range.from,
                to = range.to,
                current = summary(app, range),
                previous = summary(app, previousRange),
                buckets = buckets(app, range, period.granularity, tz),
                pages = pages(app, range)
            )
        }
    }

    private fun inRange(app: UUID, range: Range): Op<Boolean> =
        (PagesViews.app eq app) and (PagesViews.timestamp greaterEq range.from) and (PagesViews.timestamp less range.to)

    /**
     * Sessions of the range, one row each, as a subquery: start, duration and number of views.
     * A session belongs to the range when its views do (a session crossing a boundary counts
     * on both sides, with the views it had in each).
     */
    private fun JdbcTransaction.summary(app: UUID, range: Range): Summary {
        val views = PagesViews.id.count().alias("n")
        val duration = (PagesViews.timestamp.max() - PagesViews.timestamp.min()).alias("dur")
        val sessions = PagesViews.select(PagesViews.sessionId, views, duration)
            .where { inRange(app, range) }
            .groupBy(PagesViews.sessionId)
            .alias("s")

        val sessionCount = sessions[views].count()
        val totalViews = PagesViews.id.count()
        val avgDuration = sessions[duration].avg(0)
        val row = sessions.select(sessionCount, avgDuration).single()
        val viewCount = PagesViews.select(totalViews).where { inRange(app, range) }.single()[totalViews]
        val n = row[sessionCount]
        val bounces = if (n == 0L) 0L else
            sessions.select(sessionCount).where { sessions[views] eq 1L }.single()[sessionCount]

        return Summary(
            views = viewCount,
            sessions = n,
            pagesPerSession = if (n == 0L) 0.0 else viewCount.toDouble() / n,
            avgSessionMs = row[avgDuration]?.toLong() ?: 0,
            bounceRate = if (n == 0L) 0.0 else bounces.toDouble() / n
        )
    }

    /**
     * Views and sessions per bucket. SQL groups by hour or day (in the viewer's time zone);
     * months are summed from days. Sessions are counted in the slot where they started, so
     * slots add up exactly.
     */
    private fun JdbcTransaction.buckets(app: UUID, range: Range, granularity: Granularity, tz: Long): List<Bucket> {
        val unit = if (granularity == Granularity.HOUR) HOUR_MS else DAY_MS
        // Literals (not bind parameters) so the database sees SELECT and GROUP BY as the same expression
        fun slot(ts: org.jetbrains.exposed.v1.core.ExpressionWithColumnType<Long>) =
            ts.plus(longLiteral(tz)).div(longLiteral(unit))
        // Slot number -> its start instant (UTC ms)
        fun slotStart(slot: Long) = slot * unit - tz

        val views = LongArray(range.bucketStarts.size)
        val sessions = LongArray(range.bucketStarts.size)

        val viewSlot = slot(PagesViews.timestamp)
        val viewCount = PagesViews.id.count()
        PagesViews.select(viewSlot, viewCount).where { inRange(app, range) }.groupBy(viewSlot).forEach {
            val i = range.bucketIndex(slotStart(it[viewSlot]))
            if (i >= 0) views[i] += it[viewCount]
        }

        val startSlot = PagesViews.timestamp.min().plus(longLiteral(tz)).div(longLiteral(unit)).alias("slot")
        val perSession = PagesViews.select(PagesViews.sessionId, startSlot)
            .where { inRange(app, range) }
            .groupBy(PagesViews.sessionId)
            .alias("s")
        val sessionCount = perSession[startSlot].count()
        perSession.select(perSession[startSlot], sessionCount).groupBy(perSession[startSlot]).forEach {
            val slot = it[perSession[startSlot]] ?: return@forEach
            val i = range.bucketIndex(slotStart(slot))
            if (i >= 0) sessions[i] += it[sessionCount]
        }

        return range.bucketStarts.mapIndexed { i, start -> Bucket(start, views[i], sessions[i]) }
    }

    /** Every page of the application with its views and the sessions that saw it; busiest first. */
    private fun JdbcTransaction.pages(app: UUID, range: Range): List<PageStat> {
        val views = PagesViews.id.count()
        val sessions = PagesViews.sessionId.countDistinct()
        val perPage = PagesViews.select(PagesViews.page, views, sessions)
            .where { inRange(app, range) }
            .groupBy(PagesViews.page)
            .associate { it[PagesViews.page].value to (it[views] to it[sessions]) }
        return Pages.selectAll().where { Pages.app eq app }.map {
            val id = it[Pages.id].value
            val (v, s) = perPage[id] ?: (0L to 0L)
            PageStat(id, it[Pages.name], v, s)
        }.sortedWith(compareByDescending<PageStat> { it.views }.thenBy { it.name })
    }
}
