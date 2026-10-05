package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun ConnectionStatus(connected: Boolean) {
    if(connected) return
    val dark=MaterialTheme.colorScheme.background.luminance() < 0.3f
    val yellow=if(dark) Color(0xFFE0BC68) else Color(0xFF8C6518)
    Surface(shape=MaterialTheme.shapes.small,color=yellow.copy(alpha=0.10f)) {
        Row(Modifier.padding(horizontal=8.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Default.Warning,contentDescription=null,modifier=Modifier.size(15.dp),tint=yellow)
            Text("未连接",style=MaterialTheme.typography.bodySmall,color=yellow)
        }
    }
}

@Composable internal fun WorkbenchAttentionCard(attention: List<kotlinx.serialization.json.JsonObject>,hasSnapshot: Boolean,
    busy: Boolean=false,onAcknowledge: (kotlinx.serialization.json.JsonObject)->Unit={},onOpen: (kotlinx.serialization.json.JsonObject)->Unit={}) {
    Card(Modifier.fillMaxWidth().height(280.dp).testTag("attention-card"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("需要处理",Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                if(attention.isNotEmpty()) Text("${attention.size} 项",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("attention-list"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(attention.isEmpty()) item {Text(if(hasSnapshot) "当前没有待处理事项" else "等待首次同步",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(attention) {entry ->
                    Surface(onClick={onOpen(entry)},shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(if(entry.text("kind")=="steps") entry.text("title").replaceFirst("微信步数","步数") else entry.text("title"),style=MaterialTheme.typography.titleSmall)
                            Text(if(entry.text("kind")=="steps" && entry.text("message")=="failure") "提交失败" else entry.text("message"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(entry.text("kind","")=="steps" && entry.text("ack_token","").isNotBlank()) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                                TextButton({onOpen(entry)},enabled=!busy) {Text("查看原因")}
                                FilledTonalButton({onAcknowledge(entry)},enabled=!busy) {Text("已知晓")}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun WorkbenchTaskCard(tasks: List<kotlinx.serialization.json.JsonObject>,zone: String,detail: (kotlinx.serialization.json.JsonObject)->Unit,history: ()->Unit) {
    DeskCard("自动任务") {
        if(tasks.isEmpty()) Text("暂无执行记录",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        tasks.forEach {task ->
            Surface(onClick={detail(task)},shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(task.text("task_name"),modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        DeskStateBadge(status(task.text("status")),task.text("status") == "failed")
                    }
                    if(task.text("task_id","").startsWith("github.")) {
                        val parts=task.text("message","").split(" · ").filter {it.isNotBlank() && it != "签到成功" && !it.matches(Regex("成功 \\d+ / 失败 \\d+ / 已签到 \\d+"))}
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            parts.forEach {part -> Text(part,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        }
                    } else if(task.text("message","").isNotBlank()) Text(task.text("message"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(date(task.text("timestamp",""),zone),modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward,contentDescription=null,modifier=Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        DeskOutlinedButton(history,modifier=Modifier.fillMaxWidth()) {Text("查看全部历史")}
    }
}
