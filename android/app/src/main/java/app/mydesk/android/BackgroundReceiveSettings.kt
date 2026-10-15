package app.mydesk.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun BackgroundReceiveSettings(graph: AppGraph,home: HomeState,notify: ()->Unit,settings: ()->Unit) {
    val state by graph.backgroundReceiving.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    DeskCard("后台实时接收") {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(state.enabled,role=Role.Switch,onValueChange={value->graph.perform {graph.backgroundReceiving.enable(value)}}),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("桌面与锁屏接收",style=MaterialTheme.typography.titleSmall)
                Text(when {
                    !state.enabled -> "已关闭 · 使用系统定时同步"
                    state.starting -> "正在启动后台接收"
                    state.active && home.realtimeConnected -> "已连接 · 后台接收中"
                    state.active -> "正在连接 · 网络恢复后自动重试"
                    else -> "已开启 · 接收服务未运行"
                },style=MaterialTheme.typography.bodyMedium)
            }
            Switch(state.enabled,onCheckedChange=null)
        }
        Text("开启后保留一条静音状态通知，APP 返回桌面后继续接收新消息，会增加耗电。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.error.isNotBlank()) {
            Text(state.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            TextButton(notify) {Text("检查通知权限")}
            TextButton(settings) {Text("系统通知设置")}
        }
        if(state.enabled && !state.active && !state.starting) DeskOutlinedButton({graph.backgroundReceiving.startIfEnabled()}) {Text("重试后台接收")}
        if(state.enabled) {
            Text("锁屏接收延迟时，请在系统电池设置中允许 MyDesk 后台运行。强行停止 APP 后需要重新打开。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) {Text("后台与电池设置")}
        }
    }
}
