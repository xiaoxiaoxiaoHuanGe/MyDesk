package app.mydesk.android

import kotlinx.coroutines.runBlocking
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
