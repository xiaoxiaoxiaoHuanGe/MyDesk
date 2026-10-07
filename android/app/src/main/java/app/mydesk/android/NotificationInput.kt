package app.mydesk.android

import android.content.Intent
import android.app.RemoteInput
import kotlinx.serialization.json.JsonObject

data class ReminderNotificationAction(val id: String,val revision: String,val action: String,val minutes: Int)
object NotificationInput {
    const val SNOOZE_KEY="snooze_choice"
    fun action(intent: Intent): ReminderNotificationAction? {
        val id=intent.getStringExtra("id")?.takeIf {valid(it)} ?: return null
        val revision=intent.getStringExtra("revision")?.takeIf {valid(it)} ?: return null
        val action=intent.getStringExtra("action")?.takeIf {it in setOf("complete","snooze")} ?: return null
        val minutes=if(action == "complete") 10 else if(intent.getBooleanExtra("choose_snooze",false)) {
            when(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SNOOZE_KEY)?.toString()) {
                "10 分钟" -> 10
                "30 分钟" -> 30
                "60 分钟" -> 60
                else -> return null
            }
        } else intent.getIntExtra("minutes",10).takeIf {it in setOf(10,30,60)} ?: return null
        return ReminderNotificationAction(id,revision,action,minutes)
    }
    internal fun valid(value: String): Boolean=value.isNotBlank() && value.length <= 200 && value.none {it.isISOControl()}
}

enum class NotificationSection { WORKBENCH,ATTENTION,REMINDERS,TASKS,SERVERS,NETWORK,STEPS,MAIL,INBOX }
data class NotificationTarget(val section: NotificationSection,val reference: String="")
data class NotificationLaunch(val intent: Intent,val sequence: Long)
object NotificationNavigation {
    fun target(intent: Intent,snapshot: JsonObject): NotificationTarget {
        intent.getStringExtra("reminder_id")?.takeIf {NotificationInput.valid(it)}?.let {return NotificationTarget(NotificationSection.REMINDERS,it)}
        val reference=intent.getStringExtra("event_reference")?.takeIf {NotificationInput.valid(it)} ?: ""
        val section=when(intent.getStringExtra("event_kind")) {
            "inbox" -> NotificationSection.INBOX
            "task" -> if(reference.isNotEmpty() && snapshot.obj("wxstep").text("id","") == reference) NotificationSection.STEPS else NotificationSection.TASKS
            "alert" -> when {
                reference == "servers" || snapshot.obj("feeds").obj("servers").obj("data").rows("items").any {it.text("id","") == reference && reference.isNotEmpty()} -> NotificationSection.SERVERS
                reference in setOf("network","Internet") || snapshot.obj("feeds").obj("network").obj("data").rows("nodes").any {it.text("name","") == reference && reference.isNotEmpty()} -> NotificationSection.NETWORK
                reference == "mail" -> NotificationSection.MAIL
                else -> NotificationSection.ATTENTION
            }
            else -> NotificationSection.WORKBENCH
        }
        return NotificationTarget(section,reference)
    }
}
