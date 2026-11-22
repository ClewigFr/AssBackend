package com.ass.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class Granularity { HOUR, DAY, MONTH }

/**
 * The dashboard's periods, in the viewer's time zone (fixed offset):
 * - DAY:   today, hour by hour
 * - WEEK:  the last 7 days (today included), day by day
 * - MONTH: the last 30 days, day by day
 * - YEAR:  the last 12 months (current month included), month by month
 * [offset] moves back (-1, -2…) by whole periods.
 */
enum class Period(val granularity: Granularity) {
    DAY(Granularity.HOUR), WEEK(Granularity.DAY), MONTH(Granularity.DAY), YEAR(Granularity.MONTH);

    companion object {
        fun parse(value: String?): Period? =
            if (value == null) WEEK else entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** A half-open time range [from, to) in epoch ms, cut into buckets (their start instants). */
data class Range(val from: Long, val to: Long, val bucketStarts: List<Long>)

fun rangeOf(period: Period, offset: Int, tzOffsetMs: Long, now: Long = System.currentTimeMillis()): Range {
    val zone = ZoneOffset.ofTotalSeconds((tzOffsetMs / 1000).toInt())
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
    return when (period) {
        Period.DAY -> {
            val day = today.plusDays(offset.toLong())
            val from = ms(day)
            Range(from, ms(day.plusDays(1)), (0 until 24).map { from + it * HOUR_MS })
        }
        Period.WEEK, Period.MONTH -> {
            val days = if (period == Period.WEEK) 7L else 30L
            val last = today.plusDays(offset * days)
            val first = last.minusDays(days - 1)
            Range(ms(first), ms(last.plusDays(1)), (0 until days).map { ms(first.plusDays(it)) })
        }
        Period.YEAR -> {
            val lastMonth = today.withDayOfMonth(1).plusMonths(offset * 12L)
            val firstMonth = lastMonth.minusMonths(11)
            Range(ms(firstMonth), ms(lastMonth.plusMonths(1)), (0 until 12).map { ms(firstMonth.plusMonths(it.toLong())) })
        }
    }
}

const val HOUR_MS = 60L * 60 * 1000

/** Index of the bucket containing [instant], or -1. Buckets are sorted. */
fun Range.bucketIndex(instant: Long): Int {
    if (instant < from || instant >= to) return -1
    var i = bucketStarts.binarySearch(instant)
    if (i < 0) i = -i - 2
    return i
}

