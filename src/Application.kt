package com.ass

import com.ass.controller.*
import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesDaily
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.db.Db
import com.ass.db.migrateToApplications
import com.ass.db.widenViewIds
import com.ass.ingest.PageCache
import com.ass.ingest.ViewBudget
import com.ass.ingest.ViewIngestor
import com.ass.model.AssJson
import com.ass.account.Quotas
import com.ass.account.account
import com.ass.routing.health
import com.ass.routing.root
import com.ass.retention.Retention
import com.ass.stats.StatsService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import com.ass.auth.BodyLimit
import com.ass.auth.originCheck
import com.ass.auth.securityHeaders
import com.ass.dao.warnAboutLegacyHashes
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.CompressionConfig
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.util.AttributeKey
import io.ktor.util.hex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.security.SecureRandom
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
fun Application.module() {
    val databaseUrl = environment.config.property("ktor.deployment.database_url").getString()
    val db = Db(
        url = databaseUrl,
        driver = if (databaseUrl.startsWith("jdbc:h2:")) "org.h2.Driver" else "org.postgresql.Driver",
        poolSize = configInt("ktor.performance.db_pool_size", 10)
    )
    databaseInitialization(db)
    warnAboutLegacyHashes(db)

    val pageCache = PageCache(db)
    runBlocking { pageCache.load() }
    val ingestor = ViewIngestor(
        db, this,
        flushIntervalMs = configInt("ktor.performance.flush_interval_ms", 200).toLong(),
        capacity = configInt("ktor.performance.ingest_capacity", 500_000)
    )
    attributes.put(IngestorKey, ingestor)

    // Write buffered views before the database goes away
    monitor.subscribe(ApplicationStopping) { runBlocking { ingestor.close() } }
    monitor.subscribe(ApplicationStopped) { db.close() }

    val retention = Retention(db, days = configInt("ktor.performance.retention_days", 90))
    val statsService = StatsService(db, cacheMs = configInt("ktor.performance.stats_cache_ms", 5_000).toLong(), retention = retention)
    retention.onChange = statsService::invalidateAll
    attributes.put(RetentionKey, retention)
    // Roll up old views shortly after start, then every 6 hours
    if (retention.days > 0) launch {
        delay(60.seconds)
        while (true) {
            try { retention.run() } catch (e: Exception) { log.error("Retention failed", e) }
            delay(6.hours)
        }
    }

    installModule()
    routing {
        root()
        health(db)
        pageViews(pageCache, ingestor, ViewBudget(configInt("ktor.performance.views_per_minute", 20_000)), rateLimited = viewsRateLimit > 0)
        val quotas = Quotas(
            maxApps = configInt("ktor.quotas.max_apps", 5),
            maxPagesPerApp = configInt("ktor.quotas.max_pages_per_app", 30),
            maxMonthlyViews = configInt("ktor.quotas.max_monthly_views", 100_000).toLong()
        )
        apps(db, pageCache, statsService, quotas)
        account(db, quotas, pageCache, statsService, rateLimited = authRateLimit > 0)
        login(db, rateLimited = authRateLimit > 0)
        register(db, rateLimited = authRateLimit > 0)
    }
}

/** The retention job of a running application (tests run it directly). */
val RetentionKey = AttributeKey<Retention>("Retention")

/** The ingestor of a running application (tests use it to wait for buffered writes). */
val IngestorKey = AttributeKey<ViewIngestor>("ViewIngestor")

private fun Application.configInt(path: String, default: Int) =
    environment.config.propertyOrNull(path)?.getString()?.toIntOrNull() ?: default

/** Max POST /views per client IP per minute; 0 disables the limit. */
private val Application.viewsRateLimit get() = configInt("ktor.performance.views_rate_limit", 600)

/** Max POST /login and POST /register per client IP per minute; 0 disables the limit. */
private val Application.authRateLimit get() = configInt("ktor.performance.auth_rate_limit", 10)

