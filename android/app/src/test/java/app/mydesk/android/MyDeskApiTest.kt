package app.mydesk.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MyDeskApiTest {
    @Test fun fullRefreshCanOutliveTheNormalReadTimeout() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            val vault=object: SessionVault { override var cookie="mydesk_session=test"; override var csrf="guard" }
            val client=okhttp3.OkHttpClient.Builder().readTimeout(50,java.util.concurrent.TimeUnit.MILLISECONDS).build()
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault,client)
            server.enqueue(MockResponse().setBody("{\"feeds\":{}}").setBodyDelay(150,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue(api.request("/api/command","POST",buildJsonObject {put("action","sync/all")}).jsonObject.containsKey("feeds"))
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(150,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue(runCatching {api.request("/api/session")}.isFailure)
        } finally {server.shutdown()}
    }
    @Test fun authenticatedRequestsNeverFollowRedirectsToAnotherHost() = runBlocking {
        val server=MockWebServer(); val other=MockWebServer()
        server.start(); other.start()
        try {
            val vault=object: SessionVault { override var cookie="mydesk_session=secret"; override var csrf="guard" }
            other.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/collect")))
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault)
            assertTrue(runCatching {api.request("/api/session")}.isFailure)
            assertEquals(0,other.requestCount)
        } finally { server.shutdown(); other.shutdown() }
    }
    @Test fun loginSavesOpaqueCookieAndMutationsIncludeCsrf() = runBlocking {
        val server=MockWebServer()
        server.start()
        try {
            val vault=object: SessionVault { override var cookie=""; override var csrf="" }
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault)
            server.enqueue(MockResponse().setHeader("Set-Cookie","mydesk_session=opaque; HttpOnly; Path=/; SameSite=Strict").setBody("""{"username":"mydesk","csrf":"guard"}"""))
            api.login("mydesk","password")
            val login=server.takeRequest()
            assertEquals("/api/login",login.path)
            assertEquals("mydesk",deskJson.parseToJsonElement(login.body.readUtf8()).jsonObject.text("username"))
            assertEquals("mydesk_session=opaque",vault.cookie)
            assertEquals("guard",vault.csrf)
            server.enqueue(MockResponse().setBody("{}"))
            api.request("/api/mobile/devices","POST",buildJsonObject { put("name","手机") })
            val action=server.takeRequest()
            assertEquals("guard",action.getHeader("X-MyDesk-CSRF"))
            assertEquals("mydesk_session=opaque",action.getHeader("Cookie"))
        } finally { server.shutdown() }
    }

    @Test fun unauthorizedResponseRemovesPersistedSession() = runBlocking {
        val server=MockWebServer()
        server.start()
        try {
            val vault=object: SessionVault { override var cookie="mydesk_session=expired"; override var csrf="guard" }
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"请重新登录"}"""))
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault)
            assertTrue(runCatching { api.request("/api/session") }.isFailure)
            assertEquals("",vault.cookie)
            assertEquals("",vault.csrf)
        } finally { server.shutdown() }
    }
}
