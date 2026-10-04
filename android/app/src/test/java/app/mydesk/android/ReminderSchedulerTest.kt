package app.mydesk.android

import android.app.NotificationManager
import android.app.AlarmManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=android.app.Application::class)
class ReminderSchedulerTest {
    @Test fun deadlineBroadcastUsesForegroundDeliveryPriorityWithoutOpeningAnActivity()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            ReminderScheduler(context,store).apply(listOf(Reminder("r1","提醒","2099-01-01T00:00:00Z","pending","v1")))
            val operation=shadowOf(manager).scheduledAlarms.single().operation!!
            val intent=shadowOf(operation).savedIntent
            assertTrue("到点广播应按时间敏感任务处理，而非普通后台广播",intent.flags and android.content.Intent.FLAG_RECEIVER_FOREGROUND != 0)
            assertEquals("app.mydesk.android.AlarmReceiver",intent.component?.className)
            assertTrue(operation.isImmutable)
        } finally {store.close()}
    }

    @Test fun upgradingFromTheOldBroadcastCancelsItsAlarmAndReplacesItsPriority()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val legacy=android.app.PendingIntent.getBroadcast(context,0,
            android.content.Intent().setClassName(context,"app.mydesk.android.AlarmReceiver").setAction("MYDESK_ALARM"),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,java.time.Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),legacy)
        val store=NativeStore(context,memory=true)
        try {
            ReminderScheduler(context,store).apply(listOf(Reminder("r1","提醒","2099-01-02T00:00:00Z","pending","v1")))
            val alarm=shadowOf(manager).scheduledAlarms.single()
            assertTrue(shadowOf(alarm.operation).savedIntent.flags and android.content.Intent.FLAG_RECEIVER_FOREGROUND != 0)
            assertEquals(java.time.Instant.parse("2099-01-02T00:00:00Z").toEpochMilli(),alarm.triggerAtTime)
        } finally {store.close()}
    }

    @Test fun userReminderIsRegisteredAsAVisibleAlarmClockThatCanWakeTheApp()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            val reminder=Reminder("future","后台提醒","2099-01-01T00:00:00Z","pending","v1")
            val scheduler=ReminderScheduler(context,store)
            scheduler.apply(listOf(reminder))
            val clock=manager.nextAlarmClock
            assertNotNull("用户设定的到点提醒需要向系统登记为可唤醒应用的闹钟预约",clock)
            assertEquals(java.time.Instant.parse(reminder.remindAt).toEpochMilli(),clock.triggerTime)
            val target=shadowOf(clock.showIntent).savedIntent
            assertEquals("app.mydesk.android.MainActivity",target.component?.className)
            assertEquals("future",target.getStringExtra("reminder_id"))
            scheduler.apply(emptyList())
            assertNull("删除提醒后应撤销系统闹钟预约",manager.nextAlarmClock)
        } finally {store.close()}
    }

    @Test fun deniedExactPermissionKeepsAnInexactFallbackWithoutClaimingAnAlarmClock()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            ReminderScheduler(context,store).apply(listOf(Reminder("r1","提醒","2099-01-01T00:00:00Z","pending","v1")))
            assertNull(manager.nextAlarmClock)
            assertEquals(1,shadowOf(manager).scheduledAlarms.size)
        } finally {store.close()}
    }

    @Test fun newReminderChannelUsesHighImportanceWithSoundVibrationAndBannerGuidance() {
        val context=RuntimeEnvironment.getApplication()
        val store=NativeStore(context,memory=true)
        try {
            ReminderScheduler(context,store)
            val channel=context.getSystemService(NotificationManager::class.java).getNotificationChannel("reminders")
            assertEquals(NotificationManager.IMPORTANCE_HIGH,channel.importance)
            assertNotNull(channel.sound)
            assertTrue("到期提醒应默认振动",channel.shouldVibrate())
            assertTrue(channel.description?.contains("横幅") == true)
        } finally {store.close()}
    }

    @Test fun existingUserReminderChannelChoiceIsPreservedWhenAppStarts() {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("reminders","即时提醒",NotificationManager.IMPORTANCE_LOW).apply {enableVibration(false)})
        val store=NativeStore(context,memory=true)
        try {
            ReminderScheduler(context,store)
            val channel=manager.getNotificationChannel("reminders")
            assertEquals(NotificationManager.IMPORTANCE_LOW,channel.importance)
            assertFalse(channel.shouldVibrate())
        } finally {store.close()}
    }
    @Test fun reminderNotificationOffersThreeSnoozeChoicesWithoutFreeText()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            store.accept("""{"reminders":[{"id":"r1","title":"选择延后","remind_at":"2020-01-01T00:00:00Z","status":"pending","revision":"v1"}]}""")
            assertTrue(ReminderScheduler(context,store).deliver("r1","v1"))
            val notice=shadowOf(manager).allNotifications.single()
            val snooze=notice.actions.first {it.title.toString().startsWith("延后")}
            assertNotNull("延后按钮应提供系统选项",snooze.remoteInputs)
            assertArrayEquals(arrayOf<CharSequence>("10 分钟","30 分钟","60 分钟"),snooze.remoteInputs.single().choices)
            assertFalse(snooze.remoteInputs.single().allowFreeFormInput)
            assertFalse(snooze.actionIntent.isImmutable)
            assertTrue(notice.actions.first {it.title == "完成"}.actionIntent.isImmutable)
        } finally {store.close()}
    }
    @Test fun disabledReminderChannelKeepsReminderEligibleWithoutImmediateAlarmLoop()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        try {
            store.accept("""{"reminders":[{"id":"r1","title":"提醒","remind_at":"2020-01-01T00:00:00Z","status":"pending","revision":"v1"}]}""")
            val scheduler=ReminderScheduler(context,store)
            manager.createNotificationChannel(android.app.NotificationChannel("reminders","即时提醒",NotificationManager.IMPORTANCE_NONE))
            assertFalse(scheduler.deliver("r1","v1"))
            assertNull(store.delivered("r1"))
            scheduler.apply(store.visible())
            assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        } finally {store.close()}
    }
    @Test fun disabledNotificationsDoNotCauseRepeatedImmediateAlarms() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=NativeStore(context,memory=true)
        try {
            shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            ReminderScheduler(context,store).apply(listOf(Reminder("r1","提醒","2020-01-01T00:00:00Z","pending","v1")))
            assertEquals(0,shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        } finally { store.close() }
    }
    @Test fun staleOrDuplicateAlarmDoesNotDisplayAnotherNotification() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=NativeStore(context,memory=true)
        try {
            store.accept("""{"reminders":[{"id":"r1","title":"测试提醒","remind_at":"2020-01-01T00:00:00Z","status":"pending","revision":"v1"}]}""")
            val scheduler=ReminderScheduler(context,store)
            assertFalse(scheduler.deliver("r1","old"))
            assertTrue(scheduler.deliver("r1","v1"))
            assertFalse(scheduler.deliver("r1","v1"))
            val notifications=shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            assertEquals(1,notifications.size)
            assertEquals("测试提醒",notifications.single().extras.getString("android.title"))
        } finally { store.close() }
    }
}
