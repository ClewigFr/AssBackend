package com.ass

import com.ass.controller.pageViews
import com.ass.controller.pages
import com.ass.dao.Pages
import com.ass.dao.PagesViews
import com.ass.dao.PagesViews.page
import com.ass.model.Page
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
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.collections.listOf
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
}

// Routing init
fun Application.routingInitialization() {
    routing {
        root()
        pageViews()
        pages()
    }
}

// Routing init
fun Application.databaseInitialization() {
    transaction {
        create(Pages)
        create(PagesViews)
    }
}


data class MustacheUser(val id: Int, val name: String)

data class MySession(val count: Int = 0)

val Application.envKind get() = environment.config.property("ktor.environment").getString()
val Application.isDev get() = envKind == "dev"
val Application.isProd get() = envKind != "dev"