package com.ass.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Database access: a HikariCP connection pool, plus a dispatcher with exactly as many threads
 * as pooled connections, so blocking JDBC calls never run on Netty's request threads.
 */
class Db(url: String, driver: String, poolSize: Int) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        driverClassName = driver
        maximumPoolSize = poolSize
        minimumIdle = 1
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
        validate()
    })

    val database: Database = Database.connect(dataSource)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(poolSize)

    /** Run [block] in a transaction on the database dispatcher. */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T =
        withContext(dispatcher) { transaction(database) { block() } }

    override fun close() = dataSource.close()
}
