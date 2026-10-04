package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit

@Composable internal fun DeviceNameSettings(current: String,busy: Boolean,save: (String,()->Unit)->Unit,serverError: String,embedded: Boolean=false) {
    var editing by rememberSaveable {mutableStateOf(false)}
    val deviceRow: @Composable ()->Unit={
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(embedded) Icon(SettingsGlyphs.Phone,null,modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(embedded) Text("本机设备",style=MaterialTheme.typography.titleSmall)
                Text(current.ifBlank {"Android 手机"},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({editing=true},enabled=!busy,modifier=Modifier.size(48.dp)) {Icon(Icons.Default.Edit,contentDescription="编辑设备名称")}
        }
    }
    if(embedded) deviceRow() else DeskCard("本机设备") {deviceRow()}
    if(editing) {
        var name by remember(current) {mutableStateOf(current)}
        var error by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={if(!busy) editing=false},title={Text("设备名称")},text={Column {
            DeskTextField(name,{name=it;error=""},label={Text("名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),isError=error.isNotEmpty(),supportingText={Text(error.ifEmpty {"最多 80 个字符"})})
            if(serverError.isNotEmpty()) Text(serverError,color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton({runCatching {InputRules.deviceName(name)}.onSuccess {save(it) {editing=false}}.onFailure {error=it.message ?: "请检查设备名称"}},enabled=!busy) {Text("保存")}},dismissButton={TextButton({editing=false},enabled=!busy) {Text("取消")}})
    }
}
