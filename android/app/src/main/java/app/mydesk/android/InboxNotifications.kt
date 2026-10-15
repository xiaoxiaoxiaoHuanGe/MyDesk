package app.mydesk.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Shared by REST/WorkManager, live snapshots and native push. Receipt keys are
 * namespaced in the existing delivery store and cleared with the login session. */
class InboxNotifications(private val context: Context,private val store: NativeStore) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val lock=Mutex()
    init {DeskNotificationChannels.create(context)}

    suspend fun sync(snapshot: JsonObject,client: MyDeskApi?=null)=lock.withLock {
        val inbox=snapshot.obj("inbox")
        val latest=inbox["latest_seq"]?.jsonPrimitive?.longOrNull ?: return@withLock
        val previous=store.delivered(CURSOR)?.toLongOrNull()
        if(previous==null) {
            // Installing/upgrading or signing in must not replay existing history.
            store.markDelivered(CURSOR,latest.toString())
            return@withLock
        }
        if(latest<=previous)return@withLock
        val messages=mutableListOf<JsonObject>()
        if(client==null) messages+=inbox.rows("recent")
        else {
            var before: Long?=null
            while(true) {
                val page=client.request("/api/inbox?limit=100"+(before?.let {"&before=$it"} ?: "")).jsonObject
                val rows=page.rows("items")
                messages+=rows
                if(rows.isEmpty() || rows.any {it.text("seq").toLong()<=previous})break
                val next=page["next_cursor"]?.jsonPrimitive?.longOrNull ?: break
                require(next>0 && (before==null || next<before)) {"通知分页游标无效"}
                before=next
            }
        }
        val fresh=messages.filter {it.text("seq").toLong() in (previous+1)..latest}
            .distinctBy {it.text("id")}.sortedBy {it.text("seq").toLong()}
        store.cacheInbox(fresh)
        val reads=store.inboxPending().map {deskJson.parseToJsonElement(it.json).jsonObject}
        for(row in fresh) {
            if(row["read_at"]?.jsonPrimitive?.contentOrNull!=null || reads.any {inboxReadMatches(row,it)})continue
            display(row.text("id"),"${row.text("source_name")} · ${row.text("title")}",row.text("body",row.text("summary","")))
        }
        store.markDelivered(CURSOR,latest.toString())
    }

    suspend fun receive(message: PushMessage): String=lock.withLock {
        val cached=store.inboxMessage(message.reference)
        if(cached?.get("read_at")?.jsonPrimitive?.contentOrNull!=null)return@withLock "received"
        val pending=store.inboxPending().map {deskJson.parseToJsonElement(it.json).jsonObject}
        if(cached!=null && pending.any {inboxReadMatches(cached,it)})return@withLock "received"
        display(message.reference,message.title,message.body)
    }

    private suspend fun display(id: String,title: String,body: String): String {
        if(!id.matches(Regex("[a-f0-9]{32}")))return "blocked"
        val receipt="inbox-notice:$id"
        store.delivered(receipt)?.let {return it}
        val channel=DeskNotificationChannels.INBOX
        val phase=if(!PushDelivery.allowed(context) || manager.getNotificationChannel(channel)?.importance==NotificationManager.IMPORTANCE_NONE) "blocked"
        else {
            val open=PendingIntent.getActivity(context,0,Intent().setClassName(context,"app.mydesk.android.MainActivity")
                .setData(Uri.parse("mydesk://inbox/$id"))
                .putExtra("open_workbench",true).putExtra("event_kind","inbox").putExtra("event_reference",id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.take(120)).setContentText(body.take(500))
                .setStyle(Notification.BigTextStyle().bigText(body.take(4000)))
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_MESSAGE).build()
            manager.notify("inbox:$id",2,notification)
            "display_requested"
        }
        store.markDelivered(receipt,phase)
        return phase
    }

    private companion object {const val CURSOR="inbox-notification-cursor"}
}
