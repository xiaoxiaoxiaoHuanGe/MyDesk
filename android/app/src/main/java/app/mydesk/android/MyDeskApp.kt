package app.mydesk.android

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class MyDeskApp: Application() { val graph by lazy { AppGraph(this) } }
val Context.graph: AppGraph get()=(applicationContext as MyDeskApp).graph

data class HomeState(val snapshot: JsonObject?=null,val reminders: List<Reminder> = emptyList(),val queued: Int=0,
    val server: String="",val theme: String="system",val authenticated: Boolean=false,val connected: Boolean=false,
    val ready: Boolean=false,val busy: Boolean=false,val message: String="",val pushStatus: JsonObject?=null,val deviceName: String="",val syncing: Boolean=false)

class AppGraph(val context: Context,val prefs: AppPrefs=AppPrefs(context)) {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val store=NativeStore(context)
    val scheduler=ReminderScheduler(context,store)
    private val mutable=MutableStateFlow(HomeState())
    val state=mutable.asStateFlow()
    private var phone=PhonePrefs()
    private var client: MyDeskApi?=null
    private val http=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).pingInterval(20,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    val repo=DeskRepository(context,store,{client?.takeIf { it.vault.cookie.isNotEmpty() }},{phone.device},scheduler,{phone.deviceName})
    val push=NativePush(context,{client?.takeIf { it.vault.cookie.isNotEmpty() }},{phone.device},store,scheduler)
    internal val updates=AppUpdateController(context,scope,{client?.takeIf {state.value.authenticated}},{invalidateSession(it)})
    val ready=CompletableDeferred<Unit>()
    private var foreground: Job?=null
    private var wantsForeground=false
    private val manualSyncLock=kotlinx.coroutines.sync.Mutex()
    init {
        scope.launch {
            prefs.initialize()
            prefs.flow.collect { value ->
                val changed=value.server != phone.server
                phone=value
                if(changed) updates.reset()
                if (changed || client == null) client=value.server.takeIf { it.isNotEmpty() }?.let { MyDeskApi(it,SecureVault(context,it),http) }
                mutable.update { it.copy(server=value.server,theme=value.theme,deviceName=value.deviceName,ready=true,authenticated=client?.vault?.cookie?.isNotEmpty() == true) }
                if (!ready.isCompleted) ready.complete(Unit)
                if (wantsForeground && foreground == null) startForeground()
            }
        }
        scope.launch { store.state.collect { cache -> mutable.update { it.copy(snapshot=cache.snapshot,reminders=cache.reminders,queued=cache.queued) } } }
    }
    fun perform(operation: suspend ()->Unit) = scope.launch {
        mutable.update { it.copy(busy=true,message="") }
        try { operation() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { mutable.update { it.copy(message=if (error is ApiError || error is IllegalArgumentException || error is IllegalStateException) error.message ?: "操作失败" else "连接失败，请检查服务器地址和网络") } }
        finally { mutable.update { it.copy(busy=false) } }
    }
    // Local appearance saving does not own the global business-operation progress state.
    fun setTheme(value: String)=scope.launch {
        try {prefs.theme(value)}
        catch (cancel: CancellationException) {throw cancel}
        catch (error: Exception) {mutable.update {it.copy(message="外观设置未能保存，请重试")}}
    }
    suspend fun login(server: String,username: String,password: String) {
        ready.await()
        updates.reset()
        val base=InputRules.server(server)
        if (base != phone.server) { stopForeground(); repo.endSession {push.clear()} }
        val api=MyDeskApi(base,SecureVault(context,base),http)
        api.login(username,password)
        phone=phone.copy(server=base); client=api
        prefs.server(base)
        repo.register(); repo.sync()
        push.bind(repo.registration); push.refresh(repo.registration)
        mutable.update { it.copy(server=base,authenticated=true,message="已登录，提醒已同步") }
        schedulePeriodic(); startForeground()
    }
    suspend fun logout() {
        ready.await(); updates.reset(); stopForeground()
        mutable.update {it.copy(authenticated=false,connected=false)}
        WorkManager.getInstance(context).cancelAllWorkByTag("mydesk-sync")
        push.clear()
        repo.endSession {
            runCatching { client?.request("/api/logout","POST") }
            client?.vault?.apply { cookie=""; csrf="" }
        }
        mutable.update { it.copy(authenticated=false,connected=false,snapshot=null,reminders=emptyList(),pushStatus=null,message="已退出并清理本机提醒") }
    }
    suspend fun action(reminder: Reminder,action: String,minutes: Int=10) {
        repo.act(reminder.id,reminder.revision,action,minutes)
        requestSync()
        mutable.update { it.copy(message="操作已保存在本机，联网后同步") }
        runCatching { sync() }
    }
    suspend fun sync(): Boolean {
        ready.await()
        try {
            val result=repo.sync()
            val active=result && state.value.authenticated && client?.vault?.cookie?.isNotEmpty() == true
            if(active) push.refresh(repo.registration)
            mutable.update { it.copy(connected=active,message=repo.conflictMessage ?: it.message) }
            return active
        } catch (error: ApiError) {
            if (error.status in setOf(401,403)) {
                invalidateSession(error)
            }
            throw error
        }
    }
    suspend fun syncAll() {
        if(!manualSyncLock.tryLock()) return
        mutable.update {it.copy(syncing=true)}
        try {
            ready.await()
            check(state.value.authenticated) {"请先登录"}
            val result=authorized {repo.command("sync/all")}.jsonObject
            check(sync()) {"同步未完成，请检查连接"}
            val failed=result.obj("feeds").values.any { feed ->
                val data=(feed as? JsonObject)?.obj("data") ?: return@any false
                data.text("error","").isNotBlank() || (data.rows("items")+data.rows("accounts")).any {it.text("error","").isNotBlank()}
            }
            mutable.update {it.copy(message=repo.conflictMessage ?: if(failed) "同步完成，部分接入失败，请查看工作台" else "所有已启用接入已同步")}
        } finally {
            mutable.update {it.copy(syncing=false)}
            manualSyncLock.unlock()
        }
    }
    private suspend fun invalidateSession(error: ApiError) {
        updates.reset()
        mutable.update {it.copy(authenticated=false,connected=false)}
        withContext(NonCancellable) {
            repo.endSession {push.clear();client?.vault?.apply {cookie="";csrf=""}}
            mutable.update {it.copy(snapshot=null,reminders=emptyList(),pushStatus=null,message=error.message ?: "请重新登录")}
        }
        stopForeground()
    }
    private suspend fun <T> authorized(operation: suspend ()->T): T = try {operation()}
        catch(error: ApiError) {if(error.status in setOf(401,403)) invalidateSession(error);throw error}
    suspend fun command(action: String,payload: JsonObject) {
        authorized {repo.command(action,payload)}
        val feedback=when(action) {
            "attention/acknowledge"->"已确认，记录仍保留"
            "wxstep/plan/save"->"配置已保存"
            "wxstep/plan/start"->"自动任务已开始"
            else->null
        }
        if(feedback!=null) mutable.update {it.copy(message=feedback)}
    }
    suspend fun checkGitHubTask(payload: JsonObject): JsonObject=authorized {repo.command("github_task/check",payload).jsonObject}
    suspend fun checkService(payload: JsonObject): JsonObject=authorized {repo.command("service/check",payload).jsonObject}
    suspend fun inboxRequest(path: String,method: String="GET",body: JsonObject?=null): JsonObject=authorized {
        ready.await();val active=client ?: error("请先登录")
        val cookie=active.vault.cookie
        check(state.value.authenticated&&cookie.isNotBlank()) {"请先登录"}
        val result=active.request(path,method,body).jsonObject
        check(client===active&&state.value.authenticated&&active.vault.cookie==cookie) {"会话已变化，请重试"}
        result
    }
    suspend fun readInbox(payload: JsonObject) {
        repo.readInbox(payload);requestSync();runCatching {sync()}
    }
    suspend fun settings(): JsonObject=authorized {ready.await();client!!.request("/api/settings").jsonObject}
    suspend fun backup(action: String,value: JsonObject): JsonObject=authorized {
        require(action in setOf("export","preview","apply"))
        ready.await()
        client!!.request("/api/backup/$action","POST",value).jsonObject
    }
    suspend fun settings(value: JsonObject) {authorized {client!!.request("/api/settings","PUT",value);sync()}}
    suspend fun renameDevice(name: String) {
        ready.await()
        val value=InputRules.deviceName(name)
        authorized {repo.renameDevice(value)}
        prefs.deviceName(value)
    }
    suspend fun changePassword(current: String,password: String) {
        ready.await()
        (client ?: error("请先登录")).request("/api/password","POST",buildJsonObject {put("current_password",current);put("new_password",password)})
        logout()
        mutable.update {it.copy(message="密码已修改，请使用新密码重新登录")}
    }
    suspend fun notificationStatus() { ready.await(); val result=authorized {push.status()}; mutable.update { it.copy(pushStatus=result) } }
    suspend fun testNotification() {
        sync()
        val result=push.test()
        mutable.update { it.copy(message=result.text("message","原生通知已入队，请检查手机通知中心")) }
        notificationStatus()
    }
    suspend fun loadHistoryPage(taskId: String?,before: Long?,limit: Int): List<JsonObject> =
        authorized {repo.command("history",buildJsonObject {put("limit",limit);before?.let {put("before",it)};taskId?.let {put("task_id",it)}})}.jsonArray.map {it.jsonObject}
    suspend fun loadStepHistory(runId: String,before: Int?): List<JsonObject> =
        authorized {repo.command("wxstep/plan/history",buildJsonObject {put("run_id",runId);put("limit",100);before?.let {put("before",it)}})}.jsonArray.map {it.jsonObject}
    fun startForeground() {
        wantsForeground=true
        if (foreground?.isActive == true || !ready.isCompleted) return
        foreground=scope.launch {
            try {
                var delayMs=2000L
                while (isActive && state.value.authenticated) {
                    try {
                        sync()
                        updates.check()
                        val api=client ?: break
                        val messages=Channel<String>(Channel.CONFLATED)
                        val request=Request.Builder().url(api.base.replaceFirst("https://","wss://")+"/api/ws").header("Cookie",api.vault.cookie).build()
                        val socket=http.newWebSocket(request,object: WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket,response: Response) { mutable.update { it.copy(connected=true) } }
                            override fun onMessage(webSocket: WebSocket,text: String) { messages.trySend(text) }
                            override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { messages.close() }
                            override fun onClosing(webSocket: WebSocket,code: Int,reason: String) { webSocket.close(code,reason); messages.close() }
                            override fun onClosed(webSocket: WebSocket,code: Int,reason: String) { messages.close() }
                        })
                        try {
                            for (text in messages) {
                                val message=deskJson.parseToJsonElement(text).jsonObject
                                if (message.text("type") == "snapshot") repo.snapshot(message.obj("state").toString())
                            }
                        } finally { socket.cancel(); messages.cancel() }
                        delayMs=2000L
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: IOException) { }
                    mutable.update { it.copy(connected=false) }
                    delay(delayMs); delayMs=minOf(delayMs*2,30000L)
                }
            } finally { mutable.update { it.copy(connected=false) } }
        }
    }
    fun stopForeground() { wantsForeground=false; foreground?.cancel(); foreground=null; mutable.update { it.copy(connected=false) } }
    fun requestSync() {
        val work=OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("mydesk-sync").build()
        WorkManager.getInstance(context).enqueueUniqueWork("mydesk-sync-now",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
    }
    private fun schedulePeriodic() {
        val work=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("mydesk-sync").build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("mydesk-sync-periodic",ExistingPeriodicWorkPolicy.KEEP,work)
    }
}

class SyncWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        val graph=applicationContext.graph
        graph.ready.await()
        if (!graph.state.value.authenticated) Result.success()
        else if (graph.sync()) Result.success() else Result.retry()
    } catch (error: ApiError) { if (error.status in setOf(401,403)) Result.failure() else Result.retry() }
      catch (_: IOException) { Result.retry() }
}
