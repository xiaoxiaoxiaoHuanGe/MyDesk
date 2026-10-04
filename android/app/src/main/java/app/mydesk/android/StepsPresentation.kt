package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import java.text.NumberFormat
import java.util.Locale

@Composable internal fun StepsResult(job: JsonObject) {
    val state=job.text("status")
    val label=when(state) {
        "success"->"提交成功"; "failed"->"提交失败"; "queued"->"排队中"
        "running"->"执行中"; "dispatching"->"提交中"; "tracking_error"->"需要确认"
        else->"状态未知"
    }
    val raw=job.text("message","").trim()
    val detail=when(raw.lowercase(Locale.ROOT)) {
        "","success","failure","completed","in_progress","queued","dispatching","正在提交","github actions 排队中"->""
        "cancelled"->"任务已取消"; "timed_out"->"执行超时"; "skipped"->"任务未执行"
        "neutral"->"任务未确认成功"; else->raw
    }
    val count=job.text("steps","").toIntOrNull()?.let {NumberFormat.getIntegerInstance(Locale.CHINA).format(it)} ?: "—"
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,
        modifier=Modifier.fillMaxWidth().semantics {liveRegion=LiveRegionMode.Polite}) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("最近提交",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(count,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                    Text("步",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DeskStateBadge(label,state in setOf("failed","tracking_error"))
            }
            if(detail.isNotEmpty()) Text(detail,style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis)
        }
    }
}
