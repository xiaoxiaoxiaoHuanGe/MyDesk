package app.mydesk.android

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Cookie
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

interface SessionVault { var cookie: String; var csrf: String }
class MyDeskApi(val base: String, val vault: SessionVault, client: OkHttpClient = OkHttpClient()) {
    val client=client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun login(username: String, password: String): JsonObject {
        val result=request("/api/login","POST",buildJsonObject { put("username",username); put("password",password) }).jsonObject
        vault.csrf=result.text("csrf", "")
        return result
    }
    suspend fun request(path: String, method: String="GET", body: JsonElement?=null): JsonElement = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/") && !path.contains(".."))
        val builder=Request.Builder().url(base+path).header("Accept","application/json")
        if (vault.cookie.isNotEmpty()) builder.header("Cookie",vault.cookie)
        if (method != "GET" && vault.csrf.isNotEmpty()) builder.header("X-MyDesk-CSRF",vault.csrf)
        builder.method(method,if (method in setOf("GET","HEAD")) null else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        val fullRefresh=path == "/api/command" && method == "POST" && (body as? JsonObject)?.text("action") == "sync/all"
        val transport=if(fullRefresh) client.newBuilder().readTimeout(180,java.util.concurrent.TimeUnit.SECONDS).build() else client
        transport.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) { vault.cookie=""; vault.csrf="" }
            val result=runCatching { deskJson.parseToJsonElement(response.body?.string() ?: "{}") }.getOrElse { throw IOException("服务器响应格式无效") }
            if (!response.isSuccessful) throw ApiError(response.code,(result as? JsonObject)?.text("error","操作失败") ?: "操作失败")
            response.headers.values("Set-Cookie").forEach { raw ->
                val cookie=Cookie.parse(response.request.url,raw)
                if (cookie?.name == "mydesk_session") vault.cookie="${cookie.name}=${cookie.value}"
            }
            result
        }
    }
}

class ApiError(val status: Int, message: String): IOException(message)
