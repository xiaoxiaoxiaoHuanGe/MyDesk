package app.mydesk.android

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=android.app.Application::class)
class DeviceSettingsTest {
    @Test fun deviceDisplayNamePersistsWithoutChangingIdentityOrServer()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val prefs=AppPrefs(context)
        prefs.initialize();prefs.server("https://mydesk.example");prefs.deviceName("  我的真我手机  ")
        val saved=prefs.flow.first()
        assertEquals("我的真我手机",saved.deviceName)
        val reopened=AppPrefs(context)
        assertEquals(saved,reopened.flow.first())
        for(value in listOf("","   ","x".repeat(81),"手机\n名称")) assertTrue(runCatching {reopened.deviceName(value)}.isFailure)
        assertEquals(saved,reopened.flow.first())
    }
    @Test fun renameUsesAnExistingDeviceEndpointAndNeverRegistersRemovedDevice()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=NativeStore(context,memory=true)
        val server=MockWebServer();server.start()
        try {
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),object: SessionVault {override var cookie="test";override var csrf="csrf"})
            val repo=DeskRepository(context,store,{api},{"a".repeat(32)},ReminderScheduler(context,store))
            server.enqueue(MockResponse().setBody("{}"))
            repo.renameDevice("我的手机")
            val request=server.takeRequest(1,TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("/api/mobile/device-name",request!!.path)
            assertEquals("POST",request.method)
            val body=deskJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("我的手机",body.text("name"));assertEquals("a".repeat(32),body.text("id"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"设备已移除"}"""))
            assertTrue(runCatching {repo.renameDevice("改名")}.exceptionOrNull() is ApiError)
            assertEquals("/api/mobile/device-name",server.takeRequest().path)
            assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS))
        } finally {store.close();server.shutdown()}
    }
}
