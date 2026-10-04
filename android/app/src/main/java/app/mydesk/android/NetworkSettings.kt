package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.util.UUID

private data class NodeDraft(val id: String=UUID.randomUUID().toString(),val name: String="",val host: String="")

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NetworkSettings(config: JsonObject,busy: Boolean,save: (JsonObject,()->Unit)->Unit,onClose: ()->Unit,serverError: String) {
    var nodes by remember {mutableStateOf(config.obj("network").rows("nodes").map {NodeDraft(name=it.text("name"),host=it.text("host"))})}
    var enabled by remember {mutableStateOf(config.obj("network").text("enabled")=="true")}
    var query by remember {mutableStateOf("")}
    var alphabetical by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<NodeDraft?>(null)}
    var error by remember {mutableStateOf("")}
    val visible=nodes.filter {it.name.contains(query.trim(),true) || it.host.contains(query.trim(),true)}.let {if(alphabetical) it.sortedBy {node->node.name.lowercase()} else it}
    DeskModalSheet(onDismissRequest={if(!busy) onClose()},dragDismissEnabled=!busy,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(editing!=null) "编辑节点" else "网络连通性",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton(onClose,enabled=!busy) {Icon(Icons.Default.Close,"关闭设置")}
            }
            if(editing==null) {
                NetworkToggle(enabled,{enabled=it})
                DeskTextField(query,{query=it},label={Text("搜索节点名称或地址")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(query.isBlank()) "${nodes.size} 个节点" else "找到 ${visible.size} / ${nodes.size} 个节点",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    TextButton({alphabetical=!alphabetical},enabled=!busy) {Text(if(alphabetical) "恢复原顺序" else "按名称排序")}
                }
            }
            LazyColumn(Modifier.heightIn(max=320.dp).testTag("settings-fields"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                val draft=editing
                if(draft!=null) {
                    item {DeskTextField(draft.name,{editing=draft.copy(name=it);error=""},label={Text("节点名称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())}
                    item {DeskTextField(draft.host,{editing=draft.copy(host=it);error=""},label={Text("域名或 IP")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())}
                } else {
                    if(visible.isEmpty()) item {Text(if(nodes.isEmpty()) "还没有节点，添加后开始检测。" else "没有匹配的节点，试试名称或地址。",style=MaterialTheme.typography.bodySmall)}
                    items(visible,key={it.id}) {node->
                        Surface(onClick={editing=node;error=""},enabled=!busy,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier=Modifier.fillMaxWidth().semantics {contentDescription="编辑节点 ${node.name}"}) {
                            Row(Modifier.padding(start=14.dp,end=4.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(node.name,style=MaterialTheme.typography.titleSmall);Text(node.host,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                Icon(Icons.Default.Edit,null,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton({nodes=nodes.filter {it.id!=node.id};error=""},enabled=!busy) {Icon(Icons.Default.Close,"删除节点 ${node.name}")}
                            }
                        }
                    }
                    item {DeskOutlinedButton({editing=NodeDraft();error=""},enabled=!busy && nodes.size<20,modifier=Modifier.fillMaxWidth()) {Text("添加节点")}}
                }
                if(error.isNotEmpty()) item {Text(error,color=MaterialTheme.colorScheme.error)}
                if(serverError.isNotEmpty()) item {Text(serverError,color=MaterialTheme.colorScheme.error)}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp,Alignment.End)) {
                TextButton({if(editing!=null) {editing=null;error=""} else onClose()},enabled=!busy) {Text(if(editing!=null) "取消编辑" else "取消")}
                DeskButton({
                    val draft=editing
                    if(draft!=null) {
                        val candidate=if(nodes.any {it.id==draft.id}) nodes.map {if(it.id==draft.id) draft.copy(name=draft.name.trim(),host=draft.host.trim()) else it} else nodes+draft.copy(name=draft.name.trim(),host=draft.host.trim())
                        runCatching {SettingsInput.network(enabled,candidate.map {it.name to it.host})}.onSuccess {nodes=candidate;editing=null;error=""}.onFailure {error=it.message ?: "请检查节点"}
                    } else runCatching {SettingsInput.network(enabled,nodes.map {it.name to it.host})}.onSuccess {save(it,onClose)}.onFailure {error=it.message ?: "请检查节点"}
                },enabled=!busy) {Text(if(busy) "保存中…" else if(editing!=null) "完成编辑" else "保存")}
            }
            if(editing==null) Text("由运行 MyDesk 的电脑检测；搜索和排序不改变保存的节点。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}
