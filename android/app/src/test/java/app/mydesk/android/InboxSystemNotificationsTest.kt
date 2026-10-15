package app.mydesk.android

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationChannel
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class InboxSystemNotificationsTest {
    private fun row(seq: Int,read: Boolean=false)=buildJsonObject {
        put("id",seq.toString(16).padStart(32,'0'));put("seq",seq)
        put("source_name","Codex 重置");put("title","新的重置消息 $seq")
        put("summary","预告内容 $seq");put("body","预告内容 $seq")
        put("read_at",if(read) JsonPrimitive("2026-10-15T15:00:00Z") else JsonNull)
    }
    private fun snapshot(vararg rows: JsonObject)=buildJsonObject {
        put("reminders",buildJsonArray {})
        put("inbox",buildJsonObject {
            put("supported",true);put("latest_seq",rows.maxOfOrNull {it.text("seq").toLong()} ?: 0)
            put("recent",JsonArray(rows.toList()))
        })
    }.toString()
    private fun repo(store: NativeStore)=RuntimeEnvironment.getApplication().let {context->
        DeskRepository(context,store,{null},{"a".repeat(32)},ReminderScheduler(context,store))
    }

    @Test fun newSnapshotMessagePostsAHighImportancePrivateNotificationAndOpensItsDetail()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            val repo=repo(store)
            repo.snapshot(snapshot(row(1)))
            assertTrue("首次同步不应补发历史消息",shadowOf(manager).allNotifications.isEmpty())
            repo.snapshot(snapshot(row(2),row(1)))
            val notifications=shadowOf(manager).allNotifications
            assertEquals("收件箱新消息必须触发系统通知",1,notifications.size)
            val notice=notifications.single()
            assertEquals(NotificationManager.IMPORTANCE_HIGH,manager.getNotificationChannel(notice.channelId).importance)
            assertEquals(Notification.VISIBILITY_PRIVATE,notice.visibility)
            assertEquals("Codex 重置 · 新的重置消息 2",notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("预告内容 2",notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            val intent=shadowOf(notice.contentIntent).savedIntent
            assertEquals("inbox",intent.getStringExtra("event_kind"))
            assertEquals(row(2).text("id"),intent.getStringExtra("event_reference"))
        } finally {store.close()}
    }

    @Test fun messageDoesNotRealertAfterRepeatedSnapshotOrProcessRestart()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        context.deleteDatabase("mydesk.db")
        var store=NativeStore(context)
        try {
            var repo=repo(store)
            repo.snapshot(snapshot(row(1)))
            repo.snapshot(snapshot(row(2),row(1)))
            assertEquals(1,shadowOf(manager).allNotifications.size)
            manager.cancelAll()
            repo.snapshot(snapshot(row(2),row(1)))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            store.close();store=NativeStore(context);repo=repo(store)
            repo.snapshot(snapshot(row(2),row(1)))
            assertTrue("重启后不能重新弹出已通知的消息",shadowOf(manager).allNotifications.isEmpty())
        } finally {store.close();context.deleteDatabase("mydesk.db")}
    }

    @Test fun readMessagesAndMessagesSeenWhilePermissionIsOffDoNotReplayLater()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            val repo=repo(store)
            repo.snapshot(snapshot(row(1)))
            repo.snapshot(snapshot(row(2,true),row(1)))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            shadowOf(manager).setNotificationsEnabled(false)
            repo.snapshot(snapshot(row(3),row(2,true),row(1)))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            shadowOf(manager).setNotificationsEnabled(true)
            repo.snapshot(snapshot(row(3),row(2,true),row(1)))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            repo.snapshot(snapshot(row(4),row(3),row(2,true)))
            assertEquals(1,shadowOf(manager).allNotifications.size)
        } finally {store.close()}
    }

    @Test fun lateSocketSnapshotAfterLogoutCannotShowANotification()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            val repo=repo(store)
            repo.snapshot(snapshot(row(1)))
            repo.endSession {}
            repo.snapshot(snapshot(row(2),row(1)))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
        } finally {store.close()}
    }

    @Test fun restBackgroundSyncFetchesNewMessagesBeyondTheThreeRecentRows()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val server=MockWebServer()
        var latest=1
        val pages=mutableListOf<String>()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body=when {
                    request.path=="/api/mobile/devices" -> """{"devices":[{"id":"${"a".repeat(32)}"}]}"""
                    request.path=="/api/command" -> if(latest==1)snapshot(row(1)) else snapshot(row(5),row(4),row(3))
                    request.path=="/api/inbox?limit=100" -> {
                        pages+=request.path!!
                        buildJsonObject {put("items",JsonArray(listOf(row(5),row(4))));put("next_cursor",4)}.toString()
                    }
                    request.path=="/api/inbox?limit=100&before=4" -> {
                        pages+=request.path!!
                        buildJsonObject {put("items",JsonArray(listOf(row(3),row(2),row(1))));put("next_cursor",JsonNull)}.toString()
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val store=NativeStore(context,memory=true)
        try {
            val vault=object: SessionVault {override var cookie="test";override var csrf="test"}
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault)
            val repo=DeskRepository(context,store,{api},{"a".repeat(32)},ReminderScheduler(context,store))
            assertTrue(repo.sync());latest=5;assertTrue(repo.sync())
            assertEquals("后台同步应补齐最近三条以外的新消息",4,shadowOf(manager).allNotifications.size)
            assertEquals(2,pages.size)
            assertNotNull(store.inboxMessage(row(2).text("id")))
        } finally {store.close();server.shutdown()}
    }

    @Test fun nativePushAndSnapshotShareTheSameMessageReceiptInEitherArrivalOrder()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        val inbox=PushInbox(context,memory=true)
        try {
            val repo=repo(store)
            val delivery=PushDelivery(context,inbox,repo.inboxNotifications)
            val now=Instant.now()
            fun push(seq: Int)=PushMessage(seq.toString(16).padStart(32,'e'),"b".repeat(32),"device","inbox","inbox",
                "Codex 重置 · 新消息","预告",row(seq).text("id"),"",now,now.plusSeconds(3600))
            repo.snapshot(snapshot(row(1)))
            assertTrue(delivery.receive(push(2)))
            assertEquals(1,shadowOf(manager).allNotifications.size)
            manager.cancelAll()
            repo.snapshot(snapshot(row(2),row(1)))
            assertTrue("先推送后同步，不能重复提醒",shadowOf(manager).allNotifications.isEmpty())
            repo.snapshot(snapshot(row(3),row(2),row(1)))
            assertEquals(1,shadowOf(manager).allNotifications.size)
            manager.cancelAll()
            assertTrue(delivery.receive(push(3)))
            assertTrue("先同步后推送，不能重复提醒",shadowOf(manager).allNotifications.isEmpty())
            assertTrue(inbox.pending().all {it.phase=="display_requested"})
        } finally {store.close();inbox.close()}
    }

    @Test fun legacyMutedChannelAndCurrentUserChannelSettingsArePreserved()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(NotificationChannel("inbox","外部通知",NotificationManager.IMPORTANCE_NONE))
        val store=NativeStore(context,memory=true)
        try {
            val repo=repo(store)
            repo.snapshot(snapshot(row(1)))
            repo.snapshot(snapshot(row(2),row(1)))
            assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel("inbox_messages").importance)
            assertTrue("升级不能绕过用户关闭的通知频道",shadowOf(manager).allNotifications.isEmpty())
        } finally {store.close()}
    }
}
