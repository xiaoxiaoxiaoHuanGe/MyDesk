package app.mydesk.android

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.serialization.json.*
import kotlinx.coroutines.withTimeoutOrNull

data class LocalIpEndpoint(val id: String,val label: String,val url: String,val domestic: Boolean=false)
enum class IpExitStatus { LOADING, SUCCESS, TIMEOUT, FAILED }
data class IpExitResult(val endpoint: LocalIpEndpoint,val status: IpExitStatus=IpExitStatus.LOADING,val ip: String="",val location: String="",val diagnostic: String="")

internal val localIpEndpoints=listOf(
    LocalIpEndpoint("domestic","国内出口","https://ip111.cn/",domestic=true),
    LocalIpEndpoint("foreign","国外出口","https://us.ip111.cn/ip.php"),
    LocalIpEndpoint("google","Google 出口","https://sspanel.net/ip.php")
)

// Dedicated public client: no MyDesk session, cookies, backend proxy or retry loop.
class LocalIpClient(client: OkHttpClient=OkHttpClient.Builder()
    .connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build()) {
    private val http=client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    suspend fun fetch(endpoint: LocalIpEndpoint): IpExitResult {
        val result=fetchPage(endpoint)
        if(result.status!=IpExitStatus.SUCCESS || result.location.trim() !in setOf("美国","United States","US")) return result
        return withTimeoutOrNull(1500) {enrich(result)} ?: result
    }

    private suspend fun enrich(result: IpExitResult): IpExitResult=suspendCancellableCoroutine {continuation->
        val url="https://ipwho.is/".toHttpUrl().newBuilder().addPathSegment(result.ip)
            .addQueryParameter("lang","zh-CN").addQueryParameter("fields","success,ip,country,region,city").build()
        val call=http.newCall(Request.Builder().url(url).header("Accept","application/json").build())
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) {if(continuation.isActive) continuation.resume(result)}
            override fun onResponse(call: Call,response: Response) {
                val enhanced=runCatching {response.use {
                    require(it.isSuccessful)
                    val body=it.body ?: error("No geolocation")
                    val raw=body.source().apply {request(32_769)}.buffer.clone().readByteArray()
                    require(raw.size<=32_768)
                    val data=deskJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
                    require(data["success"]?.jsonPrimitive?.booleanOrNull==true && data.text("ip","")==result.ip)
                    val location=listOf("country","region","city").map {key->data.text(key,"").trim().take(100)}.filter {v->v.isNotBlank()}.distinct().joinToString(" · ")
                    if(location.isBlank()) result else result.copy(location=location)
                }}.getOrDefault(result)
                if(continuation.isActive) continuation.resume(enhanced)
            }
        })
    }

    private suspend fun fetchPage(endpoint: LocalIpEndpoint): IpExitResult=suspendCancellableCoroutine { continuation ->
        val url=endpoint.url.toHttpUrl().newBuilder().addQueryParameter("_mydesk",UUID.randomUUID().toString()).build()
        val request=Request.Builder().url(url).header("Accept","text/html,text/plain")
            .header("Cache-Control","no-cache, no-store")
            // The domestic service returns 503 for OkHttp's default User-Agent.
            .apply {if(endpoint.domestic) header("User-Agent","Mozilla/5.0")}
            // These two services reject direct requests without their embedding page's Referer.
            .apply {if(!endpoint.domestic) header("Referer","https://ip111.cn/")}.build()
        val call=http.newCall(request)
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) {
                if(continuation.isActive) continuation.resume(failure(endpoint,e))
            }
            override fun onResponse(call: Call,response: Response) {
                val result=try {
                    response.use {
                        if(!it.isSuccessful) IpExitResult(endpoint,IpExitStatus.FAILED,diagnostic="HTTP ${it.code}")
                        else {
                            val body=it.body ?: throw IOException("Missing IP response")
                            val source=body.source();source.request(256_001)
                            if(source.buffer.size>256_000) throw IOException("IP response too large")
                            val bytes=source.readByteArray()
                            parse(endpoint,bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
                        }
                    }
                } catch(error: IOException) {failure(endpoint,error)}
                  catch(_: IllegalArgumentException) {IpExitResult(endpoint,IpExitStatus.FAILED,diagnostic="Invalid IP response")}
                if(continuation.isActive) continuation.resume(result)
            }
        })
    }

    private fun failure(endpoint: LocalIpEndpoint,error: IOException)=
        IpExitResult(endpoint,if(error is InterruptedIOException) IpExitStatus.TIMEOUT else IpExitStatus.FAILED,diagnostic=error.javaClass.simpleName)

    private fun parse(endpoint: LocalIpEndpoint,html: String): IpExitResult {
        require(html.length<=256_000) {"IP response too large"}
        val options=setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)
        val cleaned=html.replace(Regex("<!--.*?-->|<(script|style|iframe)\\b[^>]*>.*?</\\1\\s*>",options),"")
        // The domestic page contains two iframe results: only its first card's paragraph is ours.
        val content=if(endpoint.domestic) Regex("""<div\b[^>]*class\s*=\s*["'][^"']*\bcard-body\b[^"']*["'][^>]*>\s*<p\b[^>]*>(.*?)</p>""",options)
            .find(cleaned)?.groupValues?.get(1) ?: throw IOException("Missing domestic IP result")
        else Regex("<body\\b[^>]*>(.*?)</body>",options).find(cleaned)?.groupValues?.get(1) ?: cleaned
        val lines=content.replace(Regex("<br\\s*/?>|</(?:p|div|tr|li)\\s*>",options),"\n")
            .replace(Regex("<[^>]+>"),"").replace("&nbsp;"," ").replace("&#160;"," ")
            .replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
            .lineSequence().map {it.trim().replace(Regex("\\s+")," ")}.filter {it.isNotEmpty()}.toList()
        val index=lines.indexOfFirst(::isIpLiteral)
        if(index<0) return IpExitResult(endpoint,IpExitStatus.FAILED,diagnostic="IP result missing")
        val location=lines.drop(index+1).takeWhile {line->!line.contains("这是") && !line.contains("访问") && !line.contains("IP地址")}
            .take(4).distinct().joinToString(" · ").take(200)
        return IpExitResult(endpoint,IpExitStatus.SUCCESS,lines[index],location)
    }

    private fun isIpLiteral(value: String): Boolean {
        if(value.length>45) return false
        if(':' in value) return value.matches(Regex("[0-9a-fA-F:.]+")) &&
            runCatching {InetAddress.getByName(value) is Inet6Address}.getOrDefault(false)
        val parts=value.split('.')
        return parts.size==4 && parts.all {it.isNotEmpty() && it.length<=3 && it.all {c->c in '0'..'9'} && it.toInt() in 0..255}
    }
}