// Install module
fun Application.installModule() {
    // Read config up front: Ktor's DSL markers forbid reaching Application from inside plugin blocks
    val dev = isDev
    val signKey = sessionSignKey
    val origins = corsOrigins

    install(Sessions) {
        cookie<ASSSession>("MY_SESSION") {
            if (dev) {
                cookie.extensions["SameSite"] = "lax"
            } else {
                // The dashboard is served from another site, so the cookie must be sent cross-site (HTTPS only).
                cookie.extensions["SameSite"] = "None"
                cookie.secure = true
            }
            // Sign the cookie so a client cannot forge a session for another user.
            transform(SessionTransportTransformerMessageAuthentication(signKey))
        }
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowCredentials = true
        // Only the dashboard origins may make credentialed requests (CORS_ORIGINS, comma-separated).
        origins.forEach { origin ->
            val (scheme, host) = origin.split("://", limit = 2)
            allowHost(host, schemes = listOf(scheme))
        }
    }

    // Unparseable request bodies are client errors (400), never 500: the Android client retries on 5xx.
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<ContentTransformationException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<JsonConvertException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<PayloadTooLargeException> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge) }
    }

    install(ContentNegotiation) {
        json(AssJson)
    }

    // Responses only: POST /views decompresses its own body with a size cap (see ViewsBody.kt),
    // the plugin's request decompression has no cap and would let a small gzip bomb fill memory
    // Answer HEAD like GET without a body (uptime monitors such as UptimeRobot use HEAD)
    install(AutoHeadResponse)

    install(Compression) {
        mode = CompressionConfig.Mode.CompressResponse
        gzip { minimumSize(1024) }
    }

    install(securityHeaders(hsts = !dev))
    install(BodyLimit)
    install(originCheck(origins))

    if (!dev) {
        // Behind a proxy (Cloudflare Tunnel), the real client IP is the last
        // X-Forwarded-For entry: proxies append it after whatever the client sent. It's only
        // used as the in-memory rate-limit key, never stored or logged.
        install(XForwardedHeaders) { useLastProxy() }
    }

    val limit = viewsRateLimit
    val authLimit = authRateLimit
    if (limit > 0 || authLimit > 0) {
        install(RateLimit) {
            if (limit > 0) register(VIEWS_RATE_LIMIT) {
                rateLimiter(limit = limit, refillPeriod = 60.seconds)
                requestKey { call -> call.request.origin.remoteHost }
            }
            if (authLimit > 0) register(AUTH_RATE_LIMIT) {
                rateLimiter(limit = authLimit, refillPeriod = 60.seconds)
                requestKey { call -> call.request.origin.remoteHost }
            }
        }
    }
}

/**
 * Whether anyone may create an account. Each account only sees its own applications, but
 * registration stays closed by default outside dev so a public server can't be used by
 * strangers: set REGISTRATION_OPEN=true to allow sign-ups.
 */
val Application.registrationOpen: Boolean
    get() = environment.config.propertyOrNull("ktor.security.registration_open")?.getString()?.toBoolean()
        ?: isDev

// Database init: also adds columns introduced after the tables were first created
fun databaseInitialization(db: Db) {
    transaction(db.database) {
        SchemaUtils.create(Users, Apps, Pages, PagesViews, PagesDaily)
        // Add missing columns and indexes, but never drop anything automatically
        MigrationUtils.statementsRequiredForDatabaseMigration(Users, Apps, Pages, PagesViews, PagesDaily, withLogs = false)
            .filterNot { it.trimStart().startsWith("DROP", ignoreCase = true) || it.contains(" DROP ", ignoreCase = true) }
            .forEach { exec(it) }
    }
    transaction(db.database) {
        widenViewIds()
        migrateToApplications()
    }
}

/**
 * Key used to sign session cookies. Must be set through SESSION_SIGN_KEY (hex, 32+ bytes) outside dev;
 * in dev a random key is generated on each start, which logs everyone out on restart.
 */
private val Application.sessionSignKey: ByteArray
    get() {
        val configured = environment.config.propertyOrNull("ktor.security.session_sign_key")?.getString()
        return when {
            !configured.isNullOrBlank() -> hex(configured)
            isDev -> ByteArray(32).also { SecureRandom().nextBytes(it) }
            else -> error("SESSION_SIGN_KEY must be set in production")
        }
    }

/**
 * Origins allowed by CORS, e.g. "https://dashboard.example.com,http://localhost:8080".
 */
private val Application.corsOrigins: List<String>
    get() = environment.config.property("ktor.security.cors_origins").getString()
        .split(',').map { it.trim().trimEnd('/') }.filter { it.contains("://") }

/**
 * Signed session cookie content: [userId] is the user's internal id (no email in the cookie),
 * [version] must match the user's session version (logout revokes), [issuedAt] lets the server
 * expire sessions (see SESSION_MAX_AGE_MS). Older cookies carried the email: they are refused.
 */
@Serializable
data class ASSSession(val userId: String = "", val issuedAt: Long = 0, val version: Int = 0)

val Application.envKind get() = environment.config.property("ktor.environment").getString()
val Application.isDev get() = envKind == "dev"
val Application.isProd get() = envKind != "dev"
