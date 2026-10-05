package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.Instant

internal fun stepPlanLabel(value: String)=mapOf("waiting" to "等待下一次","running" to "提交进行中","paused" to "异常暂停","completed" to "已完成","stopped" to "已终止")[value] ?: "尚未开始"

@Composable internal fun StepPlanStatus(run: JsonObject,zone: String,busy: Boolean,control: (String)->Unit,records: ()->Unit) {
    val phase=run.text("status")
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(run.text("next_at")) {while(true) {now=System.currentTimeMillis();delay(1000)}}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("${run.text("name")} · ${stepPlanLabel(phase)}",style=MaterialTheme.typography.titleSmall)
        Text("最近成功提交 ${run.text("last_success","—").let {if(it=="null") "—" else it}} 步 · 成功 ${run.text("success_count","0")} 次",style=MaterialTheme.typography.bodySmall)
        Text("终止步数 ${run.obj("params").text("target")} 步",style=MaterialTheme.typography.bodySmall)
        val current=run.obj("current_job")
        if(current.isNotEmpty()) Text("当前提交 ${current.text("steps")} 步 · ${status(current.text("status"))}",style=MaterialTheme.typography.bodySmall)
        else run.text("next_at","").takeIf {it.isNotEmpty() && it!="null"}?.let {next->
            val seconds=runCatching {(Instant.parse(next).toEpochMilli()-now).coerceAtLeast(0)/1000}.getOrDefault(0)
            Text("下一次 ${run.text("next_steps")} 步 · ${date(next,zone)} · ${seconds/60} 分 ${seconds%60} 秒",style=MaterialTheme.typography.bodySmall)
        }
        if(run.text("message","").isNotBlank()) Text(run.text("message"),style=MaterialTheme.typography.bodySmall,color=if(phase=="paused") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton(records) {Text("查看本轮记录")}
            // Stopping must remain reachable while another command is waiting on GitHub.
            if(phase in setOf("waiting","running","paused")) DeskOutlinedButton({control("stop")}) {Text("终止本轮")}
            if(phase=="paused") DeskOutlinedButton({control("resume")},enabled=!busy) {Text("检查后重试 / 恢复")}
        }
    }
}

