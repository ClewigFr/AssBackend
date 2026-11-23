package com.ass.stats

import com.ass.dao.Pages
import com.ass.dao.PagesDaily
import com.ass.dao.PagesViews
import com.ass.db.Db
import com.ass.model.Bucket
import com.ass.model.PageStat
import com.ass.model.PeriodStats
import com.ass.model.Summary
import com.ass.retention.Retention
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import org.jetbrains.exposed.v1.core.sum
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
/** Stats computed at the same time, at most (each holds a database connection). */
private const val MAX_CONCURRENT = 2

class StatsService(private val db: Db, private val cacheMs: Long = 5_000, private val retention: Retention? = null) {
    private data class Key(val app: UUID, val period: Period, val offset: Int, val tz: Long)
    private class Cached(val at: Long, val value: PeriodStats)
    private val cache = ConcurrentHashMap<Key, Cached>()
    /** Computation in progress per key: concurrent requests for the same stats share it. */
    private val inFlight = ConcurrentHashMap<Key, CompletableDeferred<PeriodStats>>()
    /** Bounds the database work of stats, without one slow request blocking every other key. */
    private val permits = Semaphore(MAX_CONCURRENT)

    suspend fun compute(app: UUID, period: Period, offset: Int = 0, tzOffsetMs: Long = 0): PeriodStats {
        if (cacheMs <= 0) return computeUncached(app, period, offset, tzOffsetMs)
        val key = Key(app, period, offset, tzOffsetMs)
        fun fresh() = cache[key]?.takeIf { System.currentTimeMillis() - it.at < cacheMs }?.value
        fresh()?.let { return it }
        // Concurrent requests for the same key wait for one computation instead of all hitting the database
        val mine = CompletableDeferred<PeriodStats>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return running.await()
        try {
            val value = fresh() ?: permits.withPermit { computeUncached(app, period, offset, tzOffsetMs) }.also {
                if (cache.size > 5_000) cache.clear()
                cache[key] = Cached(System.currentTimeMillis(), it)
            }
            mine.complete(value)
            return value
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /** Forget cached results for [app] (after its pages change). */
    fun invalidate(app: UUID) = cache.keys.removeIf { it.app == app }

    /** Forget every cached result (after old views were rolled up). */
    fun invalidateAll() = cache.clear()

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
                current = summary(app, range, tz),
                previous = summary(app, previousRange, tz),
                buckets = buckets(app, range, period.granularity, tz),
                pages = pages(app, range, tz),
                detailFrom = retention?.detailFrom(now)
            )
        }
    }

    private fun inRange(app: UUID, range: Range): Op<Boolean> =
        (PagesViews.app eq app) and (PagesViews.timestamp greaterEq range.from) and (PagesViews.timestamp less range.to)

    /**
     * Daily counts (views older than the retention period) of the days whose date, in the viewer's
     * time zone, falls in the range. A daily row covers a UTC day, so it's placed on the same date.
     */
    private fun dailyInRange(app: UUID, range: Range, tz: Long): Op<Boolean> =
        (PagesDaily.app eq app) and (PagesDaily.day greaterEq range.from + tz) and (PagesDaily.day less range.to + tz)

    private fun JdbcTransaction.dailyViews(app: UUID, range: Range, tz: Long): Long {
        val sum = PagesDaily.views.sum()
        return PagesDaily.select(sum).where { dailyInRange(app, range, tz) }.single()[sum] ?: 0
    }

    /**
     * Sessions of the range, one row each, as a subquery: start, duration and number of views.
     * A session belongs to the range when its views do (a session crossing a boundary counts
     * on both sides, with the views it had in each).
     */
    private fun JdbcTransaction.summary(app: UUID, range: Range, tz: Long): Summary {
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

        // Session figures only use raw views; daily counts (older data) only add to the views
        return Summary(
            views = viewCount + dailyViews(app, range, tz),
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

        // Daily counts only fit day and month slots: in a single day (hour slots) they're only in the summary
        if (granularity != Granularity.HOUR) {
            val sum = PagesDaily.views.sum()
            PagesDaily.select(PagesDaily.day, sum).where { dailyInRange(app, range, tz) }.groupBy(PagesDaily.day).forEach {
                val i = range.bucketIndex(it[PagesDaily.day] - tz)
                if (i >= 0) views[i] += it[sum] ?: 0
            }
        }

        return range.bucketStarts.mapIndexed { i, start -> Bucket(start, views[i], sessions[i]) }
    }

    /** Every page of the application with its views and the sessions that saw it; busiest first. */
    private fun JdbcTransaction.pages(app: UUID, range: Range, tz: Long): List<PageStat> {
        val views = PagesViews.id.count()
        val sessions = PagesViews.sessionId.countDistinct()
        val perPage = PagesViews.select(PagesViews.page, views, sessions)
            .where { inRange(app, range) }
            .groupBy(PagesViews.page)
            .associate { it[PagesViews.page].value to (it[views] to it[sessions]) }
        val dailySum = PagesDaily.views.sum()
        val dailyPerPage = PagesDaily.select(PagesDaily.page, dailySum).where { dailyInRange(app, range, tz) }
            .groupBy(PagesDaily.page)
            .associate { it[PagesDaily.page].value to (it[dailySum] ?: 0L) }
        return Pages.selectAll().where { Pages.app eq app }.map {
            val id = it[Pages.id].value
            val (v, s) = perPage[id] ?: (0L to 0L)
            PageStat(id, it[Pages.name], v + (dailyPerPage[id] ?: 0L), s)
        }.sortedWith(compareByDescending<PageStat> { it.views }.thenBy { it.name })
    }
}
