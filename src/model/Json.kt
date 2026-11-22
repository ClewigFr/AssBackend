package com.ass.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

/** UUID as a JSON string; an invalid string is a deserialization error (answered with 400). */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = try {
        UUID.fromString(decoder.decodeString())
    } catch (e: IllegalArgumentException) {
        throw SerializationException("Invalid UUID")
    }
}

typealias JsonUUID = @Serializable(with = UUIDSerializer::class) UUID

/** One view sent by a client. Fields are nullable so a missing field rejects only that view. */
@Serializable
data class PageViewIn(val page: JsonUUID? = null, val sessionId: JsonUUID? = null, val timestamp: Long? = null)

@Serializable
data class PostPageViews(val pageViews: List<PageViewIn?>? = null)

@Serializable
data class PageIn(val id: JsonUUID? = null, val name: String? = null)

@Serializable
data class PageOut(val id: JsonUUID, val name: String)

@Serializable
data class PagesOut(val pages: List<PageOut>)

@Serializable
data class PageStat(val id: JsonUUID, val name: String, val views: Long)

/** Views on one calendar day; [day] is the number of days since 1970-01-01 in the requested time zone. */
@Serializable
data class DayStat(val day: Long, val events: Long, val sessions: Long)

/** Aggregated statistics: the size depends on the number of pages and days, not on the number of views. */
@Serializable
data class StatsOut(val events: Long, val sessions: Long, val pages: List<PageStat>, val days: List<DayStat>)
