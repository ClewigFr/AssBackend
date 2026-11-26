package com.ass.stats

import com.ass.dao.PagesViews
import com.ass.db.Db
import com.ass.model.FlowPage
import com.ass.model.FlowStats
import com.ass.model.FlowTransition
import com.ass.retention.Retention
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A transition must be seen in this many sessions to be listed (keeps single journeys out of the dashboard). */
const val FLOW_MIN_SESSIONS = 5

/**
 * Screen-to-screen flows: each raw view is paired with the previous and next view of its session
 * (SQL window functions, run in H2 and PostgreSQL), then only counts leave the database.
 * Cached like [StatsService].
 */
class FlowService(private val db: Db, private val cacheMs: Long = 5_000, private val retention: Retention? = null) {
    private data class Key(val app: UUID, val period: Period, val offset: Int, val tz: Long)
    private class Cached(val at: Long, val value: FlowStats)
    private val cache = ConcurrentHashMap<Key, Cached>()
    private val permits = Semaphore(1)

    suspend fun compute(app: UUID, period: Period, offset: Int = 0, tzOffsetMs: Long = 0): FlowStats {
        val key = Key(app, period, offset, tzOffsetMs)
        if (cacheMs > 0) cache[key]?.takeIf { System.currentTimeMillis() - it.at < cacheMs }?.let { return it.value }
        val now = System.currentTimeMillis()
        val range = rangeOf(period, offset, tzOffsetMs, now)
        val value = permits.withPermit { db.query { flows(app, range) } }.copy(detailFrom = retention?.detailFrom(now))
        if (cacheMs > 0) {
            if (cache.size > 5_000) cache.clear()
            cache[key] = Cached(System.currentTimeMillis(), value)
        }
        return value
    }

    fun invalidate(app: UUID) = cache.keys.removeIf { it.app == app }
    fun invalidateAll() = cache.clear()

    private fun JdbcTransaction.flows(app: UUID, range: Range): FlowStats {
        val t = identity(PagesViews)
        val sid = identity(PagesViews.sessionId)
        val page = identity(PagesViews.page)
        val ts = identity(PagesViews.timestamp)
        val id = identity(PagesViews.id)
        val appCol = identity(PagesViews.app)
        // Only typed values (UUID, Long) are inlined: no injection possible
        val window = "OVER (PARTITION BY $sid ORDER BY $ts, $id)"
        val seq = """
            SELECT $sid AS sid, $page AS pg, $ts AS ts,
                   LAG($page) $window AS prev, LAG($ts) $window AS prev_ts, LEAD($page) $window AS nxt
            FROM $t
            WHERE $appCol = '$app' AND $ts >= ${range.from} AND $ts < ${range.to}
        """.trimIndent()

        val sessions = exec("SELECT COUNT(DISTINCT sid) FROM ($seq) x") { rs -> if (rs.next()) rs.getLong(1) else 0L } ?: 0L

        fun pages(where: String) = exec("SELECT pg, COUNT(DISTINCT sid) FROM ($seq) x WHERE $where GROUP BY pg") { rs ->
            buildList { while (rs.next()) add(FlowPage(rs.getObject(1, UUID::class.java), rs.getLong(2))) }
        }.orEmpty().sortedByDescending { it.sessions }

        val all = exec(
            "SELECT prev, pg, COUNT(DISTINCT sid), COUNT(*), AVG(ts - prev_ts) FROM ($seq) x WHERE prev IS NOT NULL GROUP BY prev, pg"
        ) { rs ->
            buildList {
                while (rs.next()) add(
                    FlowTransition(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3), rs.getLong(4), rs.getDouble(5).toLong())
                )
            }
        }.orEmpty()
        val (shown, hidden) = all.partition { it.sessions >= FLOW_MIN_SESSIONS }

        return FlowStats(
            from = range.from,
            to = range.to,
            sessions = sessions,
            minSessions = FLOW_MIN_SESSIONS,
            entries = pages("prev IS NULL"),
            exits = pages("nxt IS NULL"),
            transitions = shown.sortedWith(compareByDescending<FlowTransition> { it.sessions }.thenByDescending { it.count }),
            hiddenSessions = hidden.sumOf { it.sessions }
        )
    }
}
