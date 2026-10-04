package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.util.UUID

internal fun githubPreset(key: String): JsonObject=buildJsonObject {
    put("name",when(key) {"52fzwg"->"52 辅助论坛签到";"glados"->"GLaDOS 签到";else->""})
    put("owner","")
    put("repo",when(key) {"52fzwg"->"52fzwg-checkin";"glados"->"Glados-Railgun-checkin";else->""})
    put("workflow",when(key) {"52fzwg"->"checkin.yml";"glados"->"gladosCheck.yml";else->""})
    put("ref",if(key=="glados") "master" else "main")
    put("adapter",if(key=="custom") "workflow" else key)
    put("step",when(key) {"52fzwg"->"Check in";"glados"->"Running checkin";else->"MyDesk result"})
    put("max_age_hours",SettingsInput.DEFAULT_TASK_TIMEOUT_HOURS);put("enabled",true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GitHubTaskSettings(settings: JsonObject,busy: Boolean,save: (JsonObject,()->Unit)->Unit,
    onClose: ()->Unit,checkTask: (String)->Unit,feedback: String) {
    var mode by remember {mutableStateOf("")}
    var key by remember {mutableStateOf("")}
    var draft by remember {mutableStateOf(buildJsonObject {})}
    var remove by remember {mutableStateOf(false)}
    val tasks=settings.obj("github_tasks")
    fun edit(id: String,value: JsonObject) {key=id;draft=value;mode="edit"}
    fun saveTask(value: JsonObject) {
        val payload=if(key=="wxstep") buildJsonObject {put("github",value)} else buildJsonObject {
            put("github_tasks",buildJsonObject {tasks.forEach {(id,item)->put(id,item)};put(key,value)})
        }
        save(payload) {mode="";draft=buildJsonObject {}}
    }
    DeskModalSheet(onDismissRequest={if(!busy) onClose()},dragDismissEnabled=!busy,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(mode.isNotEmpty()) IconButton({mode="";draft=buildJsonObject {}},enabled=!busy,modifier=Modifier.size(48.dp)) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回 GitHub 任务")}
                Text(when(mode) {"add"->"添加任务";"edit"->if(key=="wxstep") "微信步数" else "任务设置";else->"GitHub 任务"},Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton(onClose,enabled=!busy,modifier=Modifier.size(48.dp)) {Icon(Icons.Default.Close,"关闭 GitHub 任务")}
            }
            if(feedback.isNotBlank()) Text(feedback,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            when(mode) {
                "add" -> {
                    Text("每项任务独立配置仓库与 Token。",style=MaterialTheme.typography.bodyMedium)
                    listOf("custom" to "自定义任务","52fzwg" to "52 辅助论坛","glados" to "GLaDOS").forEach {(id,label)->
                        DeskOutlinedButton({edit(if(id=="custom") "task_"+UUID.randomUUID().toString().replace("-","").take(12) else id,githubPreset(id))},
                            enabled=!busy && (id=="custom" || !tasks.containsKey(id)),modifier=Modifier.fillMaxWidth()) {Text(label)}
                    }
                }
                "edit" -> GitHubTaskEditor(key,draft,busy,::saveTask,
                    exists=if(key=="wxstep") settings.obj("github").isNotEmpty() else tasks.containsKey(key),
                    check={checkTask(key)},remove={remove=true})
                else -> {
                    Text("微信步数与自动任务在这里单独管理。",style=MaterialTheme.typography.bodyMedium)
                    GitHubTaskRow("微信步数",settings.obj("github"),busy,manual=true) {edit("wxstep",settings.obj("github"))}
                    tasks.entries.forEach {(id,item)->GitHubTaskRow(item.jsonObject.text("name"),item.jsonObject,busy) {edit(id,item.jsonObject)}}
                    DeskButton({mode="add"},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("添加任务")}
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if(remove) AlertDialog(onDismissRequest={if(!busy) remove=false},title={Text("移除此接入？")},text={Text("停止同步并移除此项配置，已有任务历史保留。")},confirmButton={TextButton({
        val payload=if(key=="wxstep") buildJsonObject {put("github",JsonNull)} else buildJsonObject {put("github_tasks",buildJsonObject {tasks.filterKeys {it!=key}.forEach {(id,item)->put(id,item)}})}
        save(payload) {remove=false;mode="";draft=buildJsonObject {}}
    },enabled=!busy) {Text("移除")}},dismissButton={TextButton({remove=false},enabled=!busy) {Text("取消")}})
}

@Composable private fun GitHubTaskRow(name: String,current: JsonObject,busy: Boolean,manual: Boolean=false,onClick: ()->Unit) {
    Surface(onClick=onClick,enabled=!busy,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(name,style=MaterialTheme.typography.titleMedium)
                Text(when {current.isEmpty()->"未配置";current.text("credential_set")!="true"->"待填写 Token";current.text("enabled","true")=="false"->"已暂停";else->"已配置"},style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(current.isNotEmpty()) {
                Text("${current.text("owner")}/${current.text("repo")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${current.text("workflow")} · ${current.text("ref","main")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(!manual) {
                    val hours=current["max_age_hours"]?.jsonPrimitive?.doubleOrNull ?: SettingsInput.DEFAULT_TASK_TIMEOUT_HOURS.toDouble()
                    val label=if(hours%1.0==0.0) hours.toLong().toString() else hours.toString()
                    Text(if(current.text("enabled","true")=="false") "超期提醒已暂停" else "超期提醒 · $label 小时",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun GitHubTaskEditor(id: String,current: JsonObject,busy: Boolean,onSave: (JsonObject)->Unit,
    exists: Boolean,check: ()->Unit,remove: ()->Unit) {
    val steps=id=="wxstep"
    val values=remember(id,current) {mutableStateMapOf("name" to current.text("name",if(steps) "微信步数" else ""),
        "owner" to current.text("owner",if(steps) "xiaoxiaoxiaoHuanGe" else ""),"repo" to current.text("repo",if(steps) "WxStepCustom" else ""),
        "workflow" to current.text("workflow",if(steps) "reachability.yml" else ""),"ref" to current.text("ref","main"),
        "step" to current.text("step","MyDesk result"),"max_age_hours" to current.text("max_age_hours",SettingsInput.DEFAULT_TASK_TIMEOUT_HOURS.toString()),"token" to "")}
    var adapter by remember(id,current) {mutableStateOf(current.text("adapter","standard"))}
    var enabled by remember(id,current) {mutableStateOf(current.text("enabled","true")!="false" || current.text("credential_set")!="true")}
    var advanced by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val credentialSet=current.text("credential_set")=="true"
    if(!steps) DeskTextField(values["name"].orEmpty(),{values["name"]=it;error=""},label={Text("任务名称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
    listOf("owner" to "仓库所有者","repo" to "仓库名","workflow" to "工作流文件名","ref" to "分支").forEach {(field,label)->
        DeskTextField(values[field].orEmpty(),{values[field]=it;error=""},label={Text(label)},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
    }
    DeskTextField(values["token"].orEmpty(),{values["token"]=it;error=""},label={Text("访问 Token")},singleLine=true,enabled=!busy,
        visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),
        supportingText={Text(if(credentialSet) "此任务已有 Token，留空保留" else if(steps) "此任务单独使用，需有 Actions 执行权限" else "此任务单独使用，需有 Actions 读取权限")})
    if(!steps) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("启用同步",Modifier.weight(1f));Switch(enabled,onCheckedChange={enabled=it},enabled=!busy)
        }
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("超期提醒",style=MaterialTheme.typography.titleSmall)
                DeskTextField(values["max_age_hours"].orEmpty(),{values["max_age_hours"]=it;error=""},label={Text("超期时间（小时）")},singleLine=true,enabled=!busy,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                Text("超过此时长没有新结果，工作台显示待处理。默认 36 小时，留空恢复默认。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DeskOutlinedButton({advanced=!advanced},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(if(advanced) "收起高级设置" else "高级设置")}
        if(advanced) {
            Text("结果格式",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val choices=listOf("standard" to "标准结果","workflow" to "工作流状态")+if(adapter in listOf("52fzwg","glados")) listOf(adapter to "现有签到格式") else emptyList()
                choices.forEach {(value,label)->FilterChip(adapter==value,onClick={adapter=value;if(value=="standard") values["step"]="MyDesk result"},label={Text(label)},enabled=!busy)}
            }
            if(adapter!="workflow") DeskTextField(values["step"].orEmpty(),{values["step"]=it},label={Text("结果步骤名称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        }
    }
    if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    DeskButton({
        runCatching {buildJsonObject {
            for(field in listOf("owner","repo","workflow","ref")) {val value=values[field].orEmpty().trim();require(value.isNotEmpty()) {"请补全仓库、工作流和分支"};put(field,value)}
            val token=values["token"].orEmpty().trim()
            require(token.isNotEmpty() || credentialSet || (!steps && !enabled)) {"请为此任务填写独立 Token"}
            if(token.isNotEmpty()) put("token",token)
            if(!steps) {
                require(values["name"].orEmpty().isNotBlank()) {"请填写任务名称"}
                val hours=SettingsInput.taskTimeout(values["max_age_hours"].orEmpty())
                put("name",values["name"].orEmpty().trim());put("enabled",enabled);put("adapter",adapter);put("step",values["step"].orEmpty().trim());put("max_age_hours",hours)
            }
        }}.onSuccess(onSave).onFailure {error=it.message ?: "请检查任务配置"}
    },enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(if(busy) "保存中…" else "保存任务")}
    if(exists) {
        DeskOutlinedButton(check,enabled=!busy && credentialSet && values["token"].isNullOrEmpty(),modifier=Modifier.fillMaxWidth()) {Text("检查已保存的连接")}
        TextButton(remove,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("移除此接入",color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

