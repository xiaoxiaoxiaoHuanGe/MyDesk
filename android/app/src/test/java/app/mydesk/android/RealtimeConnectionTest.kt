package app.mydesk.android

import android.app.Application
import android.app.NotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class RealtimeConnectionTest {
    private fun snapshot(seq: Int)=buildJsonObject {
        put("reminders",buildJsonArray {})
        put("inbox",buildJsonObject {
            put("latest_seq",seq)
            put("recent",buildJsonArray {add(buildJsonObject {
                put("seq",seq);put("id",seq.toString().padStart(32,'0'))
                put("source_name","Codex 重置");put("title","后台消息 $seq");put("body","测试");put("read_at",JsonNull)
            })})
        })
    }
    private suspend fun eventually(predicate: ()->Boolean) = withTimeout(5000) { while(!predicate()) delay(10) }

    @Test fun backgroundOwnerKeepsTheRealSocketAndPostsANotificationAfterLeavingTheActivity()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val store=NativeStore(context,memory=true)
        val repo=DeskRepository(context,store,{null},{"a".repeat(32)},ReminderScheduler(context,store))
        val server=MockWebServer();val sockets=Channel<WebSocket>(Channel.UNLIMITED)
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest)=MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
                override fun onOpen(socket: WebSocket,response: Response) {sockets.trySend(socket)}
            })
        }
        server.start()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val http=OkHttpClient()
        val api=MyDeskApi(server.url("/").toString().trimEnd('/'),object: SessionVault {override var cookie="mydesk_session=test";override var csrf="test"},http)
        var syncs=0
        repo.snapshot(snapshot(1).toString())
        val connection=RealtimeConnection(scope,http,{api},synchronize={syncs++;true},accept={repo.snapshot(it)},connected={})
        try {
            connection.foreground(true)
            val socket=withTimeout(5000) {sockets.receive()}
            connection.background(true)
            connection.foreground(false)
            assertTrue("后台接收开启后，返回桌面必须保留连接",connection.running)
            assertTrue(socket.send(buildJsonObject {put("type","snapshot");put("state",snapshot(2))}.toString()))
            eventually {shadowOf(manager).allNotifications.isNotEmpty()}
            assertEquals("Codex 重置 · 后台消息 2",shadowOf(manager).allNotifications.single().extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            assertEquals("前后台共用同一连接，不能额外同步或创建第二条连接",1,syncs)
            connection.background(false)
            eventually {!connection.running}
        } finally {connection.clear();scope.coroutineContext[Job]!!.cancelAndJoin();http.dispatcher.executorService.shutdown();http.connectionPool.evictAll();store.close();server.shutdown()}
    }

    @Test fun leavingWithoutBackgroundReceptionClosesTheSocketAndClearingSessionOverridesBothOwners()=runBlocking {
        val server=MockWebServer();val sockets=Channel<WebSocket>(Channel.UNLIMITED)
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest)=MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
                override fun onOpen(socket: WebSocket,response: Response) {sockets.trySend(socket)}
            })
        }
        server.start()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val http=OkHttpClient()
        val api=MyDeskApi(server.url("/").toString().trimEnd('/'),object: SessionVault {override var cookie="test";override var csrf="test"},http)
        val connection=RealtimeConnection(scope,http,{api},synchronize={true},accept={},connected={})
        try {
            connection.foreground(true);withTimeout(5000) {sockets.receive()}
            connection.foreground(false)
            eventually {!connection.running}
            connection.background(true)
            assertTrue("后台服务应能独立建立连接",connection.running)
            withTimeout(5000) {sockets.receive()}
            connection.foreground(true)
            connection.clear()
            assertFalse("退出登录必须关闭连接，即使界面和后台服务都曾持有连接",connection.running)
        } finally {connection.clear();scope.coroutineContext[Job]!!.cancelAndJoin();http.dispatcher.executorService.shutdown();http.connectionPool.evictAll();server.shutdown()}
    }

    @Test fun backgroundConnectionReconnectsAfterTheServerClosesTheSocket()=runBlocking {
        val server=MockWebServer();val sockets=Channel<WebSocket>(Channel.UNLIMITED)
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest)=MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
                override fun onOpen(socket: WebSocket,response: Response) {sockets.trySend(socket)}
            })
        }
        server.start()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val http=OkHttpClient()
        val api=MyDeskApi(server.url("/").toString().trimEnd('/'),object: SessionVault {override var cookie="test";override var csrf="test"},http)
        val syncs=java.util.concurrent.atomic.AtomicInteger()
        val connection=RealtimeConnection(scope,http,{api},synchronize={syncs.incrementAndGet();true},accept={},connected={})
        try {
            connection.background(true)
            assertTrue("后台服务应能独立建立连接",connection.running)
            val first=withTimeout(5000) {sockets.receive()}
            first.close(1001,"reconnect test")
            val next=withTimeout(8000) {sockets.receive()}
            assertNotSame(first,next)
            assertEquals(2,syncs.get())
            assertTrue(connection.running)
        } finally {connection.clear();scope.coroutineContext[Job]!!.cancelAndJoin();http.dispatcher.executorService.shutdown();http.connectionPool.evictAll();server.shutdown()}
    }
}
