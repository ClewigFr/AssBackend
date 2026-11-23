package com.ass.ingest

import java.util.concurrent.ConcurrentHashMap

/**
 * Max views accepted per client per minute (fixed one-minute windows), on top of the
 * per-request rate limit: otherwise a client could send a full batch on every allowed request.
 * In memory only, keyed by the client IP, never stored or logged. [limit] <= 0 disables it.
 */
class ViewBudget(private val limit: Int, private val windowMs: Long = 60_000) {
    private class Window(val start: Long, var used: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    /** Takes [count] views from [client]'s budget; false (nothing taken) when it would go over. */
    fun take(client: String, count: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (limit <= 0) return true
        if (windows.size > MAX_CLIENTS) windows.entries.removeIf { now - it.value.start >= windowMs }
        if (windows.size > MAX_CLIENTS) windows.clear()
        var ok = false
        windows.compute(client) { _, w ->
            val window = if (w == null || now - w.start >= windowMs) Window(now, 0) else w
            if (window.used + count <= limit) { window.used += count; ok = true }
            window
        }
        return ok
    }

    private companion object {
        const val MAX_CLIENTS = 100_000
    }
}
