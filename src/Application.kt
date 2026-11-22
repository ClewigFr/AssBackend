package com.ass

import com.ass.controller.*
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.routing.root
import com.google.gson.JsonParseException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.util.hex
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.security.SecureRandom

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
fun Application.module() {
    val databaseUrl = environment.config.property("ktor.deployment.database_url").getString()
    Database.connect(
        databaseUrl,
        driver = if (isDev) "org.h2.Driver" else "org.postgresql.Driver"
    )
    databaseInitialization()
    installModule()
    routingInitialization()
}

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
        exception<JsonParseException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
    }

    install(ContentNegotiation) {
        gson {
        }
    }
}

/**
 * Whether anyone may create an account. Every account can read all stats, so this is
 * closed by default outside dev: set REGISTRATION_OPEN=true to create accounts, then unset it.
 */
val Application.registrationOpen: Boolean
    get() = environment.config.propertyOrNull("ktor.security.registration_open")?.getString()?.toBoolean()
        ?: isDev

// Routing init
fun Application.routingInitialization() {
    routing {
        root()
        pageViews()
        pages()
        stats()
        login()
        register()
    }
}

// Database init: also adds columns introduced after the tables were first created
fun Application.databaseInitialization() {
    transaction {
        SchemaUtils.create(Users, Pages, PagesViews)
        MigrationUtils.statementsRequiredForDatabaseMigration(Users, Pages, PagesViews, withLogs = false)
            .forEach { exec(it) }
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
