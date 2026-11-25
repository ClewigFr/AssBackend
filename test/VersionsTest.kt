package com.ass

import com.ass.model.VersionsOut
import com.ass.versions.GitHubReleases
import com.ass.versions.LibraryVersions
import com.ass.versions.ReleaseFetcher
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionsTest {

    @Test
    fun readsThePlainVersionTagOnly() {
        assertEquals("0.5.1", GitHubReleases.parseTag("""{"tag_name":"0.5.1","name":"x"}"""))
        assertEquals("1.2.3", GitHubReleases.parseTag("""{"tag_name":"v1.2.3"}"""))
        assertNull(GitHubReleases.parseTag("""{"tag_name":"0.1-alpha"}"""))
        assertNull(GitHubReleases.parseTag("""{"tag_name":"<script>"}"""))
        assertNull(GitHubReleases.parseTag("""{"message":"Not Found"}"""))
        assertNull(GitHubReleases.parseTag("not json"))
    }

    @Test
    fun keepsTheLastKnownVersionOfEachRepositoryWhenARefreshFails() = runBlocking {
        val answers = mutableMapOf<String, String?>("a/android" to "0.5.1", "a/ios" to "0.5.0")
        val versions = LibraryVersions(ReleaseFetcher { answers[it] }, "a/android", "a/ios")
        assertEquals(VersionsOut(null, null), versions.current)

        versions.refresh()
        assertEquals(VersionsOut("0.5.1", "0.5.0"), versions.current)

        answers["a/android"] = null
        answers["a/ios"] = "0.5.1"
        versions.refresh()
        assertEquals(VersionsOut("0.5.1", "0.5.1"), versions.current)
    }

    @Test
    fun servesNullsBeforeAnyRefreshWithoutLogin() = withServer {
        val body = Json.decodeFromString<VersionsOut>(call(HttpMethod.Get, "/versions").bodyAsText())
        assertEquals(VersionsOut(null, null), body)
    }
}
