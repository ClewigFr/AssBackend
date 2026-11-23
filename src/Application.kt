package com.ass

import com.ass.controller.*
import com.ass.dao.Apps
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.db.Db
import com.ass.db.migrateToApplications
import com.ass.db.widenViewIds
import com.ass.ingest.PageCache
import com.ass.ingest.ViewIngestor
import com.ass.routing.health
import com.ass.routing.root
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
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.security.SecureRandom
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

    val statsService = StatsService(db, cacheMs = configInt("ktor.performance.stats_cache_ms", 5_000).toLong())

    installModule()
    routing {
        root()
        health(db)
        pageViews(pageCache, ingestor, rateLimited = viewsRateLimit > 0)
        apps(db, pageCache, statsService)
        login(db)
        register(db)
    }
}

/** The ingestor of a running application (tests use it to wait for buffered writes). */
val IngestorKey = AttributeKey<ViewIngestor>("ViewIngestor")

private fun Application.configInt(path: String, default: Int) =
    environment.config.propertyOrNull(path)?.getString()?.toIntOrNull() ?: default

/** Max POST /views per client IP per minute; 0 disables the limit. */
private val Application.viewsRateLimit get() = configInt("ktor.performance.views_rate_limit", 600)

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
    }

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }

    install(Compression) {
        gzip { minimumSize(1024) }
    }

    if (!dev) {
        // Behind a proxy (Cloudflare Tunnel, Heroku's router), the real client IP is the last
        // X-Forwarded-For entry: proxies append it after whatever the client sent. It's only
        // used as the in-memory rate-limit key, never stored or logged.
        install(XForwardedHeaders) { useLastProxy() }
    }

    val limit = viewsRateLimit
    if (limit > 0) {
        install(RateLimit) {
            register(VIEWS_RATE_LIMIT) {
                rateLimiter(limit = limit, refillPeriod = 60.seconds)
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
        SchemaUtils.create(Users, Apps, Pages, PagesViews)
        // Add missing columns and indexes, but never drop anything automatically
        MigrationUtils.statementsRequiredForDatabaseMigration(Users, Apps, Pages, PagesViews, withLogs = false)
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

/** Signed session cookie content. [issuedAt] lets the server expire sessions (see SESSION_MAX_AGE_MS). */
@Serializable
data class ASSSession(val userId: String = "", val issuedAt: Long = 0)

val Application.envKind get() = environment.config.property("ktor.environment").getString()
val Application.isDev get() = envKind == "dev"
val Application.isProd get() = envKind != "dev"
