package com.ass.ingest

import com.ass.dao.Pages
import com.ass.db.Db
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Known pages and the application each belongs to, kept in memory so /views never hits the
 * database to validate a page. A miss is checked once against the database (pages created on
 * another instance), and unknown ids are remembered for a short while so random ids can't
 * hammer the database.
 */
class PageCache(private val db: Db, private val negativeTtlMs: Long = 60_000) {
    /** page id -> app id (null for orphan legacy pages) */
    private val known = ConcurrentHashMap<UUID, Optional>()
    private val unknown = ConcurrentHashMap<UUID, Long>()

    /** Wrapper because ConcurrentHashMap can't hold null values. */
    private data class Optional(val app: UUID?)

    suspend fun load() {
        val pages = db.query { Pages.select(Pages.id, Pages.app).map { it[Pages.id].value to it[Pages.app]?.value } }
        pages.forEach { (page, app) -> known[page] = Optional(app) }
    }

    /** The existing pages among [ids], mapped to their application. */
    suspend fun appsOf(ids: Collection<UUID>, now: Long = System.currentTimeMillis()): Map<UUID, UUID?> {
        val result = HashMap<UUID, UUID?>()
        val toCheck = ArrayList<UUID>()
        for (id in ids) {
            val hit = known[id]
            when {
                hit != null -> result[id] = hit.app
                (unknown[id] ?: 0) > now -> Unit
                else -> toCheck += id
            }
        }
        if (toCheck.isNotEmpty()) {
            val found = db.query {
                Pages.select(Pages.id, Pages.app).where { Pages.id inList toCheck }
                    .associate { it[Pages.id].value to it[Pages.app]?.value }
            }
            found.forEach { (page, app) -> known[page] = Optional(app); unknown.remove(page) }
            if (unknown.size > MAX_UNKNOWN) unknown.clear()
            (toCheck - found.keys).forEach { unknown[it] = now + negativeTtlMs }
            result += found
        }
        return result
    }

    fun added(page: UUID, app: UUID) { known[page] = Optional(app); unknown.remove(page) }
    fun removed(page: UUID) { known.remove(page) }
    fun removedApp(app: UUID) { known.entries.removeIf { it.value.app == app } }

    private companion object {
        const val MAX_UNKNOWN = 10_000
    }
}
