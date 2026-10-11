package app.mydesk.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MyDeskApiTest {
    @Test fun scanningAnotherServerNeverSendsOurSession()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val vault=object: SessionVault {override var cookie="mydesk_session=secret";override var csrf="guard"}
            val api=MyDeskApi(server.url("/").toString().trimEnd('/'),vault)
            val raw="mydesk://login?server=https%3A%2F%2Fevil.example&id=${"a".repeat(32)}&token=${"b".repeat(43)}"
            assertTrue(runCatching {api.previewQrLogin(raw)}.isFailure)
            assertEquals(0,server.requestCount);assertEquals("mydesk_session=secret",vault.cookie)
        } finally {server.shutdown()}
    }
    @Test fun qrConfirmationUsesAuthenticatedPostAndExplicitDecision()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val base=server.url("/").toString().trimEnd('/')
            val vault=object: SessionVault {override var cookie="mydesk_session=secret";override var csrf="guard"}
            val api=MyDeskApi(base,vault)
            server.enqueue(MockResponse().setBody("{\"confirmed\":true}"))
            api.confirmQrLogin(QrLoginCode(base,"a".repeat(32),"b".repeat(43)),false)
            val request=server.takeRequest();val body=deskJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("/api/qr-login/confirm",request.path);assertEquals("POST",request.method)
            assertEquals("guard",request.getHeader("X-MyDesk-CSRF"));assertFalse(body["approve"]!!.jsonPrimitive.boolean)
        } finally {server.shutdown()}
    }
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
            val loginBody=deskJson.parseToJsonElement(login.body.readUtf8()).jsonObject
            assertEquals("mydesk",loginBody.text("username"))
            assertEquals("android",loginBody.text("client"))
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
