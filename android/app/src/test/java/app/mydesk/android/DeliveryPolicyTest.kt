package app.mydesk.android

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DeliveryPolicyTest {
    private val now = Instant.parse("2026-10-02T08:00:00Z")
    private val due = Reminder("r1", "到期", now.minusSeconds(1).toString(), "pending", "v1")

    @Test fun alarmOnlyDisplaysCurrentDueRevisionOnce() {
        assertTrue(DeliveryPolicy.shouldDisplay(due, "v1", null, now))
        assertFalse(DeliveryPolicy.shouldDisplay(due, "old", null, now))
        assertFalse(DeliveryPolicy.shouldDisplay(due, "v1", "v1", now))
        assertFalse(DeliveryPolicy.shouldDisplay(due.copy(status="completed"), "v1", null, now))
        assertFalse(DeliveryPolicy.shouldDisplay(due.copy(remindAt=now.plusSeconds(60).toString()), "v1", null, now))
    }

    @Test fun serverReceiptRebasesOnlyDirectDependentOperations() {
        val first = PendingAction("op1", "r1", "v1", "snooze", 10, now.toString())
        val second = PendingAction("op2", "r1", "local:op1", "snooze", 30, now.toString())
        val third = PendingAction("op3", "r1", "local:op2", "complete", 10, now.toString())
        val result = ReminderPolicy.acknowledge(listOf(first, second, third), "op1", "server-v2")
        assertEquals(listOf("op2","op3"), result.map { it.operationId })
        assertEquals("server-v2", result.first().revision)
        assertEquals("local:op2", result.last().revision)
    }

    @Test fun conflictDiscardsOnlyTheSameReminderChain() {
        val rejected = PendingAction("op1", "r1", "v1", "complete", 10, now.toString())
        val other = rejected.copy(operationId="op2", reminderId="r2")
        assertEquals(listOf(other), ReminderPolicy.conflict(listOf(rejected,other), "r1"))
    }
}
