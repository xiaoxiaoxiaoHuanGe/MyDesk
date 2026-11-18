package app.mydesk.android

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*

enum class RealtimeStatus(val label: String) {
    IDLE("实时连接已暂停"), CONNECTING("正在连接"), CONNECTED("实时连接正常"), RETRYING("连接中断，正在重试")
}

/** One socket shared by the visible Activity and the user-enabled receiving service. */
internal class RealtimeConnection(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val api: ()->MyDeskApi?,
    private val synchronize: suspend ()->Boolean,
    private val accept: suspend (String)->Unit,
    private val connected: (Boolean)->Unit,
    private val checkUpdates: suspend ()->Unit={},
    private val connectionState: (RealtimeStatus)->Unit={}
) {
    private var visible=false
    private var receiving=false
    private var job: Job?=null
    private var generation=0L
    val running: Boolean @Synchronized get()=job?.isActive==true
    @Synchronized fun foreground(value: Boolean) {visible=value;refresh()}
    @Synchronized fun background(value: Boolean) {receiving=value;refresh()}
    @Synchronized fun clear() {visible=false;receiving=false;disconnect()}
    @Synchronized fun refresh() {
        if(!visible && !receiving) {disconnect();return}
        if(job?.isActive==true || api()==null) return
        val current=++generation
        connectionState(RealtimeStatus.CONNECTING)
        job=scope.launch {listen(current)}
    }
    @Synchronized private fun disconnect() {
        generation++
        job?.cancel();job=null
        connected(false)
        connectionState(RealtimeStatus.IDLE)
    }
    @Synchronized private fun status(current: Long,value: Boolean) {if(generation==current) {
        connected(value)
        connectionState(if(value) RealtimeStatus.CONNECTED else RealtimeStatus.RETRYING)
    }}
    @Synchronized private fun isVisible()=visible
    private suspend fun listen(current: Long) {
        try {
            var delayMs=2000L
            while(currentCoroutineContext().isActive && api()!=null) {
                try {
                    if(!synchronize()) break
                    if(isVisible()) checkUpdates()
                    val client=api() ?: break
                    val messages=Channel<String>(Channel.CONFLATED)
                    val url=client.base.replaceFirst("https://","wss://").replaceFirst("http://","ws://")+"/api/ws"
                    val request=Request.Builder().url(url).header("Cookie",client.vault.cookie).build()
                    val socket=http.newWebSocket(request,object: WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket,response: Response) {status(current,true)}
                        override fun onMessage(webSocket: WebSocket,text: String) {messages.trySend(text)}
                        override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) {messages.close()}
                        override fun onClosing(webSocket: WebSocket,code: Int,reason: String) {webSocket.close(code,reason);messages.close()}
                        override fun onClosed(webSocket: WebSocket,code: Int,reason: String) {messages.close()}
                    })
                    try {
                        for(text in messages) {
                            val message=deskJson.parseToJsonElement(text).jsonObject
                            if(message.text("type")=="snapshot" && api()===client) accept(message.obj("state").toString())
                        }
                    } finally {socket.cancel();messages.cancel()}
                    delayMs=2000L
                } catch(cancel: CancellationException) {throw cancel}
                  catch(error: Exception) {
                      ReminderDiagnostics.event("realtime_retry","error_type=${error.javaClass.simpleName}")
                  }
                status(current,false)
                delay(delayMs);delayMs=minOf(delayMs*2,30000L)
            }
        } finally {status(current,false)}
    }
}
