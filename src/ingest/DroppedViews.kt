package com.ass.ingest

import com.ass.dao.ViewsDropped
import com.ass.db.Db
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ViewGuard")

/** Adds the views [guard] dropped since the last call to the viewsdropped table (read by the monitoring). */
suspend fun saveDropped(db: Db, guard: ViewGuard) {
    val counts = guard.takeDropped()
    if (counts.isEmpty()) return
    db.query {
        counts.forEach { (key, n) ->
            val (app, hour) = key
            val updated = ViewsDropped.update({ (ViewsDropped.app eq app) and (ViewsDropped.hour eq hour) }) {
                it[views] = views + n
            }
            if (updated == 0) ViewsDropped.insertIgnore {
                it[ViewsDropped.app] = app
                it[ViewsDropped.hour] = hour
                it[views] = n
            }
        }
    }
    log.warn("Dropped {} views over the flood limits", counts.values.sum())
}
