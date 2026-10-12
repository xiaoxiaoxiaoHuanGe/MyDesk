package app.mydesk.android

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class LocalIpClientTest {
    @Test fun domesticQueryUsesACompatibleUserAgentInsteadOfTheRejectedOkHttpDefault() = runBlocking {
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if(request.getHeader("User-Agent")=="Mozilla/5.0")
                    MockResponse().setBody("<div class=\"card-body\"><p>203.0.113.42<br/>中国 深圳</p></div>")
                else MockResponse().setResponseCode(503)
        }
        server.start()
        try {
            val result=LocalIpClient().fetch(LocalIpEndpoint("domestic","国内出口",server.url("/").toString(),domestic=true))
            assertEquals(IpExitStatus.SUCCESS,result.status)
            assertEquals("203.0.113.42",result.ip)
            val request=server.takeRequest()
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
        } finally {server.shutdown()}
    }

    @Test fun embeddedEndpointsUseTheHomepageRefererRequiredByTheService() = runBlocking {
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if(request.getHeader("Referer")=="https://ip111.cn/") MockResponse().setBody("\n<div style=\"text-align:center\">198.51.100.28<br/>中国 香港</div>")
                else MockResponse().setResponseCode(403)
        }
        server.start()
        try {
            val result=LocalIpClient().fetch(LocalIpEndpoint("foreign","国外出口",server.url("/ip.php").toString()))
            assertEquals(IpExitStatus.SUCCESS,result.status)
            assertEquals("198.51.100.28",result.ip)
            assertEquals("中国 香港",result.location)
        } finally {server.shutdown()}
    }

    @Test fun domesticPageUsesOnlyItsFirstResultNotEmbeddedExitsOrScripts() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("""<script>192.0.2.99</script><div class="card-body"><p>203.0.113.42<br/>中国&nbsp;深圳</p><p>这是您访问国内网站所使用的IP</p></div><iframe src="https://other.example/ip.php"></iframe>"""))
            val result=LocalIpClient().fetch(LocalIpEndpoint("domestic","国内出口",server.url("/").toString(),domestic=true))
            assertEquals(IpExitStatus.SUCCESS,result.status)
            assertEquals("203.0.113.42",result.ip)
            assertEquals("中国 深圳",result.location)
            assertNull(server.takeRequest().getHeader("Cookie"))
        } finally {server.shutdown()}
    }

    @Test fun simpleEndpointSupportsIpv6AndNeverTreatsErrorPageAsIp() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val endpoint=LocalIpEndpoint("foreign","国外出口",server.url("/").toString())
            server.enqueue(MockResponse().setBody("<html><body>2001:db8::42<br>日本 东京</body></html>"))
            val result=LocalIpClient().fetch(endpoint)
            assertEquals("2001:db8::42",result.ip)
            assertEquals("日本 东京",result.location)
            server.enqueue(MockResponse().setBody("<html><body>Access denied: request from 203.0.113.1</body></html>"))
            assertEquals(IpExitStatus.FAILED,LocalIpClient().fetch(endpoint).status)
            server.enqueue(MockResponse().setResponseCode(403).setBody("203.0.113.1<br>美国"))
            assertEquals(IpExitStatus.FAILED,LocalIpClient().fetch(endpoint).status)
        } finally {server.shutdown()}
    }

    @Test fun httpTimeoutIsReportedWithoutRetrying() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("203.0.113.42<br>中国 深圳").setBodyDelay(300,TimeUnit.MILLISECONDS))
            val client=OkHttpClient.Builder().callTimeout(80,TimeUnit.MILLISECONDS).build()
            val result=LocalIpClient(client).fetch(LocalIpEndpoint("google","Google 出口",server.url("/").toString()))
            assertEquals(IpExitStatus.TIMEOUT,result.status)
            assertEquals(1,server.requestCount)
        } finally {server.shutdown()}
    }
}
