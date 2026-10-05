package app.mydesk.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable internal fun StepsShortcut(open: ()->Unit) {
    DeskCard("步数",headerAction={TextButton(open) {Text("打开 ›")}}) {
        Text("自动任务、手动提交与运行记录",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun StepsPage(snapshot: JsonObject,busy: Boolean,message: String,section: Int,onSection: (Int)->Unit,
    command: (String,JsonObject)->Unit,loadHistory: suspend (String,Int?)->List<JsonObject>,openUrl: (String)->Unit,
    modifier: Modifier=Modifier,navigationKey: Int=0) {
    val plan=snapshot.obj("wxstep_plan")
    val current=plan.obj("run")
    val zone=snapshot.text("timezone","Asia/Shanghai")
    var selectedId by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedSummary by remember {mutableStateOf<JsonObject?>(null)}
    val selected=selectedId?.let {id->
        if(current.text("id")==id) current else plan.rows("recent_runs").firstOrNull {it.text("id")==id} ?: selectedSummary
    }
    fun detail(run: JsonObject) {selectedSummary=run;selectedId=run.text("id")}
    LaunchedEffect(section,navigationKey) {selectedId=null;selectedSummary=null}
    BackHandler(selectedId!=null) {selectedId=null;selectedSummary=null}
    if(selected!=null) {
        key(selectedId) {StepRunDetail(selected,zone,busy,command,loadHistory,openUrl,{selectedId=null;selectedSummary=null},modifier)}
        return
    }
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text("步数",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)}
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("自动任务","手动提交","任务记录").forEachIndexed {index,title->
                    SegmentedButton(selected=section==index,onClick={onSection(index)},shape=SegmentedButtonDefaults.itemShape(index,3),icon={}) {Text(title)}
                }
            }
        }
        if(message.isNotBlank()) item {Text(message,color=MaterialTheme.colorScheme.primary)}
        if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        when(section) {
            0->item {
                DeskCard("自动任务",headerAction={TextButton({onSection(2)}) {Text("关闭")}}) {
                    if(snapshot.obj("configured").text("github")!="true") Text("请先在设置中连接步数工作流",style=MaterialTheme.typography.bodySmall)
                    StepPlanEditor(plan,busy,snapshot.obj("configured").text("github")=="true",command)
                }
            }
            1->item {StepsCard(snapshot,busy,command) {onSection(0)}}
            else->{
                val job=snapshot.obj("wxstep")
                if(job.text("status") in setOf("failed","tracking_error")) item {
                    DeskCard("最近提交异常") {StepSubmissionIssue(job,busy,command,openUrl)}
                }
                if(current.text("status") in setOf("waiting","running","paused")) {
                    item {Text("当前任务",style=MaterialTheme.typography.titleMedium)}
                    item {DeskCard("") {
                        StepPlanStatus(current,zone,busy,{action->command("wxstep/plan/$action",buildJsonObject {put("run_id",current.text("id"))})},{detail(current)})
                    }}
                }
                item {Text("历史任务",style=MaterialTheme.typography.titleMedium)}
                val runs=plan.rows("recent_runs").filter {it.text("id")!=current.text("id") || current.text("status") !in setOf("waiting","running","paused")}
                if(runs.isEmpty()) item {Text("暂无已结束的任务",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(runs,key={it.text("id")}) {run->
                    Surface(onClick={detail(run)},modifier=Modifier.fillMaxWidth().testTag("step-run-${run.text("id")}"),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(stepPlanName(run.text("name")),style=MaterialTheme.typography.titleSmall)
                                Text("${date(run.text("started_at"),zone)} · 成功 ${run.text("success_count","0")} 次",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DeskStateBadge(stepPlanLabel(run.text("status")),run.text("status")=="paused")
                            Text("›",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if(plan.obj("settings").text("daily")=="true") item {
                    DeskOutlinedButton({command("wxstep/plan/disable",buildJsonObject {})},enabled=!busy) {Text("关闭每日计划")}
                }
            }
        }
    }
}

@Composable internal fun StepSubmissionIssue(job: JsonObject,busy: Boolean,command: (String,JsonObject)->Unit,openUrl: (String)->Unit) {
    var release by remember {mutableStateOf(false)}
    StepsResult(job)
    job.text("url").takeIf {it.startsWith("https://github.com/")}?.let {url->TextButton({openUrl(url)}) {Text("查看工作流原因")}}
    if(job.text("status")=="tracking_error") TextButton({release=true},enabled=!busy) {Text("检查后结束本地跟踪")}
    if(release) AlertDialog(onDismissRequest={release=false},title={Text("结束本地跟踪？")},
        text={Text("请先在 GitHub Actions 确认本次任务。此操作不会取消已经发出的提交。")},
        confirmButton={TextButton({release=false;command("wxstep/release",buildJsonObject {put("confirmed",true)})}) {Text("已检查，结束跟踪")}},
        dismissButton={TextButton({release=false}) {Text("取消")}})
}

@Composable internal fun StepRunDetail(run: JsonObject,zone: String,busy: Boolean,command: (String,JsonObject)->Unit,
    loadHistory: suspend (String,Int?)->List<JsonObject>,openUrl: (String)->Unit,onClose: ()->Unit,modifier: Modifier=Modifier) {
    val id=run.text("id")
    var rows by remember(id) {mutableStateOf(run.rows("records"))}
    var loading by remember(id) {mutableStateOf(true)}
    var error by remember(id) {mutableStateOf("")}
    var hasMore by remember(id) {mutableStateOf(false)}
    val liveRows by rememberUpdatedState(run.rows("records"))
    val scope=rememberCoroutineScope()
    fun merge(new: List<JsonObject>,old: List<JsonObject>)=(new+old).distinctBy {it.text("seq")}.sortedByDescending {it.text("seq").toIntOrNull() ?: 0}
    suspend fun fetch(more: Boolean) {
        loading=true;error=""
        try {
            val page=loadHistory(id,if(more) rows.lastOrNull()?.text("seq")?.toIntOrNull() else null)
            rows=merge(liveRows,merge(page,rows));hasMore=page.size==100
        } catch(cancel: CancellationException) {throw cancel}
        catch(failure: Exception) {error=failure.message ?: "本轮记录加载失败"}
        finally {loading=false}
    }
    LaunchedEffect(id) {fetch(false)}
    LaunchedEffect(id,run.rows("records")) {rows=merge(run.rows("records"),rows)}
    LazyColumn(modifier.fillMaxSize().testTag("step-run-detail"),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("${stepPlanName(run.text("name"))} · 本轮详情",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TextButton(onClose) {Text("关闭")}
        }}
        item {Text("${date(run.text("started_at"),zone)} 开始",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {DeskCard("本轮概况") {
            if(run.text("status") in setOf("waiting","running","paused")) {
                StepPlanStatus(run,zone,busy,{action->command("wxstep/plan/$action",buildJsonObject {put("run_id",id)})},{scope.launch {fetch(false)}})
            } else {
                Text("${run.text("last_success","—").let {if(it=="null") "—" else it}} 步",style=MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("成功 ${run.text("success_count","0")} 次",style=MaterialTheme.typography.bodyMedium)
                    DeskStateBadge(stepPlanLabel(run.text("status")),false)
                }
            }
            val p=run.obj("params")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text("起始 ${p.text("start")} 步");Text("每次 +${p.text("increment")} 步")}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text("终止 ${p.text("target")} 步");Text("间隔 ${p.text("interval_minutes")} 分钟")}
            }
        }}
        item {Text("本轮提交记录",style=MaterialTheme.typography.titleMedium)}
        if(loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(error.isNotBlank()) item {Text(error,color=MaterialTheme.colorScheme.error);TextButton({scope.launch {fetch(false)}},enabled=!loading) {Text("重试")}}
        if(rows.isEmpty() && !loading && error.isBlank()) item {Text("本轮暂无提交记录")}
        items(rows,key={it.text("seq")}) {record->
            Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("${record.text("steps")} 步",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                        DeskStateBadge(status(record.text("status")),record.text("status") in setOf("failed","tracking_error"))
                    }
                    Text("${date(record.text("created_at"),zone)} · ${if(record.text("source")=="manual") "手动提交" else "自动提交"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(record.text("status") in setOf("failed","tracking_error")) {
                        val raw=record.text("message")
                        if(raw.isNotBlank() && raw!="failure") Text(raw,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                        record.text("url").takeIf {it.startsWith("https://github.com/")}?.let {url->TextButton({openUrl(url)}) {Text("查看失败原因")}}
                    }
                }
            }
        }
        if(hasMore) item {DeskOutlinedButton({scope.launch {fetch(true)}},enabled=!loading) {Text("加载更早记录")}}
        item {DeskOutlinedButton(onClose,Modifier.fillMaxWidth()) {Text("返回任务列表")}}
    }
}
