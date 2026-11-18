package app.mydesk.android

import android.app.NotificationManager
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun BackgroundReceiveSettings(graph: AppGraph,home: HomeState,notify: ()->Unit,settings: ()->Unit) {
    val state by graph.backgroundReceiving.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val manager=context.getSystemService(NotificationManager::class.java)
    DeskCard("后台实时接收",headerAction={
        Switch(state.enabled,{value->graph.perform {graph.backgroundReceiving.enable(value)}},modifier=Modifier.semantics {contentDescription="后台实时接收"})
    }) {
        Text(when {
            !state.enabled -> "已关闭"
            state.starting -> "正在启动"
            state.active && home.realtimeConnected -> "已连接 · 新消息自动通知"
            state.active -> home.realtimeStatus.label
            else -> "接收暂停，请重试"
        },style=MaterialTheme.typography.bodyMedium)
        if(state.error.isNotBlank()) Text(state.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        if(state.enabled) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                TextButton({
                    DeskNotificationChannels.create(context)
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,DeskNotificationChannels.RECEIVER))
                }) {Text(if(manager.getNotificationChannel(DeskNotificationChannels.RECEIVER)?.importance==NotificationManager.IMPORTANCE_NONE) "接收状态已隐藏" else "隐藏接收状态")}
                TextButton({context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) {Text("后台与电池设置")}
            }
            Text("后台接收运行时可通知新消息；若被系统停止，重新打开 APP 可恢复。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(state.enabled && !state.active && !state.starting) DeskOutlinedButton({graph.backgroundReceiving.startIfEnabled()}) {Text("重新连接")}
    }
}
