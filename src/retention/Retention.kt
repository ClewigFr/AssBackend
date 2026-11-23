package com.ass.retention

import com.ass.dao.PagesDaily
import com.ass.dao.PagesViews
import com.ass.db.Db
import com.ass.stats.DAY_MS
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

/**
 * Keeps raw page views (with their session ids) for [days] days only. Older views are rolled up
 * into [PagesDaily] (views per page and per UTC day), then deleted, one day per transaction.
 * A day is moved atomically, so stats can always add both tables without counting a view twice.
 * [days] <= 0 keeps every raw view forever.
 */
class Retention(private val db: Db, val days: Int) {
    /** Called after views were moved (to drop cached stats). */
    var onChange: () -> Unit = {}

    private val log = LoggerFactory.getLogger(Retention::class.java)

    /** Start of the raw (detailed) data: views before it only exist as daily counts. Null if kept forever. */
    fun detailFrom(now: Long = System.currentTimeMillis()): Long? =
        if (days <= 0) null else Math.floorDiv(now, DAY_MS) * DAY_MS - days * DAY_MS

    /** Rolls up and deletes every raw view older than [detailFrom]; returns the number of days moved. */
    suspend fun run(now: Long = System.currentTimeMillis()): Int {
        val cutoff = detailFrom(now) ?: return 0
        var moved = 0
        var rows = 0L
        while (true) {
            val oldest = db.query {
                val min = PagesViews.timestamp.min()
                PagesViews.select(min).where { PagesViews.timestamp less cutoff }.single()[min]
            } ?: break
            val day = Math.floorDiv(oldest, DAY_MS) * DAY_MS
            rows += db.query { moveDay(day) }
            moved++
        }
        if (moved > 0) {
            log.info("Rolled up $rows page views older than $days days into daily counts ($moved days)")
            onChange()
        }
        return moved
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.moveDay(day: Long): Long {
        val end = day + DAY_MS
        val inDay = (PagesViews.timestamp greaterEq day) and (PagesViews.timestamp less end)
        // Views written while this runs (late uploads of old views) are left for the next run
        val maxIdExpr = PagesViews.id.max()
        val maxId = PagesViews.select(maxIdExpr).where { inDay }.single()[maxIdExpr] ?: return 0
        val count = PagesViews.id.count()
        var total = 0L
        PagesViews.select(PagesViews.page, PagesViews.app, count)
            .where { inDay and (PagesViews.id lessEq maxId.value) }
            .groupBy(PagesViews.page, PagesViews.app)
            .forEach { row ->
                val n = row[count]
                total += n
                // Views without an application (very old pages with no owner) are visible to nobody: just dropped
                val app = row[PagesViews.app] ?: return@forEach
                val page = row[PagesViews.page]
                val updated = PagesDaily.update({ (PagesDaily.page eq page) and (PagesDaily.day eq day) }) {
                    it[views] = views + n
                }
                if (updated == 0) PagesDaily.insert {
                    it[PagesDaily.page] = page
                    it[PagesDaily.app] = app
                    it[PagesDaily.day] = day
                    it[views] = n
                }
            }
        PagesViews.deleteWhere { inDay and (PagesViews.id lessEq maxId.value) }
        return total
    }
}
