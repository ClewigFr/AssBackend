package com.ass

import com.ass.model.AppsOut
import com.ass.model.PeriodStats
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import java.sql.DriverManager
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same flows on a real PostgreSQL, which is stricter than H2 (GROUP BY, identifiers,
 * types) and is what production runs; plus the startup migration from the legacy schema.
 */
class PostgresTest {
    companion object {
        private val pg: EmbeddedPostgres by lazy { EmbeddedPostgres.builder().start() }

        @JvmStatic
        @AfterClass
        fun stop() = pg.close()

        /** A fresh, empty database. */
        fun newDatabase(): String {
            val name = "t_" + UUID.randomUUID().toString().replace("-", "")
            pg.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
            return pg.getJdbcUrl("postgres", name)
        }

        fun sql(url: String, vararg statements: String) = DriverManager.getConnection(url).use { c ->
            statements.forEach { c.createStatement().execute(it) }
        }

        fun <T> query(url: String, sql: String, read: (java.sql.ResultSet) -> T): T =
            DriverManager.getConnection(url).use { c -> c.createStatement().executeQuery(sql).use(read) }

        private fun legacyHash(password: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(io.ktor.util.hex("6819b57a326945c1968f45236589"), "HmacSHA1"))
            return io.ktor.util.hex(mac.doFinal(password.toByteArray()))
        }
    }

    @Test
    fun fullFlowOnAFreshDatabase() = withServer("ktor.deployment.database_url" to newDatabase()) {
        val alice = registerAndLogin("alice@x.io")
        val app = createApp(alice, "Shop")
        val home = createPage(alice, app, "home")
        val cart = createPage(alice, app, "cart")
        val s1 = "11111111-1111-4111-8111-111111111111"
        val views = listOf(viewJson(home, 600_000, s1), viewJson(cart, 1_000, s1), viewJson(home, 2_000)).joinToString(",")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[$views]}""").status)
        awaitIngested()

        for (period in listOf("day", "week", "month", "year")) {
            val stats = Json.decodeFromString<PeriodStats>(call(HttpMethod.Get, "/apps/$app/stats?period=$period&tz=60", alice).bodyAsText())
            assertEquals(3, stats.buckets.sumOf { it.views }, period)
            assertEquals(stats.current.sessions, stats.buckets.sumOf { it.sessions }, period)
        }
        val week = Json.decodeFromString<PeriodStats>(call(HttpMethod.Get, "/apps/$app/stats?period=week", alice).bodyAsText())
        assertEquals(3, week.current.views)
        assertEquals(2, week.current.sessions)
        assertEquals(0.5, week.current.bounceRate)
        assertEquals(listOf("home" to 2L, "cart" to 1L), week.pages.map { it.name to it.views })

        val flows = Json.decodeFromString<com.ass.model.FlowStats>(call(HttpMethod.Get, "/apps/$app/flows?period=week&tz=60", alice).bodyAsText())
        assertEquals(2, flows.sessions)
        assertEquals(1, flows.hiddenSessions)
        assertEquals(2, flows.entries.sumOf { it.sessions })

        // Retention: views older than 90 days become daily counts, twice on the same day (insert, then update)
        val old = 120L * 24 * 60 * 60 * 1000
        repeat(2) {
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views", json = """{"app":"$app","pageViews":[${viewJson(home, old)},${viewJson(cart, old)}]}""").status)
            awaitIngested()
            assertEquals(1, retention().run())
        }
        val year = Json.decodeFromString<PeriodStats>(call(HttpMethod.Get, "/apps/$app/stats?period=year", alice).bodyAsText())
        assertEquals(7, year.current.views)
        assertEquals(7, year.buckets.sumOf { it.views })
        assertEquals(listOf("home" to 4L, "cart" to 3L), year.pages.map { it.name to it.views })

        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$app/pages/$cart", alice).status)
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/apps/$app", alice).status)
    }

    @Test
    fun migratesTheLegacySchemaAndData() {
        val url = newDatabase()
        val now = System.currentTimeMillis()
        sql(url, javaClass.getResource("/legacy-schema.sql")!!.readText())
        sql(url,
            "INSERT INTO users(email, display_name, password_hash) VALUES ('alice@x.io', 'A', '${legacyHash("alicepass1")}')",
            "INSERT INTO pages(id, name, owner) VALUES " +
                "('aaaaaaaa-0000-4000-8000-000000000001', 'home', 1), " +
                "('cccccccc-0000-4000-8000-000000000001', 'orphan', NULL)",
            "INSERT INTO pagesviews(page, \"sessionId\", \"timestamp\") VALUES " +
                "('aaaaaaaa-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111', ${now - 60_000}), " +
                "('cccccccc-0000-4000-8000-000000000001', '22222222-2222-4222-8222-222222222222', ${now - 60_000})"
        )

        withServer("ktor.deployment.database_url" to url) {
            // The legacy password still works (and is upgraded to bcrypt)
            val alice = sessionCookie(postForm("/login", "userId" to "alice@x.io", "password" to "alicepass1"))!!
            val app = Json.decodeFromString<AppsOut>(call(HttpMethod.Get, "/apps", alice).bodyAsText()).apps.single()
            assertEquals("Application par défaut", app.name)
            assertEquals(1, app.pages)
            val stats = Json.decodeFromString<PeriodStats>(call(HttpMethod.Get, "/apps/${app.id}/stats?period=day", alice).bodyAsText())
            assertEquals(1, stats.current.views)
            // New views go into the existing tables
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/views",
                json = """{"app":"${app.id}","pageViews":[${viewJson("aaaaaaaa-0000-4000-8000-000000000001")}]}""").status)
            awaitIngested()
        }

        assertEquals("bigint", query(url, "SELECT data_type FROM information_schema.columns WHERE table_name = 'pagesviews' AND column_name = 'id'") { it.next(); it.getString(1) })
        assertEquals("bigint", query(url, "SELECT data_type FROM information_schema.sequences WHERE sequence_name = 'pagesviews_id_seq'") { it.next(); it.getString(1) })
        assertEquals(listOf("accountnotices", "app_members", "apps", "emailverifications", "pages", "pagesdaily", "pagesviews", "passwordresets", "users", "viewsdropped"),
            query(url, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1") { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            })
        assertEquals(3L to 2L, query(url, "SELECT count(*), count(app) FROM pagesviews") { it.next(); it.getLong(1) to it.getLong(2) })
        assertTrue(query(url, "SELECT password_hash FROM users WHERE email = 'alice@x.io'") { it.next(); it.getString(1) }.startsWith("$2"))

        // Starting again changes nothing
        withServer("ktor.deployment.database_url" to url) { }
        assertEquals(1L, query(url, "SELECT count(*) FROM apps") { it.next(); it.getLong(1) })
    }
}
