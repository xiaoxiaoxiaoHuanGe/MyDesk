package app.mydesk.android

import android.content.Context
import android.util.AtomicFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class DigestFilter(val category: String="", val query: String="", val date: String="", val saved: Boolean=false) {
    fun path(before: String?=null): String {
        val url="https://localhost/api/x-digest/feed".toHttpUrl().newBuilder()
        if(category.isNotEmpty()) url.addQueryParameter("category",category)
        if(query.isNotBlank()) url.addQueryParameter("q",query.trim())
        if(date.isNotEmpty()) url.addQueryParameter("date",date)
        if(saved) url.addQueryParameter("bookmarks","1")
        if(before!=null) url.addQueryParameter("before",before)
        return url.build().let { it.encodedPath+it.encodedQuery?.let { q->"?"+q }.orEmpty() }
    }
}

internal class DigestCache(context: Context, sessionKey: String) {
    private val file=AtomicFile(File(context.cacheDir,"x-digest-"+sessionKey))
    suspend fun read(): JsonObject? = withContext(Dispatchers.IO) {
        runCatching { deskJson.parseToJsonElement(file.readFully().decodeToString()).jsonObject }.getOrNull()
    }
    suspend fun write(page: JsonObject) = withContext(Dispatchers.IO) {
        runCatching {
            val stream=file.startWrite()
            try {stream.write(page.toString().encodeToByteArray());file.finishWrite(stream)}
            catch (error: Exception) {file.failWrite(stream);throw error}
        }
        Unit
    }
}

internal fun digestSessionKey(server: String, cookie: String): String =
    MessageDigest.getInstance("SHA-256").digest((server+"\n"+cookie).encodeToByteArray()).joinToString("") {"%02x".format(it)}

internal data class DigestState(
    val filter: DigestFilter=DigestFilter(), val items: List<JsonObject> = emptyList(),
    val categories: List<JsonObject> = emptyList(), val before: String?=null, val unread: Int=0,
    val updated: String="", val working: Boolean=false, val offline: Boolean=false, val error: String="",
    val detail: JsonObject?=null, val detailBusy: Boolean=false, val detailError: String=""
)

internal class XDigestModel(
    private val request: suspend (String,String,JsonObject?)->JsonObject,
    private val cache: DigestCache
): ViewModel() {
    private val mutable=MutableStateFlow(DigestState())
    val state=mutable.asStateFlow()
    private var feedJob: Job?=null
    private var detailJob: Job?=null
    private var lastPage: JsonObject?=null
    private var started=false

    fun start() {
        if(started) return
        started=true
        load(DigestFilter(),initial=true)
    }

    fun load(filter: DigestFilter=state.value.filter, append: Boolean=false, initial: Boolean=false) {
        val previous=state.value
        if(previous.working && filter==previous.filter) return
        val cursor=if(append) previous.before ?: return else null
        feedJob?.cancel()
        mutable.update {it.copy(filter=filter,working=true,error="",items=if(filter==it.filter) it.items else emptyList(),
            before=if(filter==it.filter) it.before else null)}
        feedJob=viewModelScope.launch {
            if(initial) {
                lastPage=cache.read()
                lastPage?.let { page->
                    mutable.update { it.copy(items=page.rows("items"),categories=page.rows("categories"),
                        updated=page.text("updated_at"),unread=page.text("unread").toIntOrNull() ?: 0,offline=true) }
                }
            }
            try {
                val page=request(filter.path(cursor),"GET",null)
                mutable.update {
                    it.copy(items=if(append) (it.items+page.rows("items")).distinctBy {row->row.text("id")} else page.rows("items"),
                        categories=page.rows("categories"),before=(page["next"] as? JsonPrimitive)?.contentOrNull,
                        unread=page.text("unread").toIntOrNull() ?: 0,updated=page.text("updated_at"),working=false,offline=false)
                }
                if(filter==DigestFilter() && !append) {
                    val cachedById=lastPage?.rows("items").orEmpty().associateBy {it.text("id")}
                    val rows=page.rows("items").map { row ->
                        buildJsonObject { cachedById[row.text("id")]?.forEach { (k,v)->put(k,v) }; row.forEach { (k,v)->put(k,v) } }
                    }
                    lastPage=JsonObject(page+("items" to JsonArray(rows)))
                    cache.write(lastPage!!)
                }
            } catch (error: Exception) {
                if(error is CancellationException) throw error
                mutable.update {it.copy(working=false,offline=true,error=if(error is ApiError && error.status==404) "服务器尚未启用 X·速览" else "无法刷新，请重试")}
            }
        }
    }

    fun open(row: JsonObject) {
        detailJob?.cancel()
        val id=row.text("id")
        if(!id.matches(Regex("[0-9]{1,24}"))) return
        val cached=lastPage?.rows("items")?.firstOrNull {it.text("id")==id && "text" in it}
        mutable.update {it.copy(detail=cached ?: row,detailBusy=true,detailError="")}
        detailJob=viewModelScope.launch {
            try {
                val detail=request("/api/x-digest/posts/$id/read","POST",null)
                applyDetail(detail)
            } catch (error: Exception) {
                if(error is CancellationException) throw error
                mutable.update {it.copy(detailBusy=false,detailError=if(cached==null) "无法载入完整原文" else "离线浏览 · 已读状态尚未同步")}
            }
        }
    }

    fun bookmark() {
        val row=state.value.detail ?: return
        if(state.value.detailBusy) return
        val id=row.text("id")
        val saved=row.text("saved")!="true"
        mutable.update {it.copy(detailBusy=true,detailError="")}
        detailJob=viewModelScope.launch {
            try {
                applyDetail(request("/api/x-digest/posts/$id/bookmark","POST",buildJsonObject {put("saved",saved)}))
            } catch (error: Exception) {
                if(error is CancellationException) throw error
                mutable.update {it.copy(detailBusy=false,detailError="收藏未同步，请重试")}
            }
        }
    }

    fun close() {
        detailJob?.cancel()
        mutable.update {it.copy(detail=null,detailBusy=false,detailError="")}
    }

    private suspend fun applyDetail(row: JsonObject) {
        val id=row.text("id")
        mutable.update { s->
            val markRead=s.items.any {it.text("id")==id && it.text("read")!="true"} && row.text("read")=="true"
            s.copy(detail=row,detailBusy=false,unread=(s.unread-(if(markRead) 1 else 0)).coerceAtLeast(0),
                items=s.items.map { if(it.text("id")==id) JsonObject(it+row) else it }.filter { !s.filter.saved || it.text("saved")=="true" })
        }
        lastPage?.let { page->
            val rows=page.rows("items").map { if(it.text("id")==id) JsonObject(it+row) else it }
            lastPage=JsonObject(page+mapOf("items" to JsonArray(rows),"unread" to JsonPrimitive(state.value.unread)))
            cache.write(lastPage!!)
        }
    }
}