@Composable internal fun StepPlanEditor(plan: JsonObject,busy: Boolean,configured: Boolean,command: (String,JsonObject)->Unit) {
    val settings=plan.obj("settings")
    var start by rememberSaveable {mutableStateOf(settings.text("start",""))}
    var increment by rememberSaveable {mutableStateOf(settings.text("increment",""))}
    var interval by rememberSaveable {mutableStateOf(settings.text("interval_minutes",""))}
    var target by rememberSaveable {mutableStateOf(settings.text("target",""))}
    var name by rememberSaveable {mutableStateOf(settings.text("name",""))}
    var selected by rememberSaveable {mutableStateOf("")}
    var daily by rememberSaveable {mutableStateOf(settings.text("daily")=="true")}
    var clock by rememberSaveable {mutableStateOf(settings.text("start_time","08:00"))}
    var draftRevision by rememberSaveable {mutableStateOf(plan.text("revision"))}
    var pendingRevision by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(plan.text("revision"),busy) {
        if(!busy && pendingRevision!=null && plan.text("revision")!=pendingRevision) {
            draftRevision=plan.text("revision");pendingRevision=null
        }
    }
    var error by remember {mutableStateOf("")}
    var removing by remember {mutableStateOf(false)}
    val presets=plan.rows("presets")
    val parsed=runCatching {StepPlanInput.parse(start,increment,interval,target)}
    fun send(action: String,newPreset: Boolean=false) {
        runCatching {
            val p=StepPlanInput.parse(start,increment,interval,target)
            if(action=="wxstep/preset/save") require(name.isNotBlank()) {"请填写预设名称"}
            val payload=buildJsonObject {
                p.json().forEach {(k,v)->put(k,v)}
                put("name",name.ifBlank {"渐进任务"});put("daily",daily);put("start_time",clock)
                if(action.endsWith("/save")) put("revision",draftRevision)
                if(selected.isNotEmpty()) {put("preset_id",selected);if(action=="wxstep/preset/save"&&!newPreset) put("id",selected)}
            }
            if(action.endsWith("/save")) pendingRevision=draftRevision
            command(action,payload)
        }.onFailure {error=it.message ?: "请检查参数"}
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("快捷预设",style=MaterialTheme.typography.titleSmall)
        if(presets.isEmpty()) Text("填写参数和名称，保存自己的散步、跑步或逛街预设。",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            presets.forEach {preset->FilterChip(selected==preset.text("id"),{
                selected=preset.text("id");name=preset.text("name");start=preset.text("start");increment=preset.text("increment")
                interval=preset.text("interval_minutes");target=preset.text("target");draftRevision=plan.text("revision");error=""
            },label={Text(preset.text("name"))},enabled=!busy)}
        }
        for((tag,label,value,setter) in listOf(
            PlanField("start","起始步数",start,{start=it}),PlanField("increment","每次增加步数",increment,{increment=it}),
            PlanField("interval","间隔时间（分钟）",interval,{interval=it}),PlanField("target","终止步数",target,{target=it}))) {
            DeskTextField(value,{setter(it);error=""},label={Text(label)},singleLine=true,enabled=!busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("plan-$tag"))
        }
        parsed.getOrNull()?.let {Text("预计提交 ${it.count} 次 · ${it.duration} 分钟 · 最后提交 ${it.final} 步",style=MaterialTheme.typography.bodySmall)}
        Text("等于终止步数时继续；首次超过的那一次也会实际提交。",style=MaterialTheme.typography.bodySmall)
        DeskTextField(name,{name=it},label={Text("预设名称，例如散步")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton({send("wxstep/preset/save")},enabled=!busy) {Text(if(selected.isEmpty()) "保存为预设" else "更新此预设")}
            if(selected.isNotEmpty()) {
                TextButton({send("wxstep/preset/save",true)},enabled=!busy) {Text("另存为新预设")}
                TextButton({removing=true},enabled=!busy) {Text("删除选中预设")}
                for((label,offset) in listOf("前移" to -1,"后移" to 1)) TextButton({
                    val ids=presets.map {it.text("id")}.toMutableList();val i=ids.indexOf(selected);val j=i+offset
                    if(i>=0&&j in ids.indices) {val old=ids[i];ids[i]=ids[j];ids[j]=old;command("wxstep/preset/order",buildJsonObject {put("ids",JsonArray(ids.map(::JsonPrimitive)));put("revision",plan.text("revision"))})}
                },enabled=!busy) {Text(label)}
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("每日重复");Switch(daily,{daily=it},enabled=!busy)}
        DeskTextField(clock,{clock=it},label={Text("每日开始时间（HH:mm）")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("保存不提交；每日设置次日生效。运行中轮次保留原参数。时间使用工作台时区。",style=MaterialTheme.typography.bodySmall)
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            DeskOutlinedButton({send("wxstep/plan/save")},enabled=!busy && parsed.isSuccess) {Text("保存设置")}
            DeskButton({send("wxstep/plan/start")},enabled=!busy && configured && parsed.isSuccess) {Text("立即开始")}
        }
        Text("关闭 App 不影响运行；后端停止期间无法执行。",style=MaterialTheme.typography.bodySmall)
    }
    if(removing) AlertDialog(onDismissRequest={removing=false},title={Text("删除预设？")},text={Text("不影响已经开始的轮次。")},
        confirmButton={TextButton({removing=false;command("wxstep/preset/delete",buildJsonObject {put("id",selected);put("revision",plan.text("revision"))});selected=""}) {Text("删除")}},dismissButton={TextButton({removing=false}) {Text("取消")}})
}
private data class PlanField(val tag: String,val label: String,val value: String,val setter: (String)->Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun StepPlanSheet(snapshot: JsonObject,busy: Boolean,command: (String,JsonObject)->Unit,onClose: ()->Unit,
    recordsOnly: Boolean=false,loadHistory: suspend (String,Int?) -> List<JsonObject> = { _, _ -> emptyList() }, errorText: String = "") {
    val plan=snapshot.obj("wxstep_plan");val run=plan.obj("run");val zone=snapshot.text("timezone","Asia/Shanghai")
    val scope=rememberCoroutineScope()
    var history by remember {mutableStateOf<List<JsonObject>?>(null)}
    var historyId by remember {mutableStateOf("")}
    var historyError by remember {mutableStateOf("")}
    var historyBusy by remember {mutableStateOf(false)}
    var hasMore by remember {mutableStateOf(false)}
    var releaseTracking by remember {mutableStateOf(false)}
    fun load(id: String,more: Boolean=false) {
        if(historyBusy)return
        historyBusy=true;historyError="";historyId=id
        scope.launch {
            try {val rows=loadHistory(id,if(more) history?.lastOrNull()?.text("seq")?.toIntOrNull() else null);history=if(more) history.orEmpty()+rows else rows;hasMore=rows.size==100}
            catch(error: Exception) {historyError=error.message ?: "记录加载失败"}
            finally {historyBusy=false}
        }
    }
    DeskModalSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(if(recordsOnly) "步数运行记录" else "渐进步数任务",style=MaterialTheme.typography.titleMedium);TextButton(onClose) {Text("关闭")}}
            if(errorText.isNotBlank()) Text(errorText,color=MaterialTheme.colorScheme.error)
            val job=snapshot.obj("wxstep")
            if(job.text("status") in setOf("failed","tracking_error")) {
                Text("最近提交 ${job.text("steps")} 步 · ${status(job.text("status"))}")
                Text(job.text("message"),color=MaterialTheme.colorScheme.error)
                if(job.text("status")=="tracking_error") TextButton({releaseTracking=true}) {Text("检查后结束本地跟踪")}
            }
            if(!recordsOnly && history==null) StepPlanEditor(plan,busy,snapshot.obj("configured").text("github")=="true",command)
            if(run.isNotEmpty()) {
                HorizontalDivider()
                StepPlanStatus(run,zone,busy,{action->command("wxstep/plan/$action",buildJsonObject {put("run_id",run.text("id"))})},{load(run.text("id"))})
            }
            if(plan.obj("settings").text("daily")=="true") DeskOutlinedButton({command("wxstep/plan/disable",buildJsonObject {})}) {Text("关闭每日计划")}
            if(historyError.isNotEmpty()) Text(historyError,color=MaterialTheme.colorScheme.error)
            if(historyBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val rows=history ?: run.rows("records")
            if(rows.isNotEmpty()) Text("提交记录",style=MaterialTheme.typography.titleSmall)
            rows.forEach {record->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("${record.text("steps")} 步 · ${status(record.text("status"))}")
                    Text("${date(record.text("created_at"),zone)} · ${if(record.text("source")=="manual") "手动提交" else "计划提交"}",style=MaterialTheme.typography.bodySmall)
                    if(record.text("status") in setOf("failed","tracking_error")) Text(record.text("message"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    HorizontalDivider()
                }
            }
            if(history==null && rows.size>=20) TextButton({load(run.text("id"))},enabled=!historyBusy) {Text("加载更多记录")}
            if(hasMore) TextButton({load(historyId,true)},enabled=!historyBusy) {Text("加载更早记录")}
            if(history!=null) TextButton({history=null;historyId="";hasMore=false}) {Text("返回当前轮次")}
            plan.rows("recent_runs").filter {it.text("id")!=run.text("id")}.forEach {old->TextButton({load(old.text("id"))},enabled=!historyBusy) {Text("${old.text("name")} · ${date(old.text("started_at"),zone)} · ${stepPlanLabel(old.text("status"))}")}}
            Text("记录为提交和工作流结果，实际微信运动步数请在微信中核对。",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(releaseTracking) AlertDialog(onDismissRequest={releaseTracking=false},title={Text("结束本地跟踪？")},
        text={Text("请先在 GitHub Actions 确认本次任务。此操作不会取消已经发出的提交。")},
        confirmButton={TextButton({releaseTracking=false;command("wxstep/release",buildJsonObject {put("confirmed",true)})}) {Text("已检查，结束跟踪")}},
        dismissButton={TextButton({releaseTracking=false}) {Text("取消")}})
}
