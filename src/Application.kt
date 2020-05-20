package com.ass

import com.ass.routing.root
import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.http.*
import com.github.mustachejava.DefaultMustacheFactory
import io.ktor.mustache.Mustache
import io.ktor.mustache.MustacheContent
import io.ktor.content.*
import io.ktor.http.content.*
import io.ktor.sessions.*
import io.ktor.features.*
import io.ktor.gson.*
import io.ktor.client.*
import io.ktor.client.engine.jetty.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.transactions.transaction

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(Mustache) {
        mustacheFactory = DefaultMustacheFactory("templates/mustache")
    }

    install(Sessions) {
        cookie<MySession>("MY_SESSION") {
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

    val client = HttpClient(Jetty) {
    }

    val port = Integer.valueOf(environment.config.property("ktor.deployment.port").getString())
    val databaseUrl = environment.config.property("ktor.deployment.database_url").getString()

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
    transaction {
        // create(Cocktails)
    }
    routing {
        root()
    }
}

data class MustacheUser(val id: Int, val name: String)

data class MySession(val count: Int = 0)

val Application.envKind get() = environment.config.property("ktor.environment").getString()
val Application.isDev get() = envKind == "dev"
val Application.isProd get() = envKind != "dev"