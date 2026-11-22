package com.ass

import com.ass.auth.LoginThrottle
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID

const val DASHBOARD = "http://localhost:8080"

/** Run [test] against the real module with a fresh in-memory database. */
fun withServer(vararg extraConfig: Pair<String, String>, test: suspend ApplicationTestBuilder.() -> Unit) {
    LoginThrottle.reset()
    testApplication {
        environment {
            config = MapApplicationConfig(
                "ktor.environment" to "dev",
                "ktor.deployment.database_url" to "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "ktor.security.cors_origins" to DASHBOARD,
                "ktor.performance.flush_interval_ms" to "10",
                "ktor.performance.stats_cache_ms" to "0",
                *extraConfig
            )
        }
        application {
            module()
            apps[this@testApplication] = this
        }
        startApplication()
        test()
    }
}

private val apps = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ApplicationTestBuilder, Application>())

/** Wait until every accepted view has been written to the database. */
suspend fun ApplicationTestBuilder.awaitIngested() = apps.getValue(this).attributes[IngestorKey].awaitIdle()

suspend fun ApplicationTestBuilder.call(
    method: HttpMethod,
    path: String,
    cookie: String? = null,
    origin: String? = null,
    json: String? = null,
    form: List<Pair<String, String>>? = null
): HttpResponse = client.request(path) {
    this.method = method
    cookie?.let { header(HttpHeaders.Cookie, it) }
    origin?.let { header(HttpHeaders.Origin, it) }
    if (json != null) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }
    if (form != null) {
        setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
    }
}

suspend fun ApplicationTestBuilder.postForm(path: String, vararg fields: Pair<String, String>) =
    call(HttpMethod.Post, path, form = fields.toList())

/** The session cookie set by [response], as a `Cookie` header value. */
fun sessionCookie(response: HttpResponse): String? =
    response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        .firstOrNull { it.startsWith("MY_SESSION=") }?.substringBefore(';')

/** Register a user and return its session cookie header value. */
suspend fun ApplicationTestBuilder.registerAndLogin(email: String, password: String = "secret123"): String =
    sessionCookie(postForm("/register", "email" to email, "password" to password, "displayName" to "Test"))!!

/** Create a page as [cookie]'s user and return its id. */
suspend fun ApplicationTestBuilder.createPage(cookie: String, name: String = "home"): String =
    call(HttpMethod.Post, "/pages", cookie = cookie, json = """{"name":"$name"}""").bodyAsText().substringAfter("id: ")

fun viewJson(page: String, elapsed: Long = 1000, session: String = UUID.randomUUID().toString()) =
    """{"page":"$page","sessionId":"$session","timestamp":$elapsed}"""
