package app.mydesk.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class SettingsState(val config: JsonObject?=null,val busy: Boolean=false,val message: String="",val failed: Boolean=false)
class SettingsViewModel(private val graph: AppGraph): ViewModel() {
    private val mutable=MutableStateFlow(SettingsState())
    val state=mutable.asStateFlow()
    fun reload()=perform {val config=withContext(Dispatchers.IO) {graph.settings()};mutable.update {it.copy(config=config)}}
    fun save(value: JsonObject,onSaved: ()->Unit)=perform {
        withContext(Dispatchers.IO) {graph.settings(value)}
        val current=withContext(Dispatchers.IO) {graph.settings()}
        mutable.update {it.copy(config=current,message="设置已保存")}
        onSaved()
    }
    fun checkGitHubTask(id: String)=perform {
        val result=withContext(Dispatchers.IO) {graph.checkGitHubTask(buildJsonObject {put("task_id",id)})}
        mutable.update {it.copy(message=result.text("message","连接检查完成"))}
    }
    fun checkService(kind: String,id: String)=perform {
        val result=withContext(Dispatchers.IO) {graph.checkService(buildJsonObject {put("kind",kind);put("id",id)})}
        mutable.update {it.copy(message=result.text("message","连接检查完成"))}
    }
    fun renameDevice(name: String,onSaved: ()->Unit)=perform {
        withContext(Dispatchers.IO) {graph.renameDevice(name)}
        mutable.update {it.copy(message="设备名称已保存")}
        onSaved()
    }
    private fun perform(operation: suspend ()->Unit)=viewModelScope.launch {
        if(state.value.busy) return@launch
        mutable.update {it.copy(busy=true,message="",failed=false)}
        try {operation()}
        catch(cancel: CancellationException) {throw cancel}
        catch(error: Exception) {mutable.update {it.copy(failed=true,message=if(error is ApiError || error is IllegalArgumentException) error.message ?: "设置操作失败" else "连接失败，请检查服务器和网络")}}
        finally {mutable.update {it.copy(busy=false)}}
    }
}
