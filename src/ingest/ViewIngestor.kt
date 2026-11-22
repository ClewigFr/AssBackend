package com.ass.ingest

import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.db.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class ViewRow(val page: UUID, val sessionId: UUID, val timestamp: Long)

/**
 * Buffers accepted page views in memory and writes them in batches (one INSERT per batch),
 * so a request to /views costs no database round trip.
 *
 * Trade-off: views still in the buffer (at most [flushIntervalMs] worth) are lost if the
 * process is killed. On a normal shutdown, [close] writes everything first.
 */
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
        while (true) {
            val first = channel.receiveCatching().getOrNull() ?: break
            buffer += first
            withTimeoutOrNull(flushIntervalMs) {
                while (buffer.size < batchSize) {
                    buffer += channel.receiveCatching().getOrNull() ?: break
                }
            }
            // Take whatever else is already waiting, without blocking
            while (buffer.size < batchSize) buffer += channel.tryReceive().getOrNull() ?: break
            write(buffer)
            pending.addAndGet(-buffer.size)
            buffer.clear()
        }
    }

    private suspend fun write(rows: List<ViewRow>) {
        try {
            insert(rows)
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
            } catch (e2: Exception) {
                log.error("Failed to write ${rows.size} page views", e2)
            }
        }
    }

    private suspend fun insert(rows: List<ViewRow>) = db.query {
        PagesViews.batchInsert(rows, ignore = false, shouldReturnGeneratedValues = false) { row ->
            this[PagesViews.page] = EntityID(row.page, Pages)
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
        channel.close()
        job.join()
    }
}
