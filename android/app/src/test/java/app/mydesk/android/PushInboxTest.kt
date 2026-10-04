package app.mydesk.android

import android.app.NotificationManager
import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class PushInboxTest {
    private fun message()=PushMessage("a".repeat(32),"b".repeat(32),"device1","alert","alerts","服务器","服务器离线","server1","down",Instant.now(),Instant.now().plusSeconds(3600))
    @Test fun tappingServerNotificationCarriesBusinessReferenceIntoExplicitAppIntent()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancelAll();shadowOf(manager).setNotificationsEnabled(true)
        val inbox=PushInbox(context,memory=true)
        try {
            PushDelivery(context,inbox).receive(message())
            shadowOf(manager).allNotifications.single().contentIntent.send()
            val intent=shadowOf(context).nextStartedActivity
            assertEquals("app.mydesk.android.MainActivity",intent.component?.className)
            assertEquals("alert",intent.getStringExtra("event_kind"))
            assertEquals("server1",intent.getStringExtra("event_reference"))
        } finally {inbox.close()}
    }
    @Test fun versionOneDatabaseKeepsUnacknowledgedReceiptsAfterMigration()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("mydesk-push.db")
        context.openOrCreateDatabase("mydesk-push.db",Context.MODE_PRIVATE,null).use {db ->
            db.execSQL("CREATE TABLE push_receipts(eventId TEXT NOT NULL PRIMARY KEY,serverId TEXT NOT NULL,deviceId TEXT NOT NULL,phase TEXT NOT NULL,acknowledged INTEGER NOT NULL)")
            db.execSQL("INSERT INTO push_receipts VALUES ('legacy','server','device','display_requested',0)")
            db.version=1
        }
        val inbox=PushInbox(context)
        try {assertEquals("legacy",inbox.pending().single().eventId);assertEquals("display_requested",inbox.pending().single().phase);inbox.clear()}
        finally {inbox.close()}
    }
    @Test fun duplicateAndProcessRestartPreserveSingleDisplayAndPendingReceipt()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val inbox=PushInbox(context)
        inbox.clear()
        val delivery=PushDelivery(context,inbox)
        assertTrue(delivery.receive(message()))
        assertFalse(delivery.receive(message()))
        assertEquals(1,shadowOf(manager).allNotifications.size)
        assertEquals("display_requested",inbox.pending().single().phase)
        inbox.close()
        val reopened=PushInbox(context)
        try {
            assertFalse(PushDelivery(context,reopened).receive(message()))
            val receipt=reopened.pending().single()
            reopened.acknowledge(receipt.eventId)
            assertTrue(reopened.pending().isEmpty())
            assertFalse(PushDelivery(context,reopened).receive(message()))
            reopened.clear()
        } finally { reopened.close() }
    }
    @Test fun deniedPermissionProducesBlockedReceiptWithoutSystemNotification()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancelAll(); shadowOf(manager).setNotificationsEnabled(false)
        val inbox=PushInbox(context,memory=true)
        try {
            assertTrue(PushDelivery(context,inbox).receive(message()))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            assertEquals("blocked",inbox.pending().single().phase)
        } finally { inbox.close() }
    }
    @Test fun silentSyncNeverDisplaysAndRemoteReminderSharesAlarmDeduplication()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancelAll();shadowOf(manager).setNotificationsEnabled(true)
        val inbox=PushInbox(context,memory=true)
        val store=NativeStore(context,memory=true)
        try {
            val delivery=PushDelivery(context,inbox)
            val binding=PushBinding("b".repeat(32),"device1")
            delivery.receive(message().copy(kind="sync"))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            assertEquals("received",inbox.pending().single().phase)
            store.accept("""{"reminders":[{"id":"r1","title":"到期提醒","remind_at":"2020-01-01T00:00:00Z","status":"pending","revision":"v1"}]}""")
            val remote=message().copy(eventId="c".repeat(32),kind="reminder",channel="reminders",reference="r1",revision="v1")
            delivery.receive(remote)
            val scheduler=ReminderScheduler(context,store)
            delivery.reconcile(binding,store,scheduler)
            assertEquals(1,shadowOf(manager).allNotifications.size)
            assertFalse(scheduler.deliver("r1","v1"))
            delivery.reconcile(binding,store,scheduler)
            assertEquals("display_requested",inbox.pending().first {it.eventId==remote.eventId}.phase)
            assertEquals(1,shadowOf(manager).allNotifications.size)
        } finally {inbox.close();store.close()}
    }
    @Test fun remoteReminderMustMatchLatestSyncedRevision()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancelAll();shadowOf(manager).setNotificationsEnabled(true)
        val inbox=PushInbox(context,memory=true)
        val store=NativeStore(context,memory=true)
        try {
            store.accept("""{"reminders":[{"id":"r1","title":"提醒","remind_at":"2020-01-01T00:00:00Z","status":"pending","revision":"new"}]}""")
            val delivery=PushDelivery(context,inbox)
            delivery.receive(message().copy(kind="reminder",channel="reminders",reference="r1",revision="old"))
            delivery.reconcile(PushBinding("b".repeat(32),"device1"),store,ReminderScheduler(context,store))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            assertEquals("superseded",inbox.pending().single().phase)
        } finally {inbox.close();store.close()}
    }
}
