package com.ass.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.UUID

/** JSON settings of every request and response. */
val AssJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

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

/** [app] is the application UUID the client was configured with; every page must belong to it. */
@Serializable
data class PostPageViews(val app: JsonUUID? = null, val pageViews: List<PageViewIn?>? = null)

@Serializable
data class NameIn(val name: String? = null)

@Serializable
data class NamesIn(val names: List<String>? = null)

@Serializable
data class BatchPagesOut(val created: List<PageOut>, val existing: List<PageOut>)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppOut(
    val id: JsonUUID,
    val name: String,
    val pages: Long = 0,
    /** The caller's role: "owner", "manage" or "read". */
    @EncodeDefault val role: String = "owner",
    /** Email of the owner, only on applications shared with the caller. */
    val owner: String? = null,
)

@Serializable
data class MemberIn(val email: String? = null, val role: String? = null)

@Serializable
data class MemberOut(val userId: Int, val email: String, val role: String)

@Serializable
data class MembersOut(val members: List<MemberOut>)

@Serializable
data class AppsOut(val apps: List<AppOut>)

@Serializable
data class PageOut(val id: JsonUUID, val name: String)

@Serializable
data class PagesOut(val pages: List<PageOut>)

@Serializable
data class AppPagesOut(val app: AppOut, val pages: List<PageOut>)

@Serializable
data class PageStat(val id: JsonUUID, val name: String, val views: Long, val sessions: Long)

/** Headline numbers for a period. Durations are in ms; a single-view session lasts 0 ms. */
@Serializable
data class Summary(
    val views: Long,
    val sessions: Long,
    val pagesPerSession: Double,
    val avgSessionMs: Long,
    val bounceRate: Double
)

/** One time slot: [start] is its first instant (epoch ms). Sessions are counted in the slot where they started. */
@Serializable
data class Bucket(val start: Long, val views: Long, val sessions: Long)

/**
 * Stats of one application over [from, to) (epoch ms), with the same numbers for the
 * previous period of equal length, to show trends.
 */
@Serializable
data class PeriodStats(
    val period: String,
    val granularity: String,
    val offset: Int,
    val from: Long,
    val to: Long,
    val current: Summary,
    val previous: Summary,
    val buckets: List<Bucket>,
    /** The slots of the previous period, one per slot of [buckets], to compare them on a chart. */
    val previousBuckets: List<Bucket> = emptyList(),
    val pages: List<PageStat>,
    /** Before this instant only views per page and per day are kept (no sessions, hours or durations); null if everything is kept. */
    val detailFrom: Long? = null
)

@Serializable
data class PasswordChangeIn(val current: String? = null, val new: String? = null)

@Serializable
data class PasswordIn(val password: String? = null)

/** 0 means no limit. */
@Serializable
data class QuotasOut(val maxApps: Int, val maxPagesPerApp: Int, val maxMonthlyViews: Long)

/** [monthViews]: views of all the user's applications since [monthStart] (first day of the month, UTC). */
@Serializable
data class UsageOut(val apps: Long, val monthViews: Long, val monthStart: Long)

@Serializable
data class AccountOut(val email: String, val displayName: String, val quotas: QuotasOut, val usage: UsageOut, val monthlyReport: Boolean = false)

@Serializable
data class AccountSettingsIn(val monthlyReport: Boolean? = null)

@Serializable
data class ForgotIn(val email: String? = null)

@Serializable
data class ResetIn(val token: String? = null, val new: String? = null)

@Serializable
data class DeletedOut(val deleted: Long)

/** Public limits of the service (GET /limits); 0 means no limit, or everything kept for retentionDays. */
@Serializable
data class LimitsOut(val maxApps: Int, val maxPagesPerApp: Int, val maxMonthlyViews: Long, val retentionDays: Int)

/** Latest released versions of the libraries (GET /versions); null while unknown. */
@Serializable
data class VersionsOut(val android: String? = null, val ios: String? = null)

/** Sessions (and views) where [page] was the first, or the last, screen. */
@Serializable
data class FlowPage(val page: JsonUUID, val sessions: Long)

/** Moves from screen [from] to screen [to] (next view of the same session); [avgMs] is the mean time spent on [from]. */
@Serializable
data class FlowTransition(val from: JsonUUID, val to: JsonUUID, val sessions: Long, val count: Long, val avgMs: Long)

/**
 * Screen paths of one application over [from, to), only as aggregates: transitions seen in fewer
 * than [minSessions] sessions are not listed, only summed in [hiddenSessions] (session-transition pairs).
 */
@Serializable
data class FlowStats(
    val from: Long,
    val to: Long,
    val sessions: Long,
    val minSessions: Int,
    val entries: List<FlowPage>,
    val exits: List<FlowPage>,
    val transitions: List<FlowTransition>,
    val hiddenSessions: Long,
    /** Paths need raw views: before this instant there are none (null if everything is kept). */
    val detailFrom: Long? = null
)
