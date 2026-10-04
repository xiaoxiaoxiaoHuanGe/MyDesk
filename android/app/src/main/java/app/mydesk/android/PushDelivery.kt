package app.mydesk.android

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

class PushDelivery(private val context: Context,private val inbox: PushInbox) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    init {
        DeskNotificationChannels.create(context)
    }
    suspend fun receive(message: PushMessage): Boolean=inbox.receive(message) {
        if(message.kind in setOf("sync","reminder")) "received"
        else if(!allowed(context) || manager.getNotificationChannel(message.channel)?.importance == NotificationManager.IMPORTANCE_NONE) "blocked"
        else {
            val open=PendingIntent.getActivity(context,0,Intent().setClassName(context,"app.mydesk.android.MainActivity")
                .setData(Uri.parse("mydesk://event/${message.eventId}")).putExtra("event_id",message.eventId)
                .putExtra("open_workbench",true)
                .putExtra("event_kind",message.kind).putExtra("event_reference",message.reference)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(context,message.channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(message.title).setContentText(message.body).setStyle(Notification.BigTextStyle().bigText(message.body))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(if(message.kind == "alert") Notification.CATEGORY_ERROR else Notification.CATEGORY_STATUS).build()
            manager.notify("event:${message.eventId}",2,notification)
            "display_requested"
        }
    }
    /** Called only after an authenticated full snapshot has refreshed the reminder cache. */
    suspend fun reconcile(binding: PushBinding,store: NativeStore,scheduler: ReminderScheduler) {
        for(receipt in inbox.pending()) {
            if(receipt.kind != "reminder" || receipt.phase != "received" || receipt.serverId != binding.serverId || receipt.deviceId != binding.deviceId || receipt.registrationId != binding.registrationId) continue
            val reminder=store.visible().firstOrNull {it.id == receipt.reference && it.revision == receipt.revision}
            if(reminder == null || receipt.expiresAt <= System.currentTimeMillis()) inbox.phase(receipt.eventId,"superseded")
            else if(!allowed(context) || manager.getNotificationChannel("reminders")?.importance == NotificationManager.IMPORTANCE_NONE) inbox.phase(receipt.eventId,"blocked")
            else if(scheduler.deliver(reminder.id,reminder.revision)) inbox.phase(receipt.eventId,"display_requested")
        }
    }
    companion object {
        fun allowed(context: Context): Boolean=context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
}
