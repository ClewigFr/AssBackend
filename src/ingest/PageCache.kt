package com.ass.ingest

import com.ass.dao.Pages
import com.ass.db.Db
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Known page ids, kept in memory so /views never hits the database to validate a page.
 * A miss is checked once against the database (pages created on another instance), and
 * unknown ids are remembered for a short while so random ids can't hammer the database.
 */
class PageCache(private val db: Db, private val negativeTtlMs: Long = 60_000) {
    private val known: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val unknown = ConcurrentHashMap<UUID, Long>()

    suspend fun load() {
        val ids = db.query { Pages.select(Pages.id).map { it[Pages.id].value } }
        known.addAll(ids)
    }

    suspend fun filterExisting(ids: Collection<UUID>, now: Long = System.currentTimeMillis()): Set<UUID> {
        val result = HashSet<UUID>()
        val toCheck = ArrayList<UUID>()
        for (id in ids) {
            when {
                id in known -> result += id
                (unknown[id] ?: 0) > now -> Unit
                else -> toCheck += id
            }
        }
        if (toCheck.isNotEmpty()) {
            val found = db.query {
                toCheck.filter { id -> Pages.selectAll().where { Pages.id eq id }.limit(1).any() }
            }.toSet()
            found.forEach { known += it; unknown.remove(it) }
            if (unknown.size > MAX_UNKNOWN) unknown.clear()
            (toCheck - found).forEach { unknown[it] = now + negativeTtlMs }
            result += found
        }
        return result
    }

    fun added(id: UUID) { known += id; unknown.remove(id) }
    fun removed(id: UUID) { known -= id }

    private companion object {
        const val MAX_UNKNOWN = 10_000
    }
}
