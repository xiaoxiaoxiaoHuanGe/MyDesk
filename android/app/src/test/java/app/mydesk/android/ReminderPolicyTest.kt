package app.mydesk.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReminderPolicyTest {
    @Test fun nextMinuteRollsTheDateAndHourAtMidnight() {
        val result=nextReminderMinute(Instant.parse("2026-10-16T15:59:50Z"),java.time.ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-10-17T00:00+08:00[Asia/Shanghai]",result.toString())
    }

    @Test fun recurringCompletionAndSnoozeKeepTheFixedClockAndPauseSurvivesOfflineRollover() {
        val rule=ReminderRepeat((1..5).toList(),9,0,"Asia/Shanghai","seed")
        val first=Reminder("r","提醒","2026-10-16T01:00:00Z","pending","v",rule,"2026-10-16T01:00:00Z")
        val snoozed=ReminderPolicy.project(first,PendingAction("s","r","v","snooze",30,"2026-10-16T01:00:00Z"))
        val complete=ReminderPolicy.project(snoozed,PendingAction("c","r",snoozed.revision,"complete",10,"2026-10-16T01:31:00Z"))
        assertEquals("2026-10-19T01:00:00Z",complete.remindAt)
        val paused=PendingAction("p","r","v","pause",10,"2026-10-16T00:00:00Z")
        assertEquals("paused",ReminderPolicy.visible(listOf(first),listOf(paused),Instant.parse("2026-10-21T01:00:00Z")).single().status)
    }
    @Test fun cachedRecurringReminderAdvancesOfflineAndOldNotificationCannotCompleteTheNextOne() {
        val cached=deskJson.decodeFromString<Reminder>("""{"id":"repeat","title":"提醒","remind_at":"2020-10-16T01:00:00Z","status":"pending","revision":"seed:1602810000","scheduled_at":"2020-10-16T01:00:00Z","repeat":{"days":[1,2,3,4,5],"hour":9,"minute":0,"timezone":"Asia/Shanghai","seed":"seed"}}""")
        val result=ReminderPolicy.visible(listOf(cached),emptyList()).single()
        assertTrue(Instant.parse(result.remindAt)>Instant.parse("2020-10-16T01:00:00Z"))
        assertNotEquals(cached.revision,result.revision)
        val old=PendingAction("op","repeat",cached.revision,"complete",10,Instant.now().toString())
        assertEquals(result.revision,ReminderPolicy.project(result,old).revision)
    }
    private val now = Instant.parse("2026-10-02T08:00:00Z")
    private fun reminder(revision: String = "v1") = Reminder("r1", "续费 VPS", "2026-10-02T08:30:00Z", "pending", revision)

    @Test fun snoozeKeepsTheOfflineActionTimeAndDoesNotMoveOnRetry() {
        val action = PendingAction("op1", "r1", "v1", "snooze", 30, now.toString())
        val result = ReminderPolicy.project(reminder(), action)
        assertEquals("2026-10-02T08:30:00Z", result.remindAt)
        assertEquals("snoozed", result.status)
        assertEquals("local:op1", result.revision)
    }

    @Test fun completedOfflineReminderCannotReappearFromTheOldServerSnapshot() {
        val action = PendingAction("op1", "r1", "v1", "complete", 10, now.toString())
        val result = ReminderPolicy.visible(listOf(reminder()), listOf(action))
        assertTrue(result.isEmpty())
    }

    @Test fun changedServerRevisionWinsOverAnOldOfflineOperation() {
        val action = PendingAction("op1", "r1", "v1", "snooze", 30, now.toString())
        val result = ReminderPolicy.visible(listOf(reminder("v2")), listOf(action))
        assertEquals("v2", result.single().revision)
    }

    @Test fun chainedOfflineSnoozesRemainOrderedAndThenCompletionRemovesIt() {
        val first = PendingAction("op1", "r1", "v1", "snooze", 10, now.toString())
        val second = PendingAction("op2", "r1", "local:op1", "complete", 10, now.plusSeconds(5).toString())
        assertTrue(ReminderPolicy.visible(listOf(reminder()), listOf(first,second)).isEmpty())
    }

    @Test fun stepsAndServerAddressValidateBeforeNetworkUse() {
        assertEquals(18888, InputRules.steps("18888"))
        assertEquals(30000, InputRules.steps("30000"))
        listOf("-1","1.5","30001","100000","","1e3").forEach { assertTrue(runCatching { InputRules.steps(it) }.isFailure) }
        assertEquals("https://192.168.1.143:8443", InputRules.server("https://192.168.1.143:8443/"))
        listOf("http://example.com","https://user:pass@example.com","https://example.com/path","file:///tmp").forEach {
            assertTrue(runCatching { InputRules.server(it) }.isFailure)
        }
    }
}
