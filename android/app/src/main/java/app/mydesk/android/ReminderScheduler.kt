package app.mydesk.android

import android.content.Context
import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class ReminderScheduler(val context: Context, val store: NativeStore) {
    private val alarms=context.getSystemService(AlarmManager::class.java)
    private val notifications=context.getSystemService(NotificationManager::class.java)
    private val prefs=context.getSharedPreferences("alarm-state",Context.MODE_PRIVATE)
    private val lock=Mutex()
    init {
        DeskNotificationChannels.create(context)
    }
    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(context,1,
        Intent().setClassName(context,"app.mydesk.android.AlarmReceiver").setAction("MYDESK_ALARM")
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelScheduledAlarm() {
        alarms.cancel(alarmIntent())
        // Updating a PendingIntent's extras doesn't upgrade an existing token's delivery flags.
        // Retire the old key when upgrading so its ordinary-priority alarm cannot remain queued.
        val legacy=PendingIntent.getBroadcast(context,0,
            Intent().setClassName(context,"app.mydesk.android.AlarmReceiver").setAction("MYDESK_ALARM"),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if(legacy != null) {alarms.cancel(legacy);legacy.cancel()}
    }
    suspend fun apply(reminders: List<Reminder>) = lock.withLock {
        prefs.all.keys.filter { it.startsWith("shown:") }.forEach { key ->
            val id=key.removePrefix("shown:")
            if (reminders.none { it.id == id && it.revision == prefs.getString(key,"") }) {
                notifications.cancel(id,1); prefs.edit().remove(key).apply()
            }
        }
        val undelivered=reminders.filter { store.delivered(it.id) != it.revision }
        cancelScheduledAlarm()
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("reminders")?.importance == NotificationManager.IMPORTANCE_NONE || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return@withLock
        val next=undelivered.minByOrNull { Instant.parse(it.remindAt) } ?: return@withLock
        val whenMillis=maxOf(System.currentTimeMillis()+500,Instant.parse(next.remindAt).toEpochMilli())
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            // User-chosen deadlines are visible alarm reservations. Android treats these
            // as wake-up alarms instead of deferrable ordinary background work.
            val show=PendingIntent.getActivity(context,0,
                Intent().setClassName(context,"app.mydesk.android.MainActivity")
                    .setData(Uri.parse("mydesk://reminder/${next.id}"))
                    .putExtra("reminder_id",next.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(whenMillis,show),alarmIntent())
            ReminderDiagnostics.event("scheduled","mode=alarm_clock target_ms=$whenMillis system_clock_matches=${runCatching {alarms.nextAlarmClock?.triggerTime == whenMillis}.getOrNull()} foreground=true")
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMillis,alarmIntent())
            ReminderDiagnostics.event("scheduled","mode=inexact target_ms=$whenMillis foreground=true")
        }
    }
    suspend fun deliver(id: String, revision: String): Boolean = lock.withLock {
        val reminder=store.visible().firstOrNull { it.id == id } ?: return@withLock false
        if (!DeliveryPolicy.shouldDisplay(reminder,revision,store.delivered(id),Instant.now())) {
            ReminderDiagnostics.event("display_skipped","reason=not_due_or_already_delivered")
            return@withLock false
        }
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("reminders")?.importance == NotificationManager.IMPORTANCE_NONE || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return@withLock false
        val open=PendingIntent.getActivity(context,0,Intent().setClassName(context,"app.mydesk.android.MainActivity")
            .setData(Uri.parse("mydesk://reminder/$id")).putExtra("reminder_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String,minutes: Int=10,choices: Boolean=false)=PendingIntent.getBroadcast(context,0,
            Intent().setClassName(context,"app.mydesk.android.NotificationActionReceiver").setData(Uri.parse("mydesk://action/$id/$revision/$name/$minutes"))
                .putExtra("id",id).putExtra("revision",revision).putExtra("action",name).putExtra("minutes",minutes).putExtra("choose_snooze",choices),
            PendingIntent.FLAG_UPDATE_CURRENT or if(choices) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        val snoozeInput=RemoteInput.Builder(NotificationInput.SNOOZE_KEY).setLabel("延后多久？")
            .setChoices(arrayOf("10 分钟","30 分钟","60 分钟")).setAllowFreeFormInput(false).build()
        val notice=Notification.Builder(context,"reminders").setSmallIcon(R.drawable.ic_notification).setContentTitle(reminder.title)
            .setContentText("到提醒时间了 · 打开 MyDesk 查看或延后").setContentIntent(open).setAutoCancel(false).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setStyle(Notification.BigTextStyle().bigText("到提醒时间了。可以完成，或选择延后 10 / 30 / 60 分钟。"))
            .addAction(Notification.Action.Builder(null,"完成",action("complete")).build())
            .addAction(Notification.Action.Builder(null,"延后…",action("snooze",choices=true)).addRemoteInput(snoozeInput).build())
            .addAction(Notification.Action.Builder(null,"打开",open).build()).build()
        notifications.notify(id,1,notice)
        ReminderDiagnostics.event("notify_requested")
        prefs.edit().putString("shown:$id",revision).apply()
        store.markDelivered(id,revision)
        true
    }
    suspend fun cancelAll()=lock.withLock {
        cancelScheduledAlarm(); notifications.cancelAll(); prefs.edit().clear().apply()
    }
}
