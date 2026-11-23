package com.ass

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun healthChecksTheDatabase() = withServer {
        val response = call(HttpMethod.Get, "/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun healthAnswersHeadForUptimeMonitors() = withServer {
        val response = call(HttpMethod.Head, "/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }
}
