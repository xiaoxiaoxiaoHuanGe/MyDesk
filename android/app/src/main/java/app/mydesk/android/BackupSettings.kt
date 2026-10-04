package app.mydesk.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BackupSettings(graph: AppGraph,onRestored: ()->Unit,enabled: Boolean) {
    val context=LocalContext.current.applicationContext
    val model: BackupViewModel=viewModel(key="backup:${graph.state.value.server}",factory=remember(graph,context) {
        viewModelFactory {initializer {BackupViewModel(graph,context)}}
    })
    val state by model.state.collectAsStateWithLifecycle()
    var showing by remember {mutableStateOf(false)}
    // Passwords stay only in composition memory; never in saved instance state or preferences.
    var password by remember(showing,state.stage) {mutableStateOf("")}
    var confirmation by remember(showing,state.stage) {mutableStateOf("")}
    var replace by remember(showing) {mutableStateOf(false)}
    var confirmed by remember(state.preview) {mutableStateOf(false)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"),model::save)
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(),model::read)
    LaunchedEffect(state.pendingSave) {
        if(state.pendingSave) {model.pickerLaunched();password="";confirmation="";save.launch(state.filename)}
    }
    val last=remember(state.lastExport) {runCatching {
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(state.lastExport))
    }.getOrDefault("")}
    DeskCard("") {
        DeskSettingRow("备份与恢复",if(last.isEmpty()) "加密保存与恢复接入配置" else "上次导出 $last",
            {model.open("home");showing=true},enabled=enabled && !state.busy,icon=SettingsGlyphs.Backup)
    }
    if(showing) DeskModalSheet(onDismissRequest={if(!state.busy) {showing=false;model.close()}},dragDismissEnabled=!state.busy) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(when(state.stage) {"export"->"导出备份";"import"->"导入备份";"preview"->"恢复预览";else->"备份与恢复"},Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                IconButton({showing=false;model.close()},enabled=!state.busy) {Icon(Icons.Default.Close,"关闭备份与恢复")}
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(state.stage=="home") {
                    item {Text("包含 GitHub、邮箱、服务器、网络节点及通用设置，接入凭据一并加密。登录账号、提醒和历史记录不在此备份中。",style=MaterialTheme.typography.bodyMedium)}
                    item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        DeskOutlinedButton({model.open("import");pick.launch(arrayOf("*/*"))},Modifier.weight(1f),enabled=!state.busy) {Text("导入备份")}
                        DeskButton({model.open("export")},Modifier.weight(1f),enabled=!state.busy) {Text("导出备份")}
                    }}
                    if(last.isNotEmpty()) item {Text("上次导出 $last",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                } else if(state.stage in setOf("export","import")) {
                    if(state.stage=="import") item {
                        DeskSettingRow("选择备份文件",if(state.file==null) "MyDesk 加密备份 · .mydesk" else "已选择加密备份",{pick.launch(arrayOf("*/*"))},enabled=!state.busy)
                    }
                    item {DeskTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("备份密码")},singleLine=true,enabled=!state.busy,
                        visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))}
                    if(state.stage=="export") {
                        item {DeskTextField(confirmation,{confirmation=it},Modifier.fillMaxWidth(),label={Text("再次输入备份密码")},singleLine=true,enabled=!state.busy,
                            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))}
                        item {Text("设置独立的 12–256 位备份密码。恢复时需要此密码，请自行保存。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        item {DeskButton({model.export(password,confirmation)},Modifier.fillMaxWidth(),enabled=!state.busy && password.isNotEmpty() && confirmation.isNotEmpty()) {Text(if(state.busy) "加密中…" else "加密并保存")}}
                    } else {
                        item {Column {
                            BackupChoice("合并接入","保留其他接入，恢复备份中的通用设置",!replace,!state.busy) {replace=false}
                            BackupChoice("全部替换","移除备份中没有的接入",replace,!state.busy) {replace=true}
                        }}
                        item {DeskButton({model.preview(password,replace)},Modifier.fillMaxWidth(),enabled=!state.busy && state.file!=null && password.isNotEmpty()) {Text(if(state.busy) "读取中…" else "查看恢复预览")}}
                    }
                } else if(state.stage=="preview") {
                    val preview=state.preview ?: buildJsonObject {}
                    item {Text(if(preview.text("mode")=="replace") "全部替换配置" else "合并接入配置",style=MaterialTheme.typography.titleSmall)}
                    item {Text(preview.text("message"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    item {Text("时区：${preview.text("timezone")} · 历史保留 ${preview.text("history_days")} 天",style=MaterialTheme.typography.bodySmall)}
                    item {Text("来源：${preview.text("source_server","未知")}\n备份时间：${preview.text("created_at").replace('T',' ').substringBefore('.')}",style=MaterialTheme.typography.bodySmall)}
                    item {Text("微信步数：${if(preview["steps_configured"]?.jsonPrimitive?.booleanOrNull==true) "已配置" else "未配置"} · 网络节点：${preview.text("network_nodes","0")}",style=MaterialTheme.typography.bodySmall)}
                    items(preview.rows("changes").size) {index->
                        val change=preview.rows("changes")[index]
                        val kind=when(change.text("kind")) {"github_tasks"->"GitHub";"gmail_accounts"->"邮箱";else->"服务器"}
                        val action=when(change.text("action")) {"added"->"新增";"updated"->"更新";else->"移除"}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {Text(change.text("name"));Text(kind,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            Text(action,style=MaterialTheme.typography.labelMedium,color=if(action=="移除") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                    if(preview.text("mode")=="replace") item {
                        Row(Modifier.fillMaxWidth().toggleable(confirmed,enabled=!state.busy,role=Role.Checkbox,onValueChange={confirmed=it}),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(confirmed,onCheckedChange=null);Text("确认替换全部接入配置",style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item {DeskButton({model.restore(confirmed,onRestored)},Modifier.fillMaxWidth(),enabled=!state.busy && (preview.text("mode")!="replace" || confirmed)) {Text(if(state.busy) "恢复中…" else "恢复配置")}}
                    item {Text("恢复前会在后端保留原配置的加密备份，使用本次备份密码解密。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
                if(state.message.isNotEmpty()) item {Text(state.message,color=if(state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyMedium)}
                if(state.stage!="home") item {TextButton({model.open("home")},enabled=!state.busy) {Text("返回")}}
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun BackupChoice(title: String,subtitle: String,selected: Boolean,enabled: Boolean,onClick: ()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).selectable(selected,enabled=enabled,role=Role.RadioButton,onClick=onClick),verticalAlignment=Alignment.CenterVertically) {
        RadioButton(selected,onClick=null,enabled=enabled)
        Column {Text(title,style=MaterialTheme.typography.bodyMedium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
