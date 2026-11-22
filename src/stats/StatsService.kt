package com.ass.stats

import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.db.Db
import com.ass.model.DayStat
import com.ass.model.PageStat
import com.ass.model.StatsOut
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val DAY_MS = 24L * 60 * 60 * 1000

/** Views older than this are considered corrupt and left out of the per-day series. */
const val MIN_VALID_TIMESTAMP = 100_000L

/** Largest time zone offset accepted (UTC+14 / UTC-12). */
const val MAX_TZ_OFFSET_MS = 14L * 60 * 60 * 1000

/**
 * Per-application statistics computed with SQL aggregates (served by the (app, timestamp) index).
 * Results are cached for [cacheMs] per application and time zone: dashboards don't need
 * second-level freshness, and this turns an aggregate per request into one per [cacheMs].
 */
class StatsService(private val db: Db, private val cacheMs: Long = 5_000) {
    private data class Key(val app: UUID, val tz: Long)
    private class Cached(val at: Long, val value: StatsOut)
    private val cache = ConcurrentHashMap<Key, Cached>()
    private val computeLock = Mutex()

    suspend fun compute(app: UUID, tzOffsetMs: Long = 0): StatsOut {
        if (cacheMs <= 0) return computeUncached(app, tzOffsetMs)
        val key = Key(app, tzOffsetMs)
        fun fresh() = cache[key]?.takeIf { System.currentTimeMillis() - it.at < cacheMs }?.value
        fresh()?.let { return it }
        // One computation at a time: concurrent requests wait for it instead of all hitting the database
        return computeLock.withLock {
            fresh() ?: computeUncached(app, tzOffsetMs).also {
                if (cache.size > 1_000) cache.clear()
                cache[key] = Cached(System.currentTimeMillis(), it)
            }
        }
    }

    /** Forget cached results for [app] (after its pages change). */
    fun invalidate(app: UUID) = cache.keys.removeIf { it.app == app }

    private suspend fun computeUncached(app: UUID, tzOffsetMs: Long): StatsOut = db.query {
        val ofApp = PagesViews.app eq app

        val events = PagesViews.id.count()
        val sessions = PagesViews.sessionId.countDistinct()
        val totals = PagesViews.select(events, sessions).where { ofApp }.single()

        val views = PagesViews.page.count()
        val viewsPerPage = PagesViews.select(PagesViews.page, views)
            .where { ofApp }
            .groupBy(PagesViews.page)
            .associate { it[PagesViews.page].value to it[views] }
        val pages = Pages.selectAll().where { Pages.app eq app }.orderBy(Pages.name).map {
            val id = it[Pages.id].value
            PageStat(id, it[Pages.name], viewsPerPage[id] ?: 0)
        }

        // Literals (not bind parameters) so the database sees SELECT and GROUP BY as the same expression
        val day = PagesViews.timestamp.plus(longLiteral(tzOffsetMs)).div(longLiteral(DAY_MS))
        val dayEvents = PagesViews.id.count()
        val daySessions = PagesViews.sessionId.countDistinct()
        val days = PagesViews.select(day, dayEvents, daySessions)
            .where { ofApp and (PagesViews.timestamp greater MIN_VALID_TIMESTAMP) }
            .groupBy(day)
            .orderBy(day to SortOrder.ASC)
            .map { DayStat(it[day], it[dayEvents], it[daySessions]) }

        StatsOut(totals[events], totals[sessions], pages, days)
    }
}
