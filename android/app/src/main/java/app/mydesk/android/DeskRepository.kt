package app.mydesk.android

import android.content.Context
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal suspend fun persistAndScheduleReminders(update: suspend ()->Unit,schedule: suspend ()->Unit) = withContext(NonCancellable) {
    // Finish the local commit and its system alarm together when onStop cancels foreground sync.
    // This runs under the repository's session lock. Network calls remain cancellable outside it.
    update()
    ReminderDiagnostics.event("cache_committed")
    schedule()
    ReminderDiagnostics.event("cache_scheduled")
}

class DeskRepository(val context: Context,val store: NativeStore,val api: ()->MyDeskApi?,val deviceId: ()->String,val scheduler: ReminderScheduler,val deviceName: ()->String={Build.MODEL.take(80).ifBlank {"Android 手机"}}) {
    private val syncLock=Mutex()
    private var sessionOpen=true
    var conflictMessage: String?=null
        private set
    var registration: JsonObject=buildJsonObject { }
        private set
    suspend fun register()=syncLock.withLock {
        val client=api() ?: error("请先设置服务器地址")
        client.request("/api/mobile/devices","POST",buildJsonObject {
            put("id",deviceId()); put("name",InputRules.deviceName(deviceName())); put("local_alarm",true)
        })
        sessionOpen=true
    }
    suspend fun renameDevice(name: String)=syncLock.withLock {
        val value=InputRules.deviceName(name)
        check(sessionOpen) {"账号已退出，请重新登录"}
        val client=api() ?: error("请先登录")
        client.request("/api/mobile/device-name","POST",buildJsonObject {put("id",deviceId());put("name",value)})
    }
    suspend fun endSession(cleanup: suspend ()->Unit)=syncLock.withLock {
        sessionOpen=false
        cleanup()
        store.clear()
        scheduler.cancelAll()
    }
    suspend fun sync(): Boolean = syncLock.withLock {
        val client=api()?.takeIf {sessionOpen} ?: return@withLock false
        registration=client.request("/api/mobile/devices").jsonObject
        val devices=registration.rows("devices")
        if (devices.none { it.text("id") == deviceId() }) throw ApiError(403,"本机设备已移除，请重新登录后连接")
        while (true) {
            val operation=store.pending().firstOrNull() ?: break
            try {
                val result=client.request("/api/mobile/reminder-action","POST",buildJsonObject {
                    put("device_id",deviceId()); put("operation_id",operation.operationId)
                    put("id",operation.reminderId); put("revision",operation.revision); put("action",operation.action)
                    put("minutes",operation.minutes); put("occurred_at",operation.occurredAt)
                })
                persistAndScheduleReminders({store.receipt(operation.operationId,deskJson.decodeFromJsonElement<Reminder>(result))},{scheduler.apply(store.visible())})
            } catch (error: ApiError) {
                if (error.status == 409 || error.status == 400) {
                    persistAndScheduleReminders({store.conflict(operation.reminderId)},{scheduler.apply(store.visible())})
                    conflictMessage="提醒已在其他端更新，未应用旧操作。请检查当前提醒。"
                } else throw error
            }
        }
        for(operation in store.inboxPending()) {
            client.request("/api/inbox/read","POST",deskJson.parseToJsonElement(operation.json))
            store.inboxReceipt(operation.operationId)
        }
        val snapshot=client.request("/api/command","POST",buildJsonObject { put("action","snapshot") })
        persistAndScheduleReminders({store.accept(snapshot.toString())},{scheduler.apply(store.visible())})
        true
    }
    suspend fun act(id: String,revision: String,action: String,minutes: Int=10)=syncLock.withLock {
        check(sessionOpen) {"账号已退出，请重新登录"}
        persistAndScheduleReminders({store.enqueue(id,revision,action,minutes)},{scheduler.apply(store.visible())})
    }
    suspend fun readInbox(payload: JsonObject)=syncLock.withLock {
        check(sessionOpen && api()!=null) {"账号已退出，请重新登录"}
        store.enqueueInboxRead(payload)
    }
    suspend fun cacheInbox(rows: List<JsonObject>)=syncLock.withLock {if(sessionOpen)store.cacheInbox(rows)}
    suspend fun snapshot(value: String)=syncLock.withLock {if(sessionOpen) persistAndScheduleReminders({store.accept(value)},{scheduler.apply(store.visible())})}
    suspend fun command(action: String,payload: JsonObject=buildJsonObject { }): JsonElement {
        val client=api() ?: error("请先登录")
        val result=client.request("/api/command","POST",buildJsonObject { put("action",action); put("payload",payload) })
        sync()
        return result
    }
}
