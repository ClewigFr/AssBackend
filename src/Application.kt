package com.ass

import com.ass.controller.*
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.routing.root
import io.ktor.application.Application
import io.ktor.application.install
import com.google.gson.JsonParseException
import io.ktor.application.call
import io.ktor.features.BadRequestException
import io.ktor.features.CORS
import io.ktor.features.StatusPages
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.routing.routing
import io.ktor.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.sessions.Sessions
import io.ktor.sessions.cookie
import io.ktor.util.hex
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import kotlin.collections.set

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
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
    install(Sessions) {
        cookie<ASSSession>("MY_SESSION") {
            if (isDev) {
                cookie.extensions["SameSite"] = "lax"
            } else {
                // The dashboard is served from another site, so the cookie must be sent cross-site (HTTPS only).
                cookie.extensions["SameSite"] = "None"
                cookie.secure = true
            }
            // Sign the cookie so a client cannot forge a session for another user.
            transform(SessionTransportTransformerMessageAuthentication(sessionSignKey))
        }
    }

    install(CORS) {
        method(HttpMethod.Options)
        method(HttpMethod.Put)
        method(HttpMethod.Delete)
        method(HttpMethod.Patch)
        header(HttpHeaders.Authorization)
        header("MyCustomHeader")
        allowCredentials = true
        // Only the dashboard origins may make credentialed requests (CORS_ORIGINS, comma-separated).
        corsOrigins.forEach { origin ->
            val (scheme, host) = origin.split("://", limit = 2)
            host(host, schemes = listOf(scheme))
        }
    }

    // Unparseable request bodies are client errors (400), never 500: the Android client retries on 5xx.
    install(StatusPages) {
        exception<JsonParseException> { call.respond(HttpStatusCode.BadRequest) }
        exception<BadRequestException> { call.respond(HttpStatusCode.BadRequest) }
    }

    install(ContentNegotiation) {
        gson {
        }
    }
}

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
        createMissingTablesAndColumns(Users, Pages, PagesViews)
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

data class ASSSession(val userId: String = "")

val Application.envKind get() = environment.config.property("ktor.environment").getString()
val Application.isDev get() = envKind == "dev"
val Application.isProd get() = envKind != "dev"
