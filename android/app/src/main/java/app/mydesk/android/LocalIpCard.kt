package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun LocalIpCard(state: LocalIpState,zone: String,onRefresh: ()->Unit,modifier: Modifier=Modifier) {
    Box(modifier) {
        DeskCard("本机 IP",headerAction={
            TextButton(onClick=onRefresh,enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=8.dp)) {
                Icon(Icons.Default.Refresh,null,modifier=Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("刷新")
            }
        }) {
            state.exits.forEach {result->LocalIpExitRow(result)}
            state.updatedAt?.let {Text("更新于 ${date(it.toString(),zone)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Text("检测位置：当前设备",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun LocalIpExitRow(result: IpExitResult) {
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(result.endpoint.label,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                DeskStateBadge(when(result.status) {
                    IpExitStatus.LOADING->"查询中"
                    IpExitStatus.SUCCESS->"已获取"
                    IpExitStatus.TIMEOUT->"超时"
                    IpExitStatus.FAILED->"查询失败"
                },result.status in setOf(IpExitStatus.TIMEOUT,IpExitStatus.FAILED))
            }
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(result.ip.ifBlank {"—"},style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium)
                if(result.status==IpExitStatus.SUCCESS) Text(result.location.ifBlank {"归属地未知"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
