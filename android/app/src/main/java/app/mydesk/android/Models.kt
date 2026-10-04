package app.mydesk.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.net.URI
import kotlinx.serialization.json.*

@Serializable
data class Reminder(val id: String, val title: String, @SerialName("remind_at") val remindAt: String,
                    val status: String, val revision: String)

@Serializable
data class PendingAction(val operationId: String, val reminderId: String, val revision: String,
                         val action: String, val minutes: Int, val occurredAt: String)

object ReminderPolicy {
    fun project(reminder: Reminder, action: PendingAction): Reminder {
        if (reminder.id != action.reminderId || reminder.revision != action.revision) return reminder
        return when (action.action) {
            "snooze" -> reminder.copy(remindAt=Instant.parse(action.occurredAt).plusSeconds(action.minutes * 60L).toString(), status="snoozed", revision="local:${action.operationId}")
            "complete" -> reminder.copy(status="completed", revision="local:${action.operationId}")
            "cancel" -> reminder.copy(status="cancelled", revision="local:${action.operationId}")
            else -> error("无效提醒操作")
        }
    }
    fun visible(reminders: List<Reminder>, actions: List<PendingAction>): List<Reminder> = reminders.map { r ->
        actions.fold(r) { current, a -> project(current, a) }
    }.filter { it.status in setOf("pending", "snoozed") }.sortedBy { Instant.parse(it.remindAt) }
    fun acknowledge(actions: List<PendingAction>, operationId: String, revision: String): List<PendingAction> =
        actions.filterNot { it.operationId == operationId }.map { if (it.revision == "local:$operationId") it.copy(revision=revision) else it }
    fun conflict(actions: List<PendingAction>, reminderId: String): List<PendingAction> = actions.filterNot { it.reminderId == reminderId }
}

object DeliveryPolicy {
    fun shouldDisplay(reminder: Reminder, revision: String, delivered: String?, now: Instant): Boolean =
        reminder.status in setOf("pending", "snoozed") && reminder.revision == revision && delivered != revision && Instant.parse(reminder.remindAt) <= now
}

object InputRules {
    fun deviceName(text: String): String {
        require(text.length <= 80 && text.none {it.isISOControl()} && text.isNotBlank()) {"设备名称需要 1–80 个字符，且不能包含换行或控制字符"}
        return text.trim()
    }
    fun steps(text: String): Int {
        require(text.matches(Regex("[0-9]{1,6}"))) { "请输入 0–30000 的整数" }
        return text.toInt().also { require(it <= 30000) { "步数不能超过 30000" } }
    }
    fun server(text: String): String {
        val value=text.trim().trimEnd('/')
        val uri=URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null) { "请输入不带路径的 HTTPS 服务器地址" }
        return value
    }
}

val deskJson = Json { ignoreUnknownKeys=true; encodeDefaults=true }
fun JsonObject.text(key: String, fallback: String = "—") = (this[key] as? JsonPrimitive)?.contentOrNull ?: fallback
fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: buildJsonObject { }
fun JsonObject.rows(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
