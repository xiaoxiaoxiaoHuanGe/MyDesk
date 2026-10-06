package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.Instant

internal fun stepPlanLabel(value: String)=mapOf("waiting" to "等待下一次","running" to "提交进行中","paused" to "异常暂停","completed" to "已完成","stopped" to "已终止")[value] ?: "尚未开始"
internal fun stepPlanName(value: String)=if(value in setOf("渐进任务","渐进步数任务")) "自动任务" else value

internal fun stepPresetSelection(plan: JsonObject): String {
    val settings=plan.obj("settings");val presets=plan.rows("presets")
    val linked=presets.firstOrNull {it.text("id")==settings.text("preset_id")}
    val legacy=presets.firstOrNull {preset->preset.text("name")==settings.text("name") &&
        listOf("start","increment","interval_minutes","target").all {preset.text(it)==settings.text(it)}}
    return (linked ?: legacy)?.text("id") ?: ""
}

@Composable internal fun StepPlanStatus(run: JsonObject,zone: String,busy: Boolean,control: (String)->Unit,records: ()->Unit) {
    val phase=run.text("status")
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(run.text("next_at","")) {if(run.text("next_at","") !in setOf("","null")) while(true) {now=System.currentTimeMillis();delay(1000)}}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stepPlanName(run.text("name")),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            DeskStateBadge(stepPlanLabel(phase),phase=="paused")
        }
        Text("${run.text("last_success","—").let {if(it=="null") "—" else it}} 步",style=MaterialTheme.typography.headlineSmall)
        Text("最近成功提交 · 成功 ${run.text("success_count","0")} 次",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("终止步数 ${run.obj("params").text("target")} 步",style=MaterialTheme.typography.bodySmall)
        val current=run.obj("current_job")
        if(current.isNotEmpty()) Text("当前提交 ${current.text("steps")} 步 · ${status(current.text("status"))}",style=MaterialTheme.typography.bodySmall)
        else run.text("next_at","").takeIf {it.isNotEmpty() && it!="null"}?.let {next->
            val seconds=runCatching {(Instant.parse(next).toEpochMilli()-now).coerceAtLeast(0)/1000}.getOrDefault(0)
            Text("下一次 ${run.text("next_steps")} 步 · ${date(next,zone)} · ${seconds/60} 分 ${seconds%60} 秒",style=MaterialTheme.typography.bodySmall)
        }
        if(run.text("message","").isNotBlank()) Text(if(run.text("message")=="failure") "本轮提交失败" else run.text("message"),style=MaterialTheme.typography.bodySmall,color=if(phase=="paused") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton(records) {Text("查看本轮记录")}
            // Stopping must remain reachable while another command is waiting on GitHub.
            if(phase in setOf("waiting","running","paused")) DeskOutlinedButton({control("stop")}) {Text("终止本轮")}
            if(phase=="paused") DeskOutlinedButton({control("resume")},enabled=!busy) {Text("恢复任务")}
        }
    }
}

