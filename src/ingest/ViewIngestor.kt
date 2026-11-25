package com.ass.ingest

import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.db.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class ViewRow(val page: UUID, val app: UUID?, val sessionId: UUID, val timestamp: Long)


/**
 * Buffers accepted page views in memory and writes them in batches (one INSERT per batch),
 * so a request to /views costs no database round trip.
 *
 * Trade-off: views still in the buffer (at most [flushIntervalMs] worth) are lost if the
 * process is killed. On a normal shutdown, [close] writes everything first.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ViewIngestor(
    private val db: Db,
    scope: CoroutineScope,
    private val batchSize: Int = 5_000,
    private val flushIntervalMs: Long = 200,
    private val capacity: Int = 500_000
) {
    private val log = LoggerFactory.getLogger(ViewIngestor::class.java)
    private val channel = Channel<ViewRow>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    @Volatile private var closing = false
    private val job: Job = scope.launch { run() }

    /** Number of views accepted but not yet written. */
    val backlog: Int get() = pending.get()

    /**
     * Queue [rows]. Returns false (nothing queued) when the buffer is full: the caller should
     * answer 503 so clients retry later instead of piling up memory.
     */
    fun offer(rows: List<ViewRow>): Boolean {
        if (rows.isEmpty()) return true
        if (pending.addAndGet(rows.size) > capacity) {
            pending.addAndGet(-rows.size)
            return false
        }
        rows.forEach { channel.trySend(it) }
        return true
    }

    private suspend fun run() {
        val buffer = ArrayList<ViewRow>(batchSize)
        var open = true
        while (open) {
            val first = channel.receiveCatching()
            if (first.isClosed) break
            buffer += first.getOrThrow()
            // Collect more until the batch is full or flushIntervalMs has passed. Don't wrap receive()
            // in withTimeout: a timeout racing with a delivery can cancel receive() after it took an
            // element, losing it. select picks exactly one of "an element" or "timeout", atomically.
            val deadline = System.nanoTime() + flushIntervalMs * 1_000_000
            while (buffer.size < batchSize) {
                val ready = channel.tryReceive()
                if (ready.isSuccess) { buffer += ready.getOrThrow(); continue }
                if (ready.isClosed) { open = false; break }
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) break
                val received = select<Boolean> {
                    channel.onReceiveCatching { result ->
                        result.getOrNull()?.let { buffer += it; true } ?: run { open = false; false }
                    }
                    onTimeout(remainingMs) { false }
                }
                if (!received) break
            }
            write(buffer)
            pending.addAndGet(-buffer.size)
            buffer.clear()
        }
    }

    private companion object {
        const val MIN_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
    }

    /**
     * Writes [rows], retrying with a backoff while the database fails: the clients were told the
     * views are accepted and won't send them again. Meanwhile the rows stay counted in [pending],
     * so a long outage fills the buffer and /views answers 503 (clients retry later) instead of
     * views being lost. Only on shutdown are they given up.
     */
    private suspend fun write(rows: List<ViewRow>) {
        var backoffMs = MIN_RETRY_MS
        while (true) {
            try {
                insert(rows)
                return
            } catch (e: Exception) {
                // Most likely a page deleted while its views were buffered: keep the rows whose page still exists
                try {
                    val existing = db.query {
                        Pages.select(Pages.id).where { Pages.id inList rows.map { it.page }.distinct() }
                            .map { it[Pages.id].value }.toSet()
                    }
                    val kept = rows.filter { it.page in existing }
                    if (kept.isNotEmpty()) insert(kept)
                    if (kept.size < rows.size) log.info("Dropped ${rows.size - kept.size} views for deleted pages")
                    return
                } catch (e2: Exception) {
                    if (closing) {
                        log.error("Failed to write ${rows.size} page views on shutdown, they are lost", e2)
                        return
                    }
                    log.warn("Failed to write ${rows.size} page views, retrying in ${backoffMs / 1000} s: {}", e2.toString())
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
        }
    }

    private suspend fun insert(rows: List<ViewRow>) = db.query {
        PagesViews.batchInsert(rows, ignore = false, shouldReturnGeneratedValues = false) { row ->
            this[PagesViews.page] = EntityID(row.page, Pages)
            this[PagesViews.app] = row.app?.let { EntityID(it, Apps) }
            this[PagesViews.sessionId] = row.sessionId
            this[PagesViews.timestamp] = row.timestamp
        }
    }

    /** Wait until everything accepted so far is written (tests, shutdown). */
    suspend fun awaitIdle() {
        while (pending.get() > 0) delay(5)
    }

    /** Stop accepting views and write what's buffered. */
    suspend fun close() {
        closing = true
        channel.close()
        job.join()
    }
}
