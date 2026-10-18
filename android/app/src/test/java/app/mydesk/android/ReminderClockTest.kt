package app.mydesk.android

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ReminderClockTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    @Test fun untouchedSelectionTracksMinuteAndCrossesMidnight() {
        val selected=LocalDateTime.parse("2026-10-18T23:59")
        assertEquals(LocalDateTime.parse("2026-10-19T00:01"),reminderDraftTime(selected,false,Instant.parse("2026-10-18T16:00:01Z"),zone))
    }
    @Test fun manualSelectionIsPreservedEvenIfTheClockPassesIt() {
        val selected=LocalDateTime.parse("2026-10-18T15:00")
        assertEquals(selected,reminderDraftTime(selected,true,Instant.parse("2026-10-18T08:00:00Z"),zone))
    }
}
