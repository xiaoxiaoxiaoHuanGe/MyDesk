package app.mydesk.android

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class NativePushTest {
    private val device="a".repeat(32)
    private val registration=buildJsonObject {
        put("server_id","b".repeat(32))
        put("devices",buildJsonArray {add(buildJsonObject {put("id",device); put("registration_id","c".repeat(32))})})
    }
    @Test fun tokenRefreshUpdatesOnlyTheExistingRegistrationAndCannotRecreateRemovedDevice()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val server=MockWebServer(); server.start()
        val vault=object: SessionVault {override var cookie="mydesk_session=test";override var csrf="test"}
        val push=NativePush(context,{MyDeskApi(server.url("/").toString().trimEnd('/'),vault)},{device})
        try {
            push.clear(); push.bind(registration)
            assertFalse(push.configured)
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"设备已移除"}"""))
            val error=runCatching {push.refresh(registration)}.exceptionOrNull()
            assertTrue(error is ApiError && error.status == 403)
            val request=server.takeRequest()
            assertEquals("/api/mobile/push",request.path)
            assertEquals(device,deskJson.parseToJsonElement(request.body.readUtf8()).jsonObject.text("id"))
            assertEquals(1,server.requestCount)
            push.clear()
            assertNull(push.prefs.binding())
            assertFalse(push.receive(emptyMap()))
        } finally {push.inbox.close();server.shutdown()}
    }
    @Test fun changedRegistrationRequiresNewLoginBeforeAnyNetworkUpdate()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val push=NativePush(context,{null},{device})
        try {
            push.clear(); push.bind(registration)
            val changed=buildJsonObject { registration.forEach {(k,v)->put(k,v)};put("server_id","d".repeat(32)) }
            val error=runCatching {push.refresh(changed)}.exceptionOrNull()
            assertTrue(error is ApiError && error.status == 403)
        } finally {push.clear();push.inbox.close()}
    }
}
