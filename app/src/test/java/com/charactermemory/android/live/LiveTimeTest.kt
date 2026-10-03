package com.charactermemory.android.live

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class LiveTimeTest {
    @Test fun coreIsoTimestampRetainsFractionAndOffsetMeaning() {
        val value = "2026-10-02T10:01:00.123456+08:00"
        assertEquals("10:01", LiveTime.short(value, ZoneId.of("Asia/Shanghai")))
        assertEquals("02:01", LiveTime.short(value, ZoneId.of("UTC")))
        assertEquals("10-02 02:01", LiveTime.dateTime(value, ZoneId.of("UTC")))
    }
    @Test fun missingOrMalformedTimestampNeverInventsAClockTime() {
        assertEquals("", LiveTime.short(""))
        assertEquals("", LiveTime.short("null"))
        assertEquals("", LiveTime.short("not-a-date"))
        assertEquals("", LiveTime.dateTime("1234"))
    }
}
