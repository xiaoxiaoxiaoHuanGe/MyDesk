package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AttentionNetworkSheet(snapshot: JsonObject,id: String,busy: Boolean,refresh: ()->Unit,close: ()->Unit) {
    val data=snapshot.obj("feeds").obj("network").obj("data")
    val node=data.rows("nodes").firstOrNull {it.text("name")==id}
    DeskModalSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("网络检测 · $id",style=MaterialTheme.typography.titleMedium)
            Text("检测位置：运行 MyDesk 的服务器",style=MaterialTheme.typography.bodySmall)
            if(node!=null) {
                Text(node.text("host"));Text(node.text("error",if(node.text("online")=="true") "节点在线" else "节点未回应"))
                Text("Ping ${node.text("ping","—")} ms")
            } else Text(if(data.text("online")=="true") "Internet 在线" else data.text("error","Internet 连接异常"))
            Text("更新于 ${date(snapshot.obj("feeds").obj("network").text("updated_at"),snapshot.text("timezone","Asia/Shanghai"))}")
            DeskOutlinedButton(refresh,enabled=!busy) {Text("重新检测")}
            TextButton(close) {Text("关闭")}
        }
    }
}
