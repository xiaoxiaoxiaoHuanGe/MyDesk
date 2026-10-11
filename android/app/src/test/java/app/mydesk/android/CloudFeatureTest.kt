package app.mydesk.android

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class CloudFeatureTest {
    @Test fun randomPredictionMatchesWebAndSmallIncrementsAndCap() {
        val p=StepPlanInput.parse("80","80","3","975",10)
        assertEquals(72,p.low);assertEquals(88,p.high);assertEquals(12,p.minCount);assertEquals(14,p.maxCount)
        assertEquals("33～39",p.durationLabel)
        val small=StepPlanInput.parse("1","1","1","1",10);assertEquals(1,small.low);assertEquals(1,small.high)
        assertTrue(StepPlanInput.parse("29999","80","1","29999",10).estimateLabel.contains("30000～30000"))
    }
    @Test fun readCutoffDoesNotConsumeANewerNotification() {
        val row=buildJsonObject {put("seq",10);put("id","message")}
        assertTrue(inboxReadMatches(row,buildJsonObject {put("through_seq",10)}))
        assertFalse(inboxReadMatches(row,buildJsonObject {put("through_seq",9)}))
        assertTrue(inboxReadMatches(row,buildJsonObject {put("ids",JsonArray(listOf(JsonPrimitive("message"))))}))
    }
    @Test fun logoutClearsInboxCacheAndPendingReads()=runBlocking {
        val store=NativeStore(RuntimeEnvironment.getApplication(),memory=true)
        try {
            store.cacheInbox(listOf(buildJsonObject {put("id","message");put("seq",1);put("body","📱 验证码") }))
            store.enqueueInboxRead(buildJsonObject {put("through_seq",1)})
            assertEquals(1,store.inboxCache().size);assertEquals(1,store.inboxPending().size)
            store.clear();assertTrue(store.inboxCache().isEmpty());assertTrue(store.inboxPending().isEmpty())
        } finally {store.close()}
    }
    @Test fun externalNotificationClickKeepsItsMessageReference() {
        val target=NotificationNavigation.target(Intent().putExtra("event_kind","inbox").putExtra("event_reference","a".repeat(32)),buildJsonObject {})
        assertEquals(NotificationSection.INBOX,target.section);assertEquals("a".repeat(32),target.reference)
    }
}
