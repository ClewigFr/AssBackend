package com.ass.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * The in-memory rate-limit key of a client: its IPv4 address, or the /64 prefix of its IPv6
 * address (one subscriber usually gets a whole /64, so per-address limits would be easy to
 * dodge). Never stored or logged.
 */
val ApplicationCall.clientKey: String get() = clientKeyOf(request.origin.remoteHost)

internal fun clientKeyOf(host: String): String {
    val ip = host.removePrefix("[").removeSuffix("]").substringBefore('%')
    if (!ip.contains(':')) return ip
    val parts = expandIpv6(ip) ?: return ip
    return parts.take(4).joinToString(":") + "::/64"
}

/** The 8 groups of an IPv6 address (lower case, no leading zeros), or null if it isn't one. */
private fun expandIpv6(ip: String): List<String>? {
    val halves = ip.lowercase().split("::")
    if (halves.size > 2) return null
    fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(':')
    val head = groups(halves[0])
    val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
    // An embedded IPv4 (::ffff:1.2.3.4) counts as two groups; only the first four matter here
    val count = (head + tail).sumOf { if (it.contains('.')) 2L else 1L }.toInt()
    val all = if (halves.size == 2) head + List(8 - count) { "0" } + tail else head
    if (count > 8 || (halves.size == 1 && count != 8)) return null
    return all.map { g ->
        if (g.contains('.')) g else g.toIntOrNull(16)?.takeIf { g.length <= 4 }?.toString(16) ?: return null
    }
}
