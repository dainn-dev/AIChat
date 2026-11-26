package com.aichat.keyboard.schedule

import com.aichat.keyboard.schedule.ScheduleDraft.Problem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleDraftTest {
    private val saigon = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = LocalDateTime.of(2026, 11, 26, 9, 41, 30).atZone(saigon).toInstant().toEpochMilli()

    @Test
    fun `a filled draft for a known chat, far enough ahead, is savable`() {
        assertNull(ScheduleDraft.check("Chúc mừng sinh nhật!", now + 2 * 60_000, now, hasRecipient = true))
    }

    @Test
    fun `the recipient is checked first, then the text, then the time`() {
        assertEquals(Problem.NO_RECIPIENT, ScheduleDraft.check("", now - 1, now, hasRecipient = false))
        assertEquals(Problem.EMPTY_TEXT, ScheduleDraft.check("  \n ", now - 1, now, hasRecipient = true))
        assertEquals(Problem.TOO_LONG, ScheduleDraft.check("a".repeat(ScheduleDraft.MAX_CHARS + 1), now, now, true))
        assertEquals(Problem.NOT_IN_FUTURE, ScheduleDraft.check("hi", now + ScheduleDraft.MIN_LEAD_MS - 1, now, true))
        assertNull(ScheduleDraft.check("hi", now + ScheduleDraft.MIN_LEAD_MS, now, true))
    }

    @Test
    fun `typing stops at the character limit`() {
        assertTrue(ScheduleDraft.accepts("a".repeat(ScheduleDraft.MAX_CHARS)))
        assertFalse(ScheduleDraft.accepts("a".repeat(ScheduleDraft.MAX_CHARS + 1)))
    }

    @Test
    fun `the picked UTC day and the local time give the local instant`() {
        val dayUtc = LocalDate.of(2026, 12, 24).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val sendAt = ScheduleDraft.sendAt(dayUtc, 7, 5, saigon)

        assertEquals(LocalDateTime.of(2026, 12, 24, 7, 5).atZone(saigon).toInstant().toEpochMilli(), sendAt)
    }

    @Test
    fun `the date picker day is the local day, not the UTC day`() {
        // 06:30 in Saigon is still the previous day in UTC.
        val early = LocalDateTime.of(2026, 11, 27, 6, 30).atZone(saigon).toInstant().toEpochMilli()

        val dayUtc = ScheduleDraft.dateUtcMs(early, saigon)

        assertEquals(LocalDate.of(2026, 11, 27).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), dayUtc)
        assertEquals(early, ScheduleDraft.sendAt(dayUtc, 6, 30, saigon))
    }

    @Test
    fun `the default time is an hour ahead, rounded up to five minutes`() {
        val expected = LocalDateTime.of(2026, 11, 26, 10, 45).atZone(saigon).toInstant().toEpochMilli()

        assertEquals(expected, ScheduleDraft.defaultSendAt(now, saigon))
    }

    @Test
    fun `a default already on a five minute mark is kept`() {
        val onMark = LocalDateTime.of(2026, 11, 26, 9, 40).atZone(saigon).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 11, 26, 10, 40).atZone(saigon).toInstant().toEpochMilli()

        assertEquals(expected, ScheduleDraft.defaultSendAt(onMark, saigon))
    }
}
