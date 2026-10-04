package app.mydesk.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant

@Composable internal fun DeskStateBadge(label: String,alert: Boolean=false) {
    Surface(shape=MaterialTheme.shapes.small,color=if(alert) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Text(label,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,
            color=if(alert) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

internal fun reminderStatusLabel(reminder: Reminder): String=when {
    reminder.status in setOf("pending","snoozed") && runCatching {Instant.parse(reminder.remindAt) <= Instant.now()}.getOrDefault(false) -> "已到时间"
    reminder.status == "pending" -> "待提醒"
    else -> status(reminder.status)
}

internal fun reminderTime(value: String,zone: String,fullDate: Boolean=false): String=runCatching {
    val time=Instant.parse(value).atZone(java.time.ZoneId.of(zone))
    val pattern=(if(fullDate) "yyyy-MM-dd" else "MM-dd")+if(time.second != 0) " HH:mm:ss" else " HH:mm"
    time.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}.getOrDefault("尚未同步")

@Composable internal fun ReminderCard(reminder: Reminder,zone: String,busy: Boolean,focused: Boolean=false,detail: (()->Unit)?=null,action: (String,Int)->Unit) {
    Card(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
        border=if(focused) BorderStroke(1.dp,MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(reminder.title,modifier=Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(detail != null) IconButton(detail,modifier=Modifier.size(48.dp)) {Icon(Icons.AutoMirrored.Filled.ArrowForward,contentDescription="查看提醒详情")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.DateRange,contentDescription=null,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(reminderTime(reminder.remindAt,zone),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DeskStateBadge(reminderStatusLabel(reminder))
                }
                DeskButton({action("complete",10)},enabled=!busy) {Text("完成")}
            }
        }
    }
}
