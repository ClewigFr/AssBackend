package com.ass

import io.ktor.config.MapApplicationConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.TestApplicationRequest
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withApplication
import java.util.UUID

const val DASHBOARD = "http://localhost:8080"

/** Run [test] against the real module with a fresh in-memory database. */
fun withServer(test: TestApplicationEngine.() -> Unit) = withApplication(createTestEnvironment {
    config = MapApplicationConfig(
        "ktor.environment" to "dev",
        "ktor.deployment.database_url" to "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "ktor.security.cors_origins" to DASHBOARD
    )
    module { module() }
}, test = test)

fun TestApplicationRequest.form(vararg fields: Pair<String, String>) {
    addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
    setBody(fields.toList().formUrlEncode())
}

fun TestApplicationRequest.json(body: String) {
    addHeader(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody(body)
}

/** Register a user and return its session cookie header value. */
fun TestApplicationEngine.registerAndLogin(email: String, password: String = "secret123"): String {
    val call = handleRequest(HttpMethod.Post, "/register") {
        form("email" to email, "password" to password, "displayName" to "Test")
    }
    return call.response.headers.values(HttpHeaders.SetCookie)
        .first { it.startsWith("MY_SESSION=") }.substringBefore(';')
}

/** Create a page as [cookie]'s user and return its id. */
fun TestApplicationEngine.createPage(cookie: String, name: String = "home"): String =
    handleRequest(HttpMethod.Post, "/pages") {
        addHeader(HttpHeaders.Cookie, cookie)
        json("""{"name":"$name"}""")
    }.response.content!!.substringAfter("id: ")

fun viewJson(page: String, elapsed: Long = 1000, session: String = UUID.randomUUID().toString()) =
    """{"page":"$page","sessionId":"$session","timestamp":$elapsed}"""