@Composable internal fun StepPlanEditor(plan: JsonObject,busy: Boolean,configured: Boolean,command: (String,JsonObject)->Unit) {
    val settings=plan.obj("settings")
    var start by rememberSaveable {mutableStateOf(settings.text("start",""))}
    var increment by rememberSaveable {mutableStateOf(settings.text("increment",""))}
    var interval by rememberSaveable {mutableStateOf(settings.text("interval_minutes",""))}
    var target by rememberSaveable {mutableStateOf(settings.text("target",""))}
    var name by rememberSaveable {mutableStateOf(stepPlanName(settings.text("name","")))}
    var selected by rememberSaveable {mutableStateOf(stepPresetSelection(plan))}
    var daily by rememberSaveable {mutableStateOf(settings.text("daily")=="true")}
    var clock by rememberSaveable {mutableStateOf(settings.text("start_time","08:00"))}
    var draftRevision by rememberSaveable {mutableStateOf(plan.text("revision"))}
    var pendingRevision by remember {mutableStateOf<String?>(null)}
    var initialized by rememberSaveable {mutableStateOf(settings.containsKey("start"))}
    LaunchedEffect(plan.text("revision")) {
        if(!initialized && settings.containsKey("start")) {
            start=settings.text("start");increment=settings.text("increment");interval=settings.text("interval_minutes");target=settings.text("target")
            name=stepPlanName(settings.text("name"));selected=stepPresetSelection(plan);daily=settings.text("daily")=="true";clock=settings.text("start_time","08:00")
            draftRevision=plan.text("revision");initialized=true
        }
    }
    LaunchedEffect(plan.text("revision"),busy) {
        if(!busy && pendingRevision!=null && plan.text("revision")!=pendingRevision) {
            draftRevision=plan.text("revision");pendingRevision=null
            selected=stepPresetSelection(plan)
        }
    }
    var error by remember {mutableStateOf("")}
    var removing by remember {mutableStateOf(false)}
    var managing by remember {mutableStateOf(false)}
    val presets=plan.rows("presets")
    val parsed=runCatching {StepPlanInput.parse(start,increment,interval,target)}
    fun send(action: String,newPreset: Boolean=false) {
        runCatching {
            val p=StepPlanInput.parse(start,increment,interval,target)
            if(daily) require(clock.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))) {"每日开始时间请填写为 08:00 这样的格式"}
            val payload=buildJsonObject {
                p.json().forEach {(k,v)->put(k,v)}
                put("name",name.ifBlank {"自动任务"});put("daily",daily)
                put("start_time",if(daily) clock else clock.takeIf {it.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))} ?: settings.text("start_time","08:00"))
                put("revision",draftRevision)
                if(action.endsWith("/save")) put("save_preset",true)
                if(selected.isNotEmpty() && !newPreset) put("preset_id",selected)
            }
            pendingRevision=draftRevision
            error=""
            command(action,payload)
        }.onFailure {error=it.message ?: "请检查参数"}
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("快捷配置",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            Box {
                TextButton({managing=true},enabled=!busy) {Text("管理")}
                DropdownMenu(managing,{managing=false}) {
                    DropdownMenuItem(text={Text("新建配置")},onClick={selected="";name="";draftRevision=plan.text("revision");managing=false;error=""})
                    if(selected.isNotEmpty()) {
                        DropdownMenuItem(text={Text("删除当前配置")},onClick={managing=false;removing=true})
                        for((label,offset) in listOf("前移" to -1,"后移" to 1)) DropdownMenuItem(text={Text(label)},onClick={
                            val ids=presets.map {it.text("id")}.toMutableList();val i=ids.indexOf(selected);val j=i+offset
                            if(i>=0&&j in ids.indices) {val old=ids[i];ids[i]=ids[j];ids[j]=old;pendingRevision=plan.text("revision");command("wxstep/preset/order",buildJsonObject {put("ids",JsonArray(ids.map(::JsonPrimitive)));put("revision",plan.text("revision"))})}
                            managing=false
                        })
                    }
                }
            }
        }
        if(presets.isNotEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            presets.forEach {preset->FilterChip(selected==preset.text("id"),{
                val arrangement=if(preset.containsKey("daily")) preset else if(preset.text("id")==stepPresetSelection(plan)) settings else buildJsonObject {}
                selected=preset.text("id");name=preset.text("name");start=preset.text("start");increment=preset.text("increment")
                interval=preset.text("interval_minutes");target=preset.text("target");daily=arrangement.text("daily")=="true";clock=arrangement.text("start_time","08:00");draftRevision=plan.text("revision");error=""
            },label={Text(preset.text("name"))},enabled=!busy)}
        }
        Row(Modifier.fillMaxWidth().testTag("plan-name-row"),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            DeskTextField(name,{name=it;error=""},label={Text("预设名称")},enabled=!busy,singleLine=true,modifier=Modifier.weight(1f).testTag("plan-name"))
            Surface(Modifier.weight(1f).testTag("plan-preview"),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    val p=parsed.getOrNull()
                    Text(if(p!=null) "预计 ${p.duration} 分钟" else "预计耗时",style=MaterialTheme.typography.titleSmall)
                    Text(if(p!=null) "${p.count} 次 · 最后 ${p.final} 步" else "填入步数参数后显示",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        for(row in listOf(
            listOf(PlanField("start","起始步数",start,{start=it}),PlanField("target","终止步数",target,{target=it})),
            listOf(PlanField("increment","每次增加步数",increment,{increment=it}),PlanField("interval","间隔时间（分钟）",interval,{interval=it})))) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach {field->DeskTextField(field.value,{field.setter(it);error=""},label={Text(field.label,style=MaterialTheme.typography.bodySmall)},singleLine=true,enabled=!busy,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f).testTag("plan-${field.tag}"))}
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {Text("每日重复");Switch(daily,{daily=it;error=""},enabled=!busy)}
        if(daily) {
            DeskTextField(clock,{clock=it;error=""},label={Text("每日开始时间")},placeholder={Text("08:00")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("每日计划从明天开始，按工作台时区执行。",style=MaterialTheme.typography.bodySmall)
        }
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            DeskOutlinedButton({send("wxstep/plan/save")},modifier=Modifier.weight(1f),enabled=!busy && parsed.isSuccess) {Text("保存配置")}
            DeskButton({send("wxstep/plan/start")},modifier=Modifier.weight(1f),enabled=!busy && configured && parsed.isSuccess) {
                Icon(Icons.Default.PlayArrow,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("立即开始")
            }
        }
    }
    if(removing) AlertDialog(onDismissRequest={removing=false},title={Text("删除预设？")},text={Text("不影响已经开始的轮次。")},
        confirmButton={TextButton({removing=false;pendingRevision=plan.text("revision");command("wxstep/preset/delete",buildJsonObject {put("id",selected);put("revision",plan.text("revision"))});selected=""}) {Text("删除")}},dismissButton={TextButton({removing=false}) {Text("取消")}})
}
private data class PlanField(val tag: String,val label: String,val value: String,val setter: (String)->Unit)
