package com.ass.versions

import com.ass.model.VersionsOut
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The latest release tag of a GitHub repository ("owner/name"), or null when unknown. */
fun interface ReleaseFetcher {
    suspend fun latest(repo: String): String?
}

/** GitHub's public API, without a token: 60 requests per hour, far more than one refresh per hour needs. */
class GitHubReleases(private val apiUrl: String = "https://api.github.com") : ReleaseFetcher {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val log = LoggerFactory.getLogger("LibraryVersions")

    override suspend fun latest(repo: String): String? {
        val request = HttpRequest.newBuilder(URI("$apiUrl/repos/$repo/releases/latest"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AnonymousSimpleStats")
            .GET()
            .build()
        return try {
            val response = withContext(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            if (response.statusCode() != 200) {
                log.warn("Latest release of {}: HTTP {}", repo, response.statusCode())
                null
            } else parseTag(response.body())
        } catch (e: Exception) {
            log.warn("Latest release of {} failed: {}", repo, e.toString())
            null
        }
    }

    companion object {
        private val VERSION = Regex("""\d{1,4}\.\d{1,4}\.\d{1,4}""")

        /** The release's tag_name when it is a plain X.Y.Z version, else null (never shown as is). */
        internal fun parseTag(body: String): String? = try {
            Json.parseToJsonElement(body).jsonObject["tag_name"]?.jsonPrimitive?.content
                ?.removePrefix("v")?.takeIf { VERSION.matches(it) }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * The latest versions of the Android and iOS libraries, for the presentation page's snippets.
 * Refreshed in the background (see Application), so visitors' requests never reach GitHub and
 * a failed refresh keeps the last known versions.
 */
class LibraryVersions(
    private val fetcher: ReleaseFetcher,
    private val androidRepo: String = "ClewigFr/AssAndroid",
    private val iosRepo: String = "ClewigFr/AssIOS"
) {
    @Volatile
    var current = VersionsOut(null, null)
        private set

    suspend fun refresh() {
        val android = fetcher.latest(androidRepo)
        val ios = fetcher.latest(iosRepo)
        current = VersionsOut(android ?: current.android, ios ?: current.ios)
    }
}

/** GET /versions, public: {"android", "ios"}, each null until a refresh found it. */
fun Route.versions(versions: LibraryVersions) {
    get("/versions") { call.respond(versions.current) }
}
