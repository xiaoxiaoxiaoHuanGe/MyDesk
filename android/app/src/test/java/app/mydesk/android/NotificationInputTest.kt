package app.mydesk.android

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.serialization.json.jsonObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=android.app.Application::class)
class NotificationInputTest {
    private fun intent()=Intent().putExtra("id","r1").putExtra("revision","v1").putExtra("action","snooze").putExtra("choose_snooze",true)
    private fun choose(intent: Intent,text: String): Intent {
        val input=RemoteInput.Builder(NotificationInput.SNOOZE_KEY).setChoices(arrayOf("10 分钟","30 分钟","60 分钟")).setAllowFreeFormInput(false).build()
        RemoteInput.addResultsToIntent(arrayOf(input),intent,Bundle().apply {putCharSequence(NotificationInput.SNOOZE_KEY,text)})
        return intent
    }
    @Test fun selectedSnoozeDurationsArePersistedOfflineAndRejectOldVersion()=runBlocking {
        for(minutes in listOf(10,30,60)) {
            val action=NotificationInput.action(choose(intent(),"$minutes 分钟"))
            assertNotNull(action)
            val store=NativeStore(RuntimeEnvironment.getApplication(),memory=true)
            try {
                store.accept("""{"reminders":[{"id":"r1","title":"备份","remind_at":"2020-01-01T00:00:00Z","status":"pending","revision":"v1"}]}""")
                val now=java.time.Instant.now()
                store.enqueue(action!!.id,action.revision,action.action,action.minutes)
                val pending=store.pending().single()
                assertEquals(minutes,pending.minutes)
                assertTrue(java.time.Instant.parse(store.visible().single().remindAt)>=now.plusSeconds(minutes*60L))
                assertTrue(runCatching {store.enqueue(action.id,action.revision,action.action,action.minutes)}.isFailure)
            } finally {store.close()}
        }
    }
    @Test fun missingOrUnrecognizedChoiceDoesNotBecomeTenMinuteSnooze() {
        assertNull(NotificationInput.action(intent()))
        assertNull(NotificationInput.action(choose(intent(),"900 分钟")))
        assertNull(NotificationInput.action(choose(intent(),"30 分钟").putExtra("action","delete_all")))
    }
    @Test fun priorVersionTenMinuteActionAndCompleteStillWork() {
        val legacy=intent().putExtra("choose_snooze",false).putExtra("minutes",10)
        assertEquals(10,NotificationInput.action(legacy)?.minutes)
        assertEquals("complete",NotificationInput.action(legacy.putExtra("action","complete"))?.action)
        assertNull(NotificationInput.action(legacy.putExtra("id","")))
    }
    private val snapshot=deskJson.parseToJsonElement("""{"tasks":[{"task_id":"t1"}],"wxstep":{"id":"w1"},"feeds":{"servers":{"data":{"items":[{"id":"s1"}]}},"network":{"data":{"nodes":[{"name":"香港"}]}}}}""").jsonObject
    private fun event(kind: String,reference: String)=Intent().putExtra("event_kind",kind).putExtra("event_reference",reference).putExtra("open_workbench",true)
    @Test fun knownBusinessReferencesSelectTheirMatchingCard() {
        val cases=listOf(Triple("task","t1",NotificationSection.TASKS),Triple("task","w1",NotificationSection.STEPS),Triple("alert","s1",NotificationSection.SERVERS),Triple("alert","servers",NotificationSection.SERVERS),Triple("alert","network",NotificationSection.NETWORK),Triple("alert","Internet",NotificationSection.NETWORK),Triple("alert","香港",NotificationSection.NETWORK),Triple("alert","mail",NotificationSection.MAIL))
        for((kind,id,section) in cases) assertEquals(NotificationTarget(section,id),NotificationNavigation.target(event(kind,id),snapshot))
    }
    @Test fun unknownReferenceFallsBackWithinAppWithoutOpeningPayloadUrl() {
        assertEquals(NotificationSection.ATTENTION,NotificationNavigation.target(event("alert","https://example.com"),snapshot).section)
        assertEquals(NotificationSection.TASKS,NotificationNavigation.target(event("task","missing"),snapshot).section)
        assertEquals(NotificationSection.WORKBENCH,NotificationNavigation.target(event("test",""),snapshot).section)
        assertEquals(NotificationSection.WORKBENCH,NotificationNavigation.target(event("bogus","s1"),snapshot).section)
    }
    @Test fun reminderTapRetainsIdForScrollingToTheActualReminder() {
        assertEquals(NotificationTarget(NotificationSection.REMINDERS,"r1"),NotificationNavigation.target(Intent().putExtra("reminder_id","r1"),snapshot))
    }
}
