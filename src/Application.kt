package com.ass

import com.ass.controller.*
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.dao.Users
import com.ass.routing.root
import com.github.mustachejava.DefaultMustacheFactory
import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.engine.jetty.Jetty
import io.ktor.features.CORS
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.mustache.Mustache
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.sessions.Sessions
import io.ktor.sessions.cookie
import io.ktor.util.hex
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.transactions.transaction
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.set

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    val port = Integer.valueOf(environment.config.property("ktor.deployment.port").getString())
    val databaseUrl = environment.config.property("ktor.deployment.database_url").getString()
    embeddedServer(
        Netty,
        watchPaths = listOf("AssBackend"),
        port = port,
        module = Application::mainModule
    ).apply {
        when {
            isDev -> {
                Database.connect(
                    databaseUrl,
                    driver = "org.h2.Driver"
                )
            }
            isProd -> {
                Database.connect(
                    databaseUrl, driver = "org.postgresql.Driver"
                )
            }
        }
        databaseInitialization()
        start(wait = true)
    }
}

// Main module
fun Application.mainModule() {
    installModule()

    val client = HttpClient(Jetty) {
    }
    routingInitialization()
}

// Install module
fun Application.installModule() {
    install(Mustache) {
        mustacheFactory = DefaultMustacheFactory("templates/mustache")
    }

    install(Sessions) {
        cookie<ASSSession>("MY_SESSION") {
            cookie.extensions["SameSite"] = "lax"
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
        anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
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
        login(hashFunction)
        register(hashFunction)
    }
}

// Routing init
fun Application.databaseInitialization() {
    transaction {
        create(Pages)
        create(PagesViews)
        create(Users)
    }
}

/**
 * Hardcoded secret hash key used to hash the passwords, and to authenticate the sessions.
 */
val hashKey = hex("6819b57a326945c1968f45236589")

/**
 * HMac SHA1 key spec for the password hashing.
 */
val hmacKey = SecretKeySpec(hashKey, "HmacSHA1")

/**
 * Method that hashes a [password] by using the globally defined secret key [hmacKey].
 */
fun hash(password: String): String {
    val hmac = Mac.getInstance("HmacSHA1")
    hmac.init(hmacKey)
    return hex(hmac.doFinal(password.toByteArray(Charsets.UTF_8)))
}

// Provides a hash function to be used when registering the resources.
val hashFunction = { s: String -> hash(s) }

data class MustacheUser(val id: Int, val name: String)

data class ASSSession(val userId: String = "", val count: Int = 0)

val Application.envKind get() = environment.config.property("ktor.environment").getString()
val Application.isDev get() = envKind == "dev"
val Application.isProd get() = envKind != "dev"