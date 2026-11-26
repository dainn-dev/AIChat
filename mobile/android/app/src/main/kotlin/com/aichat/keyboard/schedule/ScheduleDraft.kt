/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Rules of the "Hẹn giờ gửi" popup ([com.aichat.keyboard.schedule.ui.ScheduleMessageActivity]). Pure Kotlin so they
 * are unit-tested.
 */
object ScheduleDraft {
    /** Longer than any chat message people schedule; keeps one stored row small. */
    const val MAX_CHARS = 2_000

    /** A send time must be at least this far ahead, so the alarm and the 5 s cancel countdown still fit. */
    const val MIN_LEAD_MS = 60_000L

    /** Default send time: this long from now, rounded up to [DEFAULT_ROUND_MINUTES]. */
    private const val DEFAULT_LEAD_MINUTES = 60L
    private const val DEFAULT_ROUND_MINUTES = 5

    enum class Problem { EMPTY_TEXT, TOO_LONG, NOT_IN_FUTURE, NO_RECIPIENT }

    fun accepts(text: String): Boolean = text.length <= MAX_CHARS

    /** The first thing that stops the draft from being saved, or null if it can be saved. */
    fun check(text: String, sendAtMs: Long, nowMs: Long, hasRecipient: Boolean): Problem? = when {
        !hasRecipient -> Problem.NO_RECIPIENT
        text.isBlank() -> Problem.EMPTY_TEXT
        text.length > MAX_CHARS -> Problem.TOO_LONG
        sendAtMs - nowMs < MIN_LEAD_MS -> Problem.NOT_IN_FUTURE
        else -> null
    }

    /**
     * The instant of [hour]:[minute] on the day of [dateUtcMs] in [zone]. [dateUtcMs] is the UTC midnight that
     * Material3's date picker reports for the chosen day.
     */
    fun sendAt(dateUtcMs: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(dateUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
    }

    /** The UTC midnight of [epochMs]'s local day in [zone], as the date picker expects. */
    fun dateUtcMs(epochMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** One hour from now, rounded up to the next 5 minutes, seconds dropped. */
    fun defaultSendAt(nowMs: Long, zone: ZoneId): Long {
        val later = Instant.ofEpochMilli(nowMs).atZone(zone).plusMinutes(DEFAULT_LEAD_MINUTES).truncatedTo(ChronoUnit.MINUTES)
        val extra = (DEFAULT_ROUND_MINUTES - later.minute % DEFAULT_ROUND_MINUTES) % DEFAULT_ROUND_MINUTES
        return later.plusMinutes(extra.toLong()).toInstant().toEpochMilli()
    }
}
