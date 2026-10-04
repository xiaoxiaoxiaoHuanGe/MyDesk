package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import kotlinx.serialization.json.*

@Composable internal fun NativeNotificationSettings(state: HomeState,graph: AppGraph) {
    val status=state.pushStatus
    fun flag(name: String)=status?.get(name)?.jsonPrimitive?.booleanOrNull == true
    DeskCard("即时告警与投递记录") {
        Text(if(graph.push.configured) "App 原生推送已配置" else "App 原生推送尚未配置")
        if(status != null) {
            Text(if(flag("push_available")) "后端发送凭据已配置" else "后端发送凭据尚未配置")
            Text(if(flag("token_registered_locally")) "本机已有原生 Token" else "本机尚未取得原生 Token")
        }
        Text("定时提醒由手机闹钟执行。服务器产生的即时告警需要配置原生推送。",style=MaterialTheme.typography.bodySmall)
        TextButton({graph.perform { graph.notificationStatus() }},enabled=!state.busy) { Text("刷新通知状态") }
        DeskOutlinedButton({graph.perform { graph.testNotification() }},enabled=!state.busy && flag("push_available") && flag("token_registered_locally")) { Text("发送原生测试通知") }
        status?.rows("deliveries")?.take(5)?.forEach { delivery ->
            val phase=when {
                delivery.text("superseded_at","").isNotEmpty() -> "提醒已更新、处理或过期 · 已跳过旧消息"
                delivery.text("blocked_at","").isNotEmpty() -> "手机通知权限或渠道已关闭"
                delivery.text("display_requested_at","").isNotEmpty() -> "手机已请求系统展示 · 请以通知中心为准"
                delivery.text("received_at","").isNotEmpty() -> "手机已接收"
                delivery.text("error","").isNotEmpty() -> delivery.text("error")
                delivery.text("submitted_at","").isNotEmpty() -> "平台已提交 · 等待手机回执"
                else -> "已入队 · 等待投递"
            }
            HorizontalDivider()
            Text(delivery.text("title"))
            Text(phase,style=MaterialTheme.typography.bodySmall)
        }
    }
}
