package app.mydesk.android

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.*
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class DeskRepositoryTest {
    private val snapshot="""{"reminders":[{"id":"r1","title":"备份","remind_at":"2099-10-02T08:30:00Z","status":"pending","revision":"v1"}]}"""
    @Test @Config(sdk=[32]) fun leavingTheActivityAfterCacheCommitStillArmsTheDeadline()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(android.app.AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            val scheduler=ReminderScheduler(context,store)
            val foregroundJob=Job()
            val foreground=launch(foregroundJob) {
                persistAndScheduleReminders({
                    store.accept(snapshot)
                    // onStop cancels the foreground sync just after its Room transaction commits.
                    foregroundJob.cancel()
                },{scheduler.apply(store.visible())})
            }
            foreground.join()
            assertTrue(foreground.isCancelled)
            assertEquals("r1",store.visible().single().id)
            assertNotNull("已落盘的提醒不能因返回桌面而失去系统闹钟",manager.nextAlarmClock)
            assertEquals(java.time.Instant.parse("2099-10-02T08:30:00Z").toEpochMilli(),manager.nextAlarmClock.triggerTime)
        } finally {store.close()}
    }
    @Test @Config(sdk=[32]) fun leavingAfterCompletingAReminderAlsoCancelsItsOldAlarm()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(android.app.AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            val scheduler=ReminderScheduler(context,store)
            store.accept(snapshot);scheduler.apply(store.visible())
            assertNotNull(manager.nextAlarmClock)
            val foregroundJob=Job()
            val foreground=launch(foregroundJob) {
                persistAndScheduleReminders({
                    store.enqueue("r1","v1","complete")
                    foregroundJob.cancel()
                },{scheduler.apply(store.visible())})
            }
            foreground.join()
            assertTrue(foreground.isCancelled)
            assertTrue(store.visible().isEmpty())
            assertNull("已完成的提醒不能因同步被取消而留下旧闹钟",manager.nextAlarmClock)
        } finally {store.close()}
    }
    @Test fun lateSnapshotAndNotificationActionCannotRestoreDataAfterLogout()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=NativeStore(context,memory=true)
        try {
            store.accept(snapshot)
            val repo=DeskRepository(context,store,{null},{"a".repeat(32)},ReminderScheduler(context,store))
            repo.endSession { }
            repo.snapshot(snapshot)
            assertTrue(store.visible().isEmpty())
            assertTrue(runCatching {repo.act("r1","v1","complete")}.isFailure)
            assertTrue(store.pending().isEmpty())
        } finally {store.close()}
    }
    @Test fun chainedOfflineActionsSubmitTheRevisionReturnedByThePreviousReceipt() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=NativeStore(context,memory=true)
        val server=MockWebServer()
        var completed=false
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/mobile/devices") return MockResponse().setBody("""{"devices":[{"id":"${"a".repeat(32)}"}]}""")
                if (request.path == "/api/mobile/reminder-action") {
                    val action=deskJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                    if (action.text("action") == "snooze") return MockResponse().setBody(deskJson.parseToJsonElement(snapshot.replace("v1","v2")).jsonObject.rows("reminders").single().toString())
                    if (action.text("revision") != "v2") return MockResponse().setResponseCode(409).setBody("""{"error":"旧版本"}""")
                    completed=true
                    val result=deskJson.parseToJsonElement(snapshot.replace("v1","v3").replace("pending","completed")).jsonObject.rows("reminders").single()
                    return MockResponse().setBody(result.toString())
                }
                return MockResponse().setBody(if (completed) """{"reminders":[]}""" else snapshot.replace("v1","v2"))
            }
        }
        server.start()
        try {
            store.accept(snapshot)
            store.enqueue("r1","v1","snooze",30)
            store.enqueue("r1",store.visible().single().revision,"complete")
            val vault=object: SessionVault { override var cookie="test"; override var csrf="test" }
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault)
            val repo=DeskRepository(context,store,{api},{"a".repeat(32)},ReminderScheduler(context,store))
            assertTrue(repo.sync())
            assertTrue(completed)
            assertTrue(store.visible().isEmpty())
            assertTrue(store.pending().isEmpty())
        } finally { store.close(); server.shutdown() }
    }
    @Test fun offlineActionImmediatelyChangesCacheBeforeAnyNetworkCall() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=NativeStore(context,memory=true)
        try {
            store.accept(snapshot)
            val repo=DeskRepository(context,store,{null},{"a".repeat(32)},ReminderScheduler(context,store))
            repo.act("r1","v1","complete")
            assertTrue(store.visible().isEmpty())
            assertEquals(1,store.pending().size)
            assertFalse(repo.sync())
            assertEquals(1,store.pending().size)
        } finally { store.close() }
    }

    @Test fun conflictKeepsOtherOperationsAndRefreshesServerState() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=NativeStore(context,memory=true)
        val server=MockWebServer(); server.start()
        try {
            store.accept(snapshot); store.enqueue("r1","v1","complete")
            val vault=object: SessionVault { override var cookie="mydesk_session=test"; override var csrf="test" }
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault)
            val repo=DeskRepository(context,store,{api},{"a".repeat(32)},ReminderScheduler(context,store))
            server.enqueue(MockResponse().setBody("""{"devices":[{"id":"${"a".repeat(32)}"}]}"""))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"提醒已更新"}"""))
            server.enqueue(MockResponse().setBody(snapshot.replace("v1","v2")))
            assertTrue(repo.sync())
            assertTrue(store.pending().isEmpty())
            assertEquals("v2",store.visible().single().revision)
            assertEquals("/api/mobile/devices",server.takeRequest().path)
            val action=server.takeRequest()
            assertEquals("/api/mobile/reminder-action",action.path)
            assertEquals("v1",deskJson.parseToJsonElement(action.body.readUtf8()).jsonObject.text("revision"))
            assertEquals("/api/command",server.takeRequest().path)
        } finally { store.close(); server.shutdown() }
    }
}
