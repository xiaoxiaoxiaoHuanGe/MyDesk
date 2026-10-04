package app.mydesk.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReminderPolicyTest {
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
