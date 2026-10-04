package app.mydesk.android

import android.content.Context
import android.os.Build
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class PushPrefs(context: Context) {
    private val prefs=context.getSharedPreferences("native-push",Context.MODE_PRIVATE)
    var token: String
        get()=prefs.getString("token","") ?: ""
        set(value) { check(prefs.edit().putString("token",value).commit()) }
    fun binding(): PushBinding? {
        val server=prefs.getString("server_id","") ?: ""
        val device=prefs.getString("device_id","") ?: ""
        val registration=prefs.getString("registration_id","") ?: ""
        return if(server.isEmpty() || device.isEmpty() || registration.isEmpty()) null else PushBinding(server,device,registration)
    }
    fun bind(server: String,device: String,registration: String) { check(prefs.edit().putString("server_id",server).putString("device_id",device).putString("registration_id",registration).commit()) }
    fun unbind() { check(prefs.edit().remove("server_id").remove("device_id").remove("registration_id").commit()) }
}

class NativePush(private val context: Context,private val api: ()->MyDeskApi?,private val device: ()->String,private val store: NativeStore?=null,private val scheduler: ReminderScheduler?=null) {
    val prefs=PushPrefs(context)
    val inbox=PushInbox(context)
    private val delivery=PushDelivery(context,inbox)
    private val lock=kotlinx.coroutines.sync.Mutex()
    val configured: Boolean get()=FirebaseApp.getApps(context).isNotEmpty()
    private fun messaging(): FirebaseMessaging?=if(configured) FirebaseMessaging.getInstance() else null
    suspend fun bind(registration: JsonObject) {
        val server=registration.text("server_id")
        val generation=registration.rows("devices").firstOrNull {it.text("id") == device()}?.text("registration_id","") ?: ""
        require(server.matches(Regex("[a-f0-9]{32}"))) { "服务器缺少原生通知标识" }
        require(generation.matches(Regex("[a-f0-9]{32}"))) { "服务器缺少设备登记标识" }
        if(prefs.binding() != PushBinding(server,device(),generation)) inbox.clear()
        prefs.bind(server,device(),generation)
    }
    suspend fun refresh(registration: JsonObject) {
        val current=prefs.binding() ?: return
        val generation=registration.rows("devices").firstOrNull {it.text("id") == device()}?.text("registration_id","") ?: ""
        if(current != PushBinding(registration.text("server_id"),device(),generation)) throw ApiError(403,"通知设备登记已变更，请重新登录")
        val client=api() ?: return
        if(store != null && scheduler != null) delivery.reconcile(current,store,scheduler)
        val firebase=messaging()
        if(firebase != null && PushDelivery.allowed(context)) {
            firebase.isAutoInitEnabled=true
            val token=withTimeoutOrNull(10000) { suspendCancellableCoroutine<String?> { continuation ->
                firebase.token.addOnCompleteListener { task -> if(continuation.isActive) continuation.resume(if(task.isSuccessful) task.result else null) }
            } }
            if(!token.isNullOrBlank()) prefs.token=token
        }
        client.request("/api/mobile/push","POST",buildJsonObject {
            put("id",device()); put("notifications_enabled",PushDelivery.allowed(context))
            if(configured && prefs.token.isNotEmpty()) put("push_token",prefs.token) else put("push_token",JsonNull)
        })
        receipts(client,current)
    }
    private suspend fun receipts(client: MyDeskApi,binding: PushBinding) {
        for(receipt in inbox.pending()) {
            if(receipt.serverId != binding.serverId || receipt.deviceId != binding.deviceId || receipt.registrationId != binding.registrationId) continue
            client.request("/api/mobile/notification-receipt","POST",buildJsonObject {
                put("device_id",receipt.deviceId); put("event_id",receipt.eventId); put("phase",receipt.phase)
            })
            inbox.acknowledge(receipt.eventId)
        }
    }
    suspend fun receive(data: Map<String,String>): Boolean {
        lock.lock()
        try {
            val message=PushMessage.parse(data,prefs.binding(),Instant.now()) ?: return false
            val accepted=delivery.receive(message)
            requestWork(context,urgent=message.kind=="reminder")
            return accepted
        } finally { lock.unlock() }
    }
    suspend fun clear() {
        lock.lock()
        try { prefs.unbind(); inbox.clear(); messaging()?.isAutoInitEnabled=false }
        finally { lock.unlock() }
    }
    suspend fun status(): JsonObject {
        val client=api() ?: error("请先登录")
        val result=client.request("/api/mobile/notifications?device_id=${device()}").jsonObject
        return buildJsonObject { result.forEach { (key,value)->put(key,value) }; put("client_configured",configured); put("token_registered_locally",configured && prefs.token.isNotEmpty()) }
    }
    suspend fun test(): JsonObject=(api() ?: error("请先登录")).request("/api/mobile/notifications/test","POST",buildJsonObject {put("device_id",device())}).jsonObject
    companion object {
        fun requestWork(context: Context,urgent: Boolean=false) {
            val builder=OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("mydesk-sync")
            if(urgent && Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(context).enqueueUniqueWork(if(urgent) "mydesk-native-receive" else "mydesk-sync-now",ExistingWorkPolicy.APPEND_OR_REPLACE,builder.build())
        }
    }
}

class NativeMessagingService: FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushPrefs(this).token=token
        NativePush.requestWork(this)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        // Persist and ask NotificationManager locally. Network work is delegated to WorkManager.
        runBlocking(Dispatchers.IO) { applicationContext.graph.push.receive(message.data) }
    }
}
