package app.mydesk.android

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.time.Instant

internal data class BackupState(val stage: String="home",val busy: Boolean=false,
    val message: String="",val failed: Boolean=false,val filename: String="",
    val file: JsonObject?=null,val preview: JsonObject?=null,val pendingSave: Boolean=false,
    val lastExport: String="")

internal class BackupViewModel(private val graph: AppGraph,context: Context): ViewModel() {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("mydesk-backups",Context.MODE_PRIVATE)
    private val exportKey="last-export:"+graph.state.value.server
    private val mutable=MutableStateFlow(BackupState(lastExport=prefs.getString(exportKey,"").orEmpty()))
    val state=mutable.asStateFlow()
    fun open(stage: String) {if(!state.value.busy) mutable.update {BackupState(stage=stage,lastExport=it.lastExport)}}
    fun close()=open("home")
    fun pickerLaunched() {mutable.update {it.copy(pendingSave=false)}}
    fun export(password: String,confirmation: String)=perform {
        BackupInput.password(password,confirmation)
        val result=graph.backup("export",buildJsonObject {put("password",password);put("appearance",graph.state.value.theme)})
        mutable.update {it.copy(file=result.obj("file"),filename=BackupInput.filename(result.text("filename")),pendingSave=true)}
    }
    fun save(uri: Uri?)=perform {
        if(uri==null) {mutable.update {it.copy(file=null,message="已取消保存")};return@perform}
        val bytes=state.value.file?.toString()?.toByteArray(Charsets.UTF_8) ?: error("备份内容已失效")
        withContext(Dispatchers.IO) {app.contentResolver.openOutputStream(uri,"wt")?.use {it.write(bytes)} ?: error("无法写入备份文件")}
        val now=Instant.now().toString()
        prefs.edit().putString(exportKey,now).apply()
        mutable.update {it.copy(stage="home",file=null,lastExport=now,message="加密备份已保存")}
    }
    fun read(uri: Uri?)=perform {
        if(uri==null) return@perform
        val file=withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use(BackupInput::read)
                ?: error("无法读取备份文件")
        }
        mutable.update {it.copy(stage="import",file=file,preview=null,filename=uri.lastPathSegment?.substringAfterLast('/') ?: "MyDesk 备份")}
    }
    fun preview(password: String,replace: Boolean)=perform {
        BackupInput.password(password)
        val file=state.value.file ?: throw IllegalArgumentException("请先选择备份文件")
        val result=graph.backup("preview",buildJsonObject {put("file",file);put("password",password);put("mode",if(replace) "replace" else "merge");put("current_appearance",graph.state.value.theme)})
        mutable.update {it.copy(stage="preview",preview=result,file=null)}
    }
    fun restore(confirmed: Boolean,onRestored: ()->Unit)=perform {
        val preview=state.value.preview ?: throw IllegalArgumentException("请重新预览备份")
        val result=graph.backup("apply",buildJsonObject {put("preview_id",preview.text("preview_id"));put("confirmed",confirmed)})
        graph.setTheme(result.text("appearance","system"))
        mutable.update {it.copy(stage="home",preview=null,file=null,message="配置已恢复，原配置已在后端加密保留")}
        // Restoration is already committed; a transient refresh failure must not report it as failed.
        onRestored()
        try {graph.sync()} catch(cancel: CancellationException) {throw cancel} catch(_: Exception) {}
    }
    private fun perform(operation: suspend ()->Unit)=viewModelScope.launch {
        if(state.value.busy) return@launch
        mutable.update {it.copy(busy=true,message="",failed=false)}
        try {operation()}
        catch(cancel: CancellationException) {throw cancel}
        catch(error: Exception) {mutable.update {it.copy(failed=true,message=if(error is ApiError || error is IllegalArgumentException) error.message ?: "备份操作失败" else "操作失败，请检查服务器连接或文件权限")}}
        finally {mutable.update {it.copy(busy=false)}}
    }
}
