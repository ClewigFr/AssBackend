package com.ass.ingest

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps one source from inflating an application's stats. Views over a limit are dropped
 * silently (the client is told they're accepted or rejected, never to retry), per:
 * - client IP and application: [perIpAppHour] views per hour. Mobile carriers share one IP
 *   between many users, so it stays well above what one person produces;
 * - session: [perSession] views per session id and day. A real session is one app launch.
 *
 * In memory only: IPs and session ids are never stored or logged. Dropped views are counted
 * per application and hour ([takeDropped]) so they can be reported. A limit <= 0 disables it.
 */
class ViewGuard(
    private val perIpAppHour: Int = 2000,
    private val perSession: Int = 500,
    private val maxEntries: Int = 200_000
) {
    private class Window(val start: Long, var used: Int)
    private val ipApp = ConcurrentHashMap<String, Window>()
    private val sessions = ConcurrentHashMap<UUID, Window>()
    private val dropped = ConcurrentHashMap<Pair<UUID, Long>, AtomicLong>()

    /** The [rows] from [client] within the limits; the others are counted as dropped. */
    fun filter(client: String, rows: List<ViewRow>, now: Long = System.currentTimeMillis()): List<ViewRow> {
        if (perIpAppHour <= 0 && perSession <= 0) return rows
        prune(now)
        val hour = now / HOUR_MS * HOUR_MS
        // Identical views are common in a flood: count the dropped ones one by one, never by equality
        return rows.filter { row ->
            val ok = (perSession <= 0 || take(sessions, row.sessionId, DAY_MS, perSession, now)) &&
                (perIpAppHour <= 0 || take(ipApp, "$client|${row.app}", HOUR_MS, perIpAppHour, now))
            if (!ok && row.app != null) dropped.computeIfAbsent(row.app to hour) { AtomicLong() }.incrementAndGet()
            ok
        }
    }

    /** Dropped views since the last call, per (application, start of the hour). */
    fun takeDropped(): Map<Pair<UUID, Long>, Long> {
        val out = HashMap<Pair<UUID, Long>, Long>()
        dropped.keys.toList().forEach { key -> dropped.remove(key)?.get()?.takeIf { it > 0 }?.let { out[key] = it } }
        return out
    }

    private fun <K : Any> take(map: ConcurrentHashMap<K, Window>, key: K, windowMs: Long, limit: Int, now: Long): Boolean {
        var ok = false
        map.compute(key) { _, w ->
            val window = if (w == null || now - w.start >= windowMs) Window(now, 0) else w
            if (window.used < limit) { window.used++; ok = true }
            window
        }
        return ok
    }

    private fun prune(now: Long) {
        if (ipApp.size > maxEntries) ipApp.entries.removeIf { now - it.value.start >= HOUR_MS }
        if (ipApp.size > maxEntries) ipApp.clear()
        if (sessions.size > maxEntries) sessions.entries.removeIf { now - it.value.start >= DAY_MS }
        if (sessions.size > maxEntries) sessions.clear()
    }

    private companion object {
        const val HOUR_MS = 60L * 60 * 1000
        const val DAY_MS = 24 * HOUR_MS
    }
}

