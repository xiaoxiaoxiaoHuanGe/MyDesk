package app.mydesk.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

data class TaskHistoryState(val taskId: String?=null,val rows: List<JsonObject> = emptyList(),val busy: Boolean=false,val error: String="",val hasMore: Boolean=false)
class TaskHistoryViewModel(private val loader: suspend (String?,Long?,Int)->List<JsonObject>,private val pageSize: Int=100): ViewModel() {
    private val mutable=MutableStateFlow(TaskHistoryState())
    val state=mutable.asStateFlow()
    private var generation=0L
    private var loading: Job?=null
    init {require(pageSize in 1..200)}
    fun open(taskId: String?) {
        loading?.cancel()
        val current=++generation
        if(taskId != null && !taskId.matches(Regex("[a-zA-Z0-9_.-]{1,100}"))) {mutable.value=TaskHistoryState(error="任务标识无效");return}
        mutable.value=TaskHistoryState(taskId=taskId,busy=true)
        loading=viewModelScope.launch {load(current,null)}
    }
    fun loadMore() {
        val value=state.value
        if(value.busy || !value.hasMore) return
        val before=value.rows.lastOrNull()?.text("id","")?.toLongOrNull() ?: return
        val current=generation
        mutable.value=value.copy(busy=true,error="")
        loading=viewModelScope.launch {load(current,before)}
    }
    private suspend fun load(current: Long,before: Long?) {
        val taskId=state.value.taskId
        try {
            val page=loader(taskId,before,pageSize)
            if(current != generation) return
            require(page.size <= pageSize && page.all {it.text("id","").toLongOrNull()?.let {id->id>0} == true && (taskId == null || it.text("task_id","") == taskId)}) {"任务历史数据无效"}
            val previous=if(before == null) emptyList() else state.value.rows
            require(page.map {it.text("id")}.toSet().size == page.size && page.none {row->previous.any {it.text("id") == row.text("id")}}) {"任务历史分页重复，请刷新"}
            mutable.value=state.value.copy(rows=previous+page,error="",hasMore=page.size == pageSize)
        } catch(cancel: CancellationException) {throw cancel}
        catch(error: Exception) {
            if(current == generation) mutable.value=state.value.copy(error=if(error is ApiError || error is IllegalArgumentException) error.message ?: "历史加载失败" else "连接失败，已保留当前记录，请重试")
        } finally {if(current == generation) mutable.value=state.value.copy(busy=false)}
    }
    fun clear() {++generation;loading?.cancel();loading=null;mutable.value=TaskHistoryState()}
    override fun onCleared() {clear();super.onCleared()}
}
