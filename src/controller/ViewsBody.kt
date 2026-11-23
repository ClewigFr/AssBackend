package com.ass.controller

import com.ass.model.AssJson
import com.ass.model.PostPageViews
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Largest POST /views body, after decompression. A full retry file (1000 views) is about
 * 130 KB of JSON; the cap is what stops a small gzip bomb from filling memory.
 */
const val MAX_VIEWS_BODY_BYTES = 1 shl 20

sealed interface ViewsBody {
    data class Read(val views: PostPageViews) : ViewsBody
    /** A Content-Encoding other than gzip or identity: 415. */
    data object UnsupportedEncoding : ViewsBody
    /** Empty, too large, corrupt gzip or not valid JSON: 400. */
    data object Invalid : ViewsBody
}

/** Reads the body of POST /views, gzipped or not, never more than [MAX_VIEWS_BODY_BYTES] once decompressed. */
suspend fun readViewsBody(call: ApplicationCall): ViewsBody {
    val encoding = call.request.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
    if (encoding != null && encoding != "identity" && encoding != "gzip") return ViewsBody.UnsupportedEncoding
    val raw = call.receiveChannel().toInputStream()
    return withContext(Dispatchers.IO) {
        try {
            val stream: InputStream = if (encoding == "gzip") GZIPInputStream(raw) else raw
            val bytes = stream.use { it.readNBytes(MAX_VIEWS_BODY_BYTES + 1) }
            if (bytes.isEmpty() || bytes.size > MAX_VIEWS_BODY_BYTES) ViewsBody.Invalid
            else ViewsBody.Read(AssJson.decodeFromString(PostPageViews.serializer(), bytes.decodeToString()))
        } catch (e: IOException) {
            ViewsBody.Invalid // corrupt gzip, truncated body
        } catch (e: SerializationException) {
            ViewsBody.Invalid
        } catch (e: IllegalArgumentException) {
            ViewsBody.Invalid
        }
    }
}
