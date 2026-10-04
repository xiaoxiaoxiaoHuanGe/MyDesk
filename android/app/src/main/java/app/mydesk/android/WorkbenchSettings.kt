package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkbenchSettings(config: JsonObject?,busy: Boolean,save: (JsonObject,()->Unit)->Unit,serverError: String="") {
    var section by remember {mutableStateOf("")}
    DeskCard("工作台与监控") {
        DeskSettingRow("时区与历史保留","时间显示与记录保存",{section="basic"},enabled=config!=null && !busy,icon=SettingsGlyphs.Clock)
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        DeskSettingRow("网络连通性","搜索与管理检测节点",{section="network"},enabled=config!=null && !busy,icon=SettingsGlyphs.Network)
    }
    if(section=="network" && config!=null) NetworkSettings(config,busy,save,{section=""},serverError)
    if(section=="basic" && config!=null) {
        var zone by remember {mutableStateOf(config.text("timezone","Asia/Shanghai"))}
        var days by remember {mutableStateOf(config.text("history_days","90"))}
        var error by remember {mutableStateOf("")}
        DeskModalSheet(onDismissRequest={if(!busy) section=""},dragDismissEnabled=!busy,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("时区与历史保留",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    IconButton({section=""},enabled=!busy) {Icon(Icons.Default.Close,"关闭设置")}
                }
                LazyColumn(Modifier.heightIn(max=360.dp).testTag("settings-fields"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item {TimeZoneDropdown(zone,{zone=it;error=""},!busy)}
                    item {DeskTextField(days,{days=it;error=""},label={Text("历史保留天数")},enabled=!busy,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())}
                    if(error.isNotEmpty()) item {Text(error,color=MaterialTheme.colorScheme.error)}
                    if(serverError.isNotEmpty()) item {Text(serverError,color=MaterialTheme.colorScheme.error)}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp,Alignment.End)) {
                    TextButton({section=""},enabled=!busy) {Text("取消")}
                    DeskButton({runCatching {SettingsInput.basic(zone,days)}.onSuccess {value->save(value) {section=""}}.onFailure {error=it.message ?: "请检查输入"}},enabled=!busy) {Text(if(busy) "保存中…" else "保存")}
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable private fun TimeZoneDropdown(value: String,onSelect: (String)->Unit,enabled: Boolean) {
    var expanded by remember {mutableStateOf(false)}
    val choices=remember(value) {linkedMapOf(
        "Asia/Shanghai" to "北京时间","UTC" to "协调世界时","Asia/Hong_Kong" to "香港","Asia/Taipei" to "台北",
        "Asia/Tokyo" to "东京","Asia/Seoul" to "首尔","Asia/Singapore" to "新加坡","Asia/Bangkok" to "曼谷",
        "Asia/Kolkata" to "新德里","Asia/Dubai" to "迪拜","Europe/London" to "伦敦","Europe/Paris" to "巴黎",
        "Europe/Berlin" to "柏林","Europe/Moscow" to "莫斯科","America/New_York" to "纽约","America/Chicago" to "芝加哥",
        "America/Denver" to "丹佛","America/Los_Angeles" to "洛杉矶","America/Sao_Paulo" to "圣保罗",
        "Australia/Sydney" to "悉尼","Pacific/Auckland" to "奥克兰").apply {if(value !in this) put(value,value)}}
    fun label(id: String)=if(choices[id]==id) id else "${choices[id]} · $id"
    Box(Modifier.fillMaxWidth()) {
        Surface(onClick={expanded=true},enabled=enabled,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {contentDescription="选择时区"}) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("时区",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label(value),style=MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Default.ArrowDropDown,contentDescription=null)
            }
        }
        DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=320.dp)) {
            choices.keys.forEach {id->DropdownMenuItem(text={Text(label(id))},onClick={onSelect(id);expanded=false})}
        }
    }
}

@Composable internal fun PasswordSettings(busy: Boolean,change: (String,String)->Unit,serverError: String="") {
    var show by remember {mutableStateOf(false)}
    var current by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf("")}
    var attempted by remember {mutableStateOf(false)}
    fun close() {show=false;current="";password="";confirm="";attempted=false}
    DeskIconAction("修改密码",SettingsGlyphs.Password,{show=true},enabled=!busy)
    if(show) AlertDialog(onDismissRequest={if(!busy) close()},title={Text("修改 MyDesk 密码")},text={PasswordFields(current,password,confirm,{index,value->when(index) {0->current=value;1->password=value;else->confirm=value}},if(attempted && !busy) serverError else "")},confirmButton={TextButton({attempted=true;change(current,password)},enabled=!busy && current.isNotEmpty() && password.length in 12..256 && password == confirm) {Text("修改并重新登录")}},dismissButton={TextButton({close()},enabled=!busy) {Text("取消")}})
}

@Composable internal fun NetworkToggle(enabled: Boolean,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=enabled,role=Role.Switch,onValueChange=onChange).padding(vertical=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("启用网络检测",Modifier.weight(1f))
        Switch(enabled,onCheckedChange=null)
    }
}

@Composable internal fun PasswordFields(current: String,password: String,confirm: String,onChange: (Int,String)->Unit,serverError: String) {
    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("修改后所有设备需要重新登录。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        listOf("当前密码" to current,"新密码（12–256 个字符）" to password,"再次输入新密码" to confirm).forEachIndexed {index,(label,value) ->
            item(key=index) {DeskTextField(value,{onChange(index,it)},label={Text(label)},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())}
        }
        if(confirm.isNotEmpty() && confirm != password) item {Text("两次新密码不一致",color=MaterialTheme.colorScheme.error)}
        if(serverError.isNotEmpty()) item {Text(serverError,color=MaterialTheme.colorScheme.error)}
    }
}
